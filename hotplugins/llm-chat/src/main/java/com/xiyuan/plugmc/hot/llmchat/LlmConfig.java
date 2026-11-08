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
record LlmConfig(Api api, Npc npc) {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1";
	private static final String DEFAULT_MODEL = "Qwen/Qwen3.5-9B";
	private static final String DEFAULT_SYSTEM_PROMPT =
		"你是 Minecraft 服务器里的常驻聊天 NPC「小页」，说话简洁、友好，使用中文回答。";

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

	static LlmConfig defaults() {
		return new LlmConfig(
			new Api(DEFAULT_BASE_URL, DEFAULT_MODEL, "", 0.7, 256, 60, false, DEFAULT_SYSTEM_PROMPT),
			new Npc("小页", "@npc", "[小页] ", 6, true)
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
}
