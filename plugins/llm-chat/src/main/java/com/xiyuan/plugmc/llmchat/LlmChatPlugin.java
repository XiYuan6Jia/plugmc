package com.xiyuan.plugmc.llmchat;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/**
 * 常驻聊天 NPC：聊天栏以触发前缀呼叫，异步请求 OpenAI 兼容接口后广播回复。
 * <p>
 * Paper 版说明：玩家聊天走 {@link AsyncChatEvent}，进服欢迎走 {@link PlayerJoinEvent}；
 * 系统的成就/公告消息在 Paper 侧没有对应事件，{@code banter.systemChat} 只能覆盖
 * {@link BroadcastMessageEvent}（插件广播与部分 /say 实现）。
 */
public final class LlmChatPlugin extends JavaPlugin implements Listener {
	static final String CONFIG_FILE = "config.json";
	private static final UUID CONSOLE_SESSION = new UUID(0L, 0L);

	private LlmClient client;
	private LlmChatService service;
	private Path configPath;

	@Override
	public void onEnable() {
		configPath = getDataFolder().toPath().resolve(CONFIG_FILE);
		Path legacyPath = Bukkit.getWorldContainer().toPath()
			.resolve("plugmc-hot")
			.resolve("llm-chat.json");

		LlmConfig config;
		try {
			config = LlmConfig.loadOrMigrate(configPath, legacyPath);
		} catch (Exception exception) {
			config = LlmConfig.defaults();
			getLogger().warning("读取 " + configPath + " 失败，使用默认配置: " + exception.getMessage());
		}
		if (config.api().apiKey().isBlank()) {
			getLogger().warning(
				"未配置 API 密钥：请编辑 " + configPath + "（或设置 SILICONFLOW_API_KEY）后执行 /npc reload"
			);
		}

		client = new LlmClient();
		service = new LlmChatService(client, config);

		getServer().getPluginManager().registerEvents(this, this);
		var command = getCommand("npc");
		if (command != null) {
			NpcCommand executor = new NpcCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		} else {
			getLogger().warning("plugin.yml 缺少 npc 命令定义，/npc 不可用");
		}

		getLogger().info(
			"LLM 聊天 NPC 已启用: " + config.npc().name()
				+ " model=" + config.api().model()
				+ " endpoint=" + config.api().endpoint()
				+ " 配置=" + configPath
		);
	}

