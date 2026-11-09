package com.xiyuan.plugmc.llmchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** OpenAI 兼容的 /chat/completions 最小客户端：一次请求换取一条回复文本。 */
final class LlmClient implements AutoCloseable {
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	CompletableFuture<String> complete(LlmConfig config, JsonArray messages, int maxTokens) {
		HttpRequest request = buildRequest(config, messages, maxTokens);
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
			.thenApply(response -> {
				int status = response.statusCode();
				String body = response.body() == null ? "" : response.body();
				if (status < 200 || status >= 300) {
					throw new LlmException("API 返回 HTTP " + status + "：" + truncate(body, 200));
				}
				return extractContent(body);
			});
	}

	static JsonObject turn(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	private HttpRequest buildRequest(LlmConfig config, JsonArray messages, int maxTokens) {
		LlmConfig.Api api = config.api();
		JsonObject body = new JsonObject();
		body.addProperty("model", api.model());
		body.addProperty("temperature", api.temperature());
		body.addProperty("max_tokens", maxTokens);
		body.addProperty("stream", false);
		if (api.model().toLowerCase(Locale.ROOT).contains("qwen")) {
			body.addProperty("enable_thinking", api.enableThinking());
		}
		body.add("messages", messages);

		return HttpRequest.newBuilder(URI.create(api.endpoint()))
			.timeout(Duration.ofSeconds(api.timeoutSeconds()))
			.header("Authorization", "Bearer " + api.apiKey())
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
			.build();
	}

	private static String extractContent(String body) {
		JsonObject json = JsonParser.parseString(body).getAsJsonObject();
		JsonArray choices = json.getAsJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new LlmException("响应缺少 choices");
		}
		return choices.get(0).getAsJsonObject()
			.getAsJsonObject("message")
			.get("content")
			.getAsString()
			.trim();
	}

	static String truncate(String text, int max) {
		if (text == null) {
			return "";
		}
		return text.length() <= max ? text : text.substring(0, max) + "...";
	}

	@Override
	public void close() {
		http.shutdownNow();
	}

	static final class LlmException extends RuntimeException {
		LlmException(String message) {
			super(message);
		}
	}
}
