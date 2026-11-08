package com.xiyuan.plugmc.hot.llmchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.xiyuan.plugmc.api.HotPlugin;
import com.xiyuan.plugmc.api.HotPluginContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 常驻聊天 NPC：聊天栏以触发前缀呼叫，异步请求 OpenAI 兼容接口后广播回复。
 * 目前只做基础单轮/短期上下文聊天。
 */
public final class LlmChatPlugin implements HotPlugin {
	private static final String CONFIG_FILE = "llm-chat.json";
	private static final UUID CONSOLE_SESSION = new UUID(0L, 0L);

	private final Map<UUID, Deque<ChatTurn>> histories = new HashMap<>();
	private final Set<UUID> inFlight = new HashSet<>();

	private HotPluginContext context;
	private Logger logger;
	private Path configPath;
	private volatile LlmConfig config;
	private volatile boolean enabled;
	private volatile long generation;
	private HttpClient http;

	private record ChatTurn(String role, String content) {
	}

	@Override
	public void onEnable(HotPluginContext context) {
		this.context = context;
		this.logger = context.logger();
		this.configPath = context.server().getServerDirectory().resolve("plugmc-hot").resolve(CONFIG_FILE);
		this.http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

		try {
			this.config = LlmConfig.load(configPath);
		} catch (Exception exception) {
			this.config = LlmConfig.defaults();
			logger.error("读取 {} 失败，使用默认配置: {}", configPath, exception.getMessage());
		}

		if (config.api().apiKey().isBlank()) {
			logger.warn("未配置 API 密钥：请编辑 {}（或设置 SILICONFLOW_API_KEY）后执行 /npc reload", configPath);
		}

		enabled = true;
		context.onChatMessage(this::onChatMessage);
		context.registerCommand(buildCommand());

		logger.info(
			"LLM 聊天 NPC 已加载: {} model={} endpoint={}",
			config.npc().name(),
			config.api().model(),
			config.api().endpoint()
		);
	}

	@Override
	public void onDisable() {
		enabled = false;
		generation++;
		histories.clear();
		inFlight.clear();
		if (http != null) {
			try {
				http.close();
			} catch (Exception ignored) {
			}
		}
		logger.info("LLM 聊天 NPC 已卸载");
	}

	private void onChatMessage(ServerPlayer sender, String message) {
		if (!enabled) {
			return;
		}

		LlmConfig current = config;
		String prefix = current.npc().triggerPrefix();
		if (!message.regionMatches(true, 0, prefix, 0, prefix.length())) {
			return;
		}
		if (message.length() > prefix.length() && !Character.isWhitespace(message.charAt(prefix.length()))) {
			return;
		}

		String text = message.substring(prefix.length()).trim();
		if (text.isEmpty()) {
			sender.sendSystemMessage(Component.literal(current.npc().replyPrefix() + "用法: " + prefix + " <内容>"));
			return;
		}

		ask(sender.getUUID(), sender.getName().getString(), text, sender);
	}

	private void ask(UUID session, String speaker, String text, ServerPlayer player) {
		if (!enabled) {
			return;
		}

		LlmConfig current = config;
		if (current.api().apiKey().isBlank()) {
			notify(player, "未配置 API 密钥，请编辑 " + CONFIG_FILE + " 后执行 /npc reload");
			return;
		}
		if (!inFlight.add(session)) {
			notify(player, "还在思考上一条消息，请稍候");
			return;
		}

		long requestGeneration = generation;
		Deque<ChatTurn> history = histories.computeIfAbsent(session, key -> new ArrayDeque<>());
		List<ChatTurn> snapshot = new ArrayList<>(history);

		JsonObject body = new JsonObject();
		body.addProperty("model", current.api().model());
		body.addProperty("temperature", current.api().temperature());
		body.addProperty("max_tokens", current.api().maxTokens());
		body.addProperty("stream", false);
		if (current.api().model().toLowerCase(java.util.Locale.ROOT).contains("qwen")) {
			body.addProperty("enable_thinking", current.api().enableThinking());
		}

		JsonArray messages = new JsonArray();
		messages.add(turn("system", current.api().systemPrompt()));
		for (ChatTurn item : snapshot) {
			messages.add(turn(item.role(), item.content()));
		}
		messages.add(turn("user", text));
		body.add("messages", messages);

		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(URI.create(current.api().endpoint()))
				.timeout(Duration.ofSeconds(current.api().timeoutSeconds()))
				.header("Authorization", "Bearer " + current.api().apiKey())
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		} catch (RuntimeException exception) {
			inFlight.remove(session);
			logger.warn("构建 LLM 请求失败: {}", exception.getMessage());
			notify(player, "请求配置有误: " + exception.getMessage());
			return;
		}