	@Override
	public void onDisable() {
		if (service != null) {
			service.clearAll();
		}
		if (client != null) {
			client.close();
		}
		getLogger().info("LLM 聊天 NPC 已卸载");
	}

	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public void onChat(AsyncChatEvent event) {
		String message = plain(event.message()).trim();
		if (message.isEmpty()) {
			return;
		}

		Player sender = event.getPlayer();
		LlmConfig current = service.config();
		String prefix = current.npc().triggerPrefix();
		boolean triggered = message.regionMatches(true, 0, prefix, 0, prefix.length())
			&& (message.length() == prefix.length() || Character.isWhitespace(message.charAt(prefix.length())));

		if (!triggered) {
			maybeBanter(sender, "玩家 " + sender.getName() + " 说", message);
			return;
		}

		String text = message.substring(prefix.length()).trim();
		if (text.isEmpty()) {
			sender.sendMessage(format("用法: " + prefix + " <内容>"));
			return;
		}
		ask(sender.getUniqueId(), sender.getName(), text, sender);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		welcome(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBroadcast(BroadcastMessageEvent event) {
		LlmConfig current = service.config();
		if (!current.banter().enabled() || !current.banter().systemChat()) {
			return;
		}

		String text = plain(event.message()).trim();
		if (text.isEmpty()
			|| text.startsWith(current.npc().replyPrefix())
			|| text.startsWith("[llm-chat]")
			|| text.startsWith("[PlugMC]")) {
			return;
		}
		// 进服/退服消息由欢迎逻辑处理，避免同一次进服产生欢迎 + 吐槽两条消息
		if (current.banter().ignoreJoinLeave() && isJoinLeaveMessage(text)) {
			return;
		}
		maybeBanter(null, "服务器系统消息", text);
	}

	/** 通过聊天栏触发的一次提问。 */
	private void ask(UUID session, String speaker, String text, Player player) {
		LlmConfig current = service.config();
		if (current.api().apiKey().isBlank()) {
			notify(player, "未配置 API 密钥，请编辑 " + CONFIG_FILE + " 后执行 /npc reload");
			return;
		}

		getLogger().info("LLM 请求: " + speaker + " session=" + session + " model=" + current.api().model());
		service.ask(session, speaker, text).whenComplete((reply, throwable) -> runMain(() -> {
			if (throwable != null) {
				reportFailure("LLM 请求", throwable, player);
				return;
			}
			reply(reply, player, current.npc().broadcast());
		}));
	}

	/** /npc chat：玩家或控制台直接对话。 */
	void askFromCommand(Player player, String text) {
		UUID session = player != null ? player.getUniqueId() : CONSOLE_SESSION;
		String speaker = player != null ? player.getName() : "Console";
		ask(session, speaker, text, player);
	}

	private void maybeBanter(Player player, String source, String message) {
		LlmConfig current = service.config();
		LlmConfig.Banter settings = current.banter();
		String text = message.trim();
		if (!settings.enabled() || text.length() < settings.minLength()) {
			return;
		}

		service.banter(source + "：" + text, false).whenComplete((result, throwable) -> runMain(() -> {
			if (throwable != null) {
				if (!LlmChatService.isCancellation(throwable)) {
					getLogger().warning("LLM 吐槽请求失败: " + LlmChatService.rootMessage(throwable));
				}
				return;
			}
			result.ifPresent(content -> {
				getLogger().info("吐槽回复: " + content);
				reply(content, player, settings.broadcast());
			});
		}));
	}

	/** /npc banter：跳过概率与冷却，直接请求一次吐槽。 */
	void forceBanter(Player player, String text) {
		String speaker = player != null ? player.getName() : "Console";
		service.banter("玩家 " + speaker + " 说：" + text.trim(), true)
			.whenComplete((result, throwable) -> runMain(() -> {
				if (throwable != null) {
					reportFailure("LLM 吐槽请求", throwable, player);
					return;
				}
				result.ifPresent(content -> {
					getLogger().info("吐槽回复: " + content);
					reply(content, player, service.config().banter().broadcast());
				});
			}));
	}

	void welcome(Player player) {
		LlmConfig current = service.config();
		if (!current.welcome().enabled()) {
			return;
		}

		String text = formatTemplate(current.welcome().message(), player.getName());
		Component message = format(text);
		if (current.welcome().broadcast()) {
			sendToAll(message);
		} else {
			player.sendMessage(message);
		}
		getLogger().info("欢迎新玩家 " + player.getName() + ": " + text);
	}

	/** /npc welcome：返回 true 表示欢迎语确实发给了在线玩家（离线时只渲染/记录）。 */
	boolean welcomeByName(String playerName) {
		Player target = Bukkit.getPlayerExact(playerName);
		if (target != null) {
			welcome(target);
			return true;
		}

		LlmConfig current = service.config();
		String text = formatTemplate(current.welcome().message(), playerName);
		Component message = format(text);
		if (current.welcome().broadcast()) {
			sendToAll(message);
		} else {
			getLogger().info(plain(message));
		}
		return false;
	}

	/** 重新读取配置；返回 null 表示成功，否则返回失败原因。 */
	String reloadLlmConfig() {
		try {
			LlmConfig config = LlmConfig.load(configPath);
			service.reload(config);
			getLogger().info(
				"LLM 配置已重新加载: model=" + config.api().model() + " endpoint=" + config.api().endpoint()
			);
			return null;
		} catch (Exception exception) {
			getLogger().warning("重新加载 LLM 配置失败: " + exception.getMessage());
			return exception.getMessage();
		}
	}

	LlmChatService service() {
		return service;
	}

	Path configPath() {
		return configPath;
	}

	private String formatTemplate(String template, String playerName) {
		return template
			.replace("{player}", playerName)
			.replace("{online}", Integer.toString(Bukkit.getOnlinePlayers().size()));
	}

	private Component format(String text) {
		return Component.text(service.config().npc().replyPrefix() + text);
	}

	private void reply(String content, Player player, boolean broadcast) {
		Component message = format(content);
		// 后台留一份，便于核对 NPC 是否真的记住了上下文（不含密钥）
		getLogger().info("回复: " + content);
		if (broadcast) {
			sendToAll(message);
		} else if (player != null && player.isOnline()) {
			player.sendMessage(message);
		} else {
			getLogger().info(plain(message));
		}
	}

	private void notify(Player player, String message) {
		Component component = format(message);
		if (player != null && player.isOnline()) {
			player.sendMessage(component);
		} else {
			getLogger().info(plain(component));
		}
	}

	private void sendToAll(Component message) {
		Collection<? extends Player> players = Bukkit.getOnlinePlayers();
		if (players.isEmpty()) {
			getLogger().info(plain(message));
			return;
		}
		for (Player player : players) {
			player.sendMessage(message);
		}
	}

	private void reportFailure(String action, Throwable throwable, Player player) {
		if (LlmChatService.isCancellation(throwable)) {
			return;
		}
		String message = LlmChatService.rootMessage(throwable);
		getLogger().warning(action + "失败: " + message);
		notify(player, "请求失败: " + message);
	}

	private void runMain(Runnable task) {
		if (!isEnabled()) {
			return;
		}
		try {
			Bukkit.getScheduler().runTask(this, task);
		} catch (IllegalPluginAccessException ignored) {
			// 插件正在关闭，丢弃迟到的回调
		}
	}

	private static String plain(Component component) {
		return PlainTextComponentSerializer.plainText().serialize(component);
	}

	private static boolean isJoinLeaveMessage(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		return lower.contains(" joined the game")
			|| lower.contains(" left the game")
			|| text.contains("加入了游戏")
			|| text.contains("离开了游戏");
	}
}
