package com.xiyuan.plugmc.hot.llmchat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * plugmc-hot/llm-chat.json 的读取与默认值。密钥也可以来自环境变量
 * SILICONFLOW_API_KEY / PLUGMC_LLM_API_KEY。
 */
record LlmConfig(Api api, Npc npc, Welcome welcome, Banter banter) {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";
	private static final String DEFAULT_MODEL = "Qwen/Qwen3.5-9B";
	private static final String DEFAULT_SYSTEM_PROMPT =
		"你是 Minecraft 服务器里的常驻聊天 NPC「小页」，说话简洁、友好，使用中文回答。";
	private static final String DEFAULT_WELCOME =
		"欢迎 {player} 加入服务器！我是常驻 NPC 小页，聊天栏输入 @npc 可以找我聊天。";
	private static final String DEFAULT_BANTER_PROMPT =
		"你是 Minecraft 服务器里的常驻 NPC「小页」。请对玩家刚说的这句话做一句简短、幽默、友善的吐槽，"
			+ "最多 40 字，不要人身攻击，不要复述原句。";

	record Api(
		String baseUrl,
		String model,
		String apiKey,
		double temperature,
		int maxTokens,
		int timeoutSeconds,
		boolean enableThinking,
		String systemPrompt
	) {
		String endpoint() {
			String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
			return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
		}
	}

	record Npc(String name, String triggerPrefix, String replyPrefix, int historyTurns, boolean broadcast) {
	}

	record Welcome(boolean enabled, String message, boolean broadcast) {
	}

	record Banter(
		boolean enabled,
		boolean systemChat,
		boolean ignoreJoinLeave,
		double chance,
		int cooldownSeconds,
		int maxPerWindow,
		int windowSeconds,
		int minLength,
		int maxTokens,
		boolean broadcast,
		String systemPrompt
	) {
	}

	static LlmConfig defaults() {
		return new LlmConfig(
			new Api(DEFAULT_BASE_URL, DEFAULT_MODEL, "", 0.7, 256, 60, false, DEFAULT_SYSTEM_PROMPT),
			new Npc("小页", "@npc", "[小页] ", 6, true),
			new Welcome(true, DEFAULT_WELCOME, true),
			new Banter(true, true, true, 0.12, 45, 3, 300, 3, 80, true, DEFAULT_BANTER_PROMPT)
		);
	}

	static LlmConfig load(Path path) throws IOException {
		if (!Files.isRegularFile(path)) {
			LlmConfig defaults = defaults();
			defaults.write(path);
			return defaults;
		}

		JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject api = object(root, "api");
		JsonObject npc = object(root, "npc");
		JsonObject welcome = object(root, "welcome");
		JsonObject banter = object(root, "banter");

		return new LlmConfig(
			new Api(
				string(api, "baseUrl", DEFAULT_BASE_URL),
				string(api, "model", DEFAULT_MODEL),
				resolveApiKey(string(api, "apiKey", "")),
				number(api, "temperature", 0.7),
				clamp(integer(api, "maxTokens", 256), 16, 4096),
				clamp(integer(api, "timeoutSeconds", 60), 5, 300),
				bool(api, "enableThinking", false),
				string(api, "systemPrompt", DEFAULT_SYSTEM_PROMPT)
			),
			new Npc(
				string(npc, "name", "小页"),
				string(npc, "triggerPrefix", "@npc"),
				string(npc, "replyPrefix", "[小页] "),
				clamp(integer(npc, "historyTurns", 6), 0, 20),
				bool(npc, "broadcast", true)
			),
			new Welcome(
				bool(welcome, "enabled", true),
				string(welcome, "message", DEFAULT_WELCOME),
				bool(welcome, "broadcast", true)
			),
			new Banter(
				bool(banter, "enabled", true),
				bool(banter, "systemChat", true),
				bool(banter, "ignoreJoinLeave", true),
				clamp(number(banter, "chance", 0.12), 0.0, 1.0),
				clamp(integer(banter, "cooldownSeconds", 45), 0, 600),
				clamp(integer(banter, "maxPerWindow", 3), 0, 100),
				clamp(integer(banter, "windowSeconds", 300), 10, 3600),
				clamp(integer(banter, "minLength", 3), 0, 100),
				clamp(integer(banter, "maxTokens", 80), 16, 512),
				bool(banter, "broadcast", true),
				string(banter, "systemPrompt", DEFAULT_BANTER_PROMPT)
			)
		);
	}

	void write(Path path) throws IOException {
		JsonObject root = new JsonObject();

		JsonObject apiJson = new JsonObject();
		apiJson.addProperty("baseUrl", api.baseUrl());
		apiJson.addProperty("model", api.model());
		apiJson.addProperty("apiKey", "");
		apiJson.addProperty("temperature", api.temperature());
		apiJson.addProperty("maxTokens", api.maxTokens());
		apiJson.addProperty("timeoutSeconds", api.timeoutSeconds());
		apiJson.addProperty("enableThinking", api.enableThinking());
		apiJson.addProperty("systemPrompt", api.systemPrompt());
		root.add("api", apiJson);

		JsonObject npcJson = new JsonObject();
		npcJson.addProperty("name", npc.name());
		npcJson.addProperty("triggerPrefix", npc.triggerPrefix());
		npcJson.addProperty("replyPrefix", npc.replyPrefix());
		npcJson.addProperty("historyTurns", npc.historyTurns());
		npcJson.addProperty("broadcast", npc.broadcast());
		root.add("npc", npcJson);

		JsonObject welcomeJson = new JsonObject();
		welcomeJson.addProperty("enabled", welcome.enabled());
		welcomeJson.addProperty("message", welcome.message());
		welcomeJson.addProperty("broadcast", welcome.broadcast());
		root.add("welcome", welcomeJson);

		JsonObject banterJson = new JsonObject();
		banterJson.addProperty("enabled", banter.enabled());
		banterJson.addProperty("systemChat", banter.systemChat());
		banterJson.addProperty("ignoreJoinLeave", banter.ignoreJoinLeave());
		banterJson.addProperty("chance", banter.chance());
		banterJson.addProperty("cooldownSeconds", banter.cooldownSeconds());
		banterJson.addProperty("maxPerWindow", banter.maxPerWindow());
		banterJson.addProperty("windowSeconds", banter.windowSeconds());
		banterJson.addProperty("minLength", banter.minLength());
		banterJson.addProperty("maxTokens", banter.maxTokens());
		banterJson.addProperty("broadcast", banter.broadcast());
		banterJson.addProperty("systemPrompt", banter.systemPrompt());
		root.add("banter", banterJson);

		Path parent = path.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(path, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
	}

	private static String resolveApiKey(String configured) {
		if (configured != null && !configured.isBlank()) {
			return configured.trim();
		}
		for (String name : new String[]{"SILICONFLOW_API_KEY", "PLUGMC_LLM_API_KEY"}) {
			String value = System.getenv(name);
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return "";
	}

	private static JsonObject object(JsonObject parent, String key) {
		return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
	}

	private static String string(JsonObject json, String key, String fallback) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
	}

	private static double number(JsonObject json, String key, double fallback) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : fallback;
	}

	private static int integer(JsonObject json, String key, int fallback) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
	}

	private static boolean bool(JsonObject json, String key, boolean fallback) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