		logger.info("LLM 请求: {} session={} model={}", speaker, session, current.api().model());
		http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
			.whenComplete((response, throwable) -> {
				try {
					context.server().executeIfPossible(() ->
						handleResponse(requestGeneration, session, player, text, current, history, response, throwable));
				} catch (RuntimeException exception) {
					inFlight.remove(session);
					logger.warn("无法回到服务端线程处理 LLM 响应: {}", exception.getMessage());
				}
			});
	}

	private void handleResponse(
		long requestGeneration,
		UUID session,
		ServerPlayer player,
		String userText,
		LlmConfig current,
		Deque<ChatTurn> history,
		HttpResponse<String> response,
		Throwable throwable
	) {
		inFlight.remove(session);
		if (!enabled || requestGeneration != generation) {
			return;
		}

		if (throwable != null) {
			logger.warn("LLM 请求失败: {}", throwable.toString());
			notify(player, "请求失败: " + rootMessage(throwable));
			return;
		}

		int status = response.statusCode();
		String body = response.body() == null ? "" : response.body();
		if (status < 200 || status >= 300) {
			logger.warn("LLM API 返回 {}: {}", status, truncate(body, 500));
			notify(player, "API 返回 HTTP " + status);
			return;
		}

		String content;
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			JsonArray choices = json.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				throw new IllegalStateException("choices 为空");
			}
			content = choices.get(0).getAsJsonObject()
				.getAsJsonObject("message")
				.get("content")
				.getAsString()
				.trim();
		} catch (RuntimeException exception) {
			logger.warn("解析 LLM 响应失败: {} | {}", exception.getMessage(), truncate(body, 300));
			notify(player, "响应解析失败");
			return;
		}

		if (content.isEmpty()) {
			notify(player, "模型没有返回内容");
			return;
		}

		history.addLast(new ChatTurn("user", userText));
		history.addLast(new ChatTurn("assistant", content));
		trimHistory(history, current.npc().historyTurns());
		reply(content, player);
	}

	private LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
		return Commands.literal("npc")
			.requires(source -> Commands.LEVEL_ALL.check(source.permissions()))
			.then(Commands.literal("status")
				.executes(command -> status(command.getSource())))
			.then(Commands.literal("clear")
				.executes(command -> clear(command.getSource())))
			.then(Commands.literal("chat")
				.then(Commands.argument("message", StringArgumentType.greedyString())
					.executes(command -> chat(command.getSource(), StringArgumentType.getString(command, "message")))))
			.then(Commands.literal("reload")
				.requires(admin())
				.executes(command -> reload(command.getSource())));
	}

	private int status(CommandSourceStack source) {
		LlmConfig current = config;
		String text = "endpoint=" + current.api().endpoint()
			+ " model=" + current.api().model()
			+ " key=" + mask(current.api().apiKey())
			+ " 会话=" + histories.size()
			+ " 进行中=" + inFlight.size()
			+ " 配置=" + configPath;
		source.sendSuccess(() -> Component.literal("[llm-chat] " + text), false);
		return 1;
	}

	private int clear(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player != null) {
			histories.remove(player.getUUID());
			source.sendSuccess(() -> Component.literal("[llm-chat] 已清空你的对话历史"), false);
		} else {
			histories.clear();
			source.sendSuccess(() -> Component.literal("[llm-chat] 已清空全部对话历史"), true);
		}
		return 1;
	}

	private int chat(CommandSourceStack source, String message) {
		String text = message.trim();
		if (text.isEmpty()) {
			source.sendFailure(Component.literal("[llm-chat] 内容不能为空"));
			return 0;
		}

		ServerPlayer player = source.getPlayer();
		UUID session = player != null ? player.getUUID() : CONSOLE_SESSION;
		String speaker = player != null ? player.getName().getString() : "Console";
		ask(session, speaker, text, player);
		source.sendSuccess(() -> Component.literal("[llm-chat] 已发送"), false);
		return 1;
	}

	private int reload(CommandSourceStack source) {
		try {
			config = LlmConfig.load(configPath);
			generation++;
			histories.clear();
			inFlight.clear();
			logger.info("LLM 配置已重新加载: model={} endpoint={}", config.api().model(), config.api().endpoint());
			source.sendSuccess(() -> Component.literal("[llm-chat] 已重新加载配置: model=" + config.api().model()), true);
			return 1;
		} catch (Exception exception) {
			logger.warn("重新加载 LLM 配置失败: {}", exception.getMessage());
			source.sendFailure(Component.literal("[llm-chat] 重新加载失败: " + exception.getMessage()));
			return 0;
		}
	}

	private void reply(String content, ServerPlayer player) {
		LlmConfig current = config;
		Component message = Component.literal(current.npc().replyPrefix() + content);
		if (current.npc().broadcast()) {
			context.server().getPlayerList().broadcastSystemMessage(message, false);
		} else if (player != null) {
			player.sendSystemMessage(message);
		} else {
			logger.info("{}", message.getString());
		}
	}

	private void notify(ServerPlayer player, String message) {
		String text = config.npc().replyPrefix() + message;
		if (player != null) {
			player.sendSystemMessage(Component.literal(text));
		} else {
			logger.info("{}", text);
		}
	}

	private static Predicate<CommandSourceStack> admin() {
		return source -> Commands.LEVEL_ADMINS.check(source.permissions());
	}

	private static JsonObject turn(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	private static void trimHistory(Deque<ChatTurn> history, int turns) {
		int maxEntries = Math.max(0, turns) * 2;
		if (maxEntries == 0) {
			history.clear();
			return;
		}
		while (history.size() > maxEntries) {
			history.removeFirst();
		}
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

	private static String truncate(String text, int max) {
		if (text == null) {
			return "";
		}
		return text.length() <= max ? text : text.substring(0, max) + "...";
	}

	private static String rootMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}
}
