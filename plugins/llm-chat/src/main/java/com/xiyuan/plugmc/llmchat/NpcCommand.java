package com.xiyuan.plugmc.llmchat;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /npc 子命令：status / clear / chat / welcome / banter / reload。 */
final class NpcCommand implements TabExecutor {
	private static final String BASE_PERMISSION = "plugmc.npc";
	private static final String ADMIN_PERMISSION = "plugmc.npc.admin";
	private static final List<String> SUBCOMMANDS = List.of("status", "clear", "chat", "welcome", "banter", "reload");
	private static final List<String> ADMIN_SUBCOMMANDS = List.of("welcome", "banter", "reload");

	private final LlmChatPlugin plugin;

	NpcCommand(LlmChatPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission(BASE_PERMISSION)) {
			sender.sendMessage(text("没有权限使用该命令"));
			return true;
		}
		if (args.length == 0) {
			sender.sendMessage(text("用法: /" + label + " status|clear|chat <内容>|welcome <玩家>|banter <内容>|reload"));
			return true;
		}

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "status" -> status(sender);
			case "clear" -> clear(sender);
			case "chat" -> chat(sender, join(args, 1));
			case "welcome" -> {
				if (requireAdmin(sender)) {
					welcome(sender, join(args, 1));
				}
			}
			case "banter" -> {
				if (requireAdmin(sender)) {
					banter(sender, join(args, 1));
				}
			}
			case "reload" -> {
				if (requireAdmin(sender)) {
					reload(sender);
				}
			}
			default -> sender.sendMessage(text("未知子命令: " + args[0]));
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			return filter(SUBCOMMANDS.stream()
				.filter(name -> !ADMIN_SUBCOMMANDS.contains(name) || sender.hasPermission(ADMIN_PERMISSION))
				.toList(), args[0]);
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("welcome") && sender.hasPermission(ADMIN_PERMISSION)) {
			return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
		}
		return List.of();
	}

	private void status(CommandSender sender) {
		LlmConfig current = plugin.service().config();
		LlmConfig.Banter banter = current.banter();
		boolean shared = plugin.service().shared();
		String summary = "endpoint=" + current.api().endpoint()
			+ " model=" + current.api().model()
			+ " key=" + mask(current.api().apiKey())
			+ " 记忆=" + (shared ? "共享(" + current.npc().historyTurns() + " 轮)" : "独立")
			+ " 会话=" + plugin.service().sessionCount()
			+ " 进行中=" + plugin.service().inFlightCount()
			+ " 欢迎=" + (current.welcome().enabled() ? "on" : "off")
			+ " 吐槽=" + (banter.enabled()
				? "on(" + Math.round(banter.chance() * 100) + "%, " + banter.maxPerWindow() + "/" + banter.windowSeconds() + "s)"
				: "off")
			+ " 配置=" + plugin.configPath();
		sender.sendMessage(text(summary));
	}

	private void clear(CommandSender sender) {
		if (sender instanceof Player player) {
			if (plugin.service().shared()) {
				plugin.service().clearAll();
				sender.sendMessage(text("已清空共享对话记忆（所有玩家可见）"));
				return;
			}
			plugin.service().clear(player.getUniqueId());
			sender.sendMessage(text("已清空你的对话历史"));
		} else {
			plugin.service().clearAll();
			sender.sendMessage(text("已清空全部对话历史"));
		}
	}

	private void chat(CommandSender sender, String message) {
		String content = message.trim();
		if (content.isEmpty()) {
			sender.sendMessage(text("内容不能为空"));
			return;
		}
		Player player = sender instanceof Player online ? online : null;
		plugin.askFromCommand(player, content);
		sender.sendMessage(text("已发送"));
	}

	private void welcome(CommandSender sender, String message) {
		String playerName = message.trim();
		if (playerName.isEmpty()) {
			sender.sendMessage(text("用法: /npc welcome <玩家名>"));
			return;
		}
		if (!plugin.service().config().welcome().enabled()) {
			sender.sendMessage(text("进服欢迎已关闭"));
			return;
		}
		boolean sent = plugin.welcomeByName(playerName);
		sender.sendMessage(text(sent
			? "已发送欢迎语: " + playerName
			: "玩家 " + playerName + " 不在线，欢迎语只写入了日志"));
	}

	private void banter(CommandSender sender, String message) {
		String content = message.trim();
		if (content.isEmpty()) {
			sender.sendMessage(text("用法: /npc banter <内容>"));
			return;
		}
		LlmConfig current = plugin.service().config();
		if (!current.banter().enabled()) {
			sender.sendMessage(text("随机吐槽已关闭"));
			return;
		}
		if (current.api().apiKey().isBlank()) {
			sender.sendMessage(text("未配置 API 密钥"));
			return;
		}
		Player player = sender instanceof Player online ? online : null;
		plugin.forceBanter(player, content);
		sender.sendMessage(text("已发送吐槽请求"));
	}

	private void reload(CommandSender sender) {
		String error = plugin.reloadLlmConfig();
		if (error == null) {
			sender.sendMessage(text("已重新加载配置: model=" + plugin.service().config().api().model()));
		} else {
			sender.sendMessage(text("重新加载失败: " + error));
		}
	}

	private boolean requireAdmin(CommandSender sender) {
		if (sender.hasPermission(ADMIN_PERMISSION)) {
			return true;
		}
		sender.sendMessage(text("需要管理员权限（" + ADMIN_PERMISSION + "）"));
		return false;
	}

	private static Component text(String message) {
		return Component.text("[llm-chat] " + message);
	}

	private static String join(String[] args, int from) {
		return args.length <= from ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length));
	}

	private static List<String> filter(List<String> candidates, String prefix) {
		String lower = prefix.toLowerCase(Locale.ROOT);
		return candidates.stream()
			.filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(lower))
			.sorted()
			.toList();
	}

	private static String mask(String key) {
		if (key == null || key.isBlank()) {
			return "(未配置)";
		}
		if (key.length() <= 8) {
			return "****";
		}
		return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
	}
}
