package com.xiyuan.plugmc.llmchat;

import com.google.gson.JsonArray;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会话历史、并发控制与吐槽限流。不接触 Bukkit 世界，网络回调只在这里改状态，
 * 发送消息交给调用方回到主线程处理。
 */
final class LlmChatService {
	private final LlmClient client;
	private final Map<UUID, Deque<ChatTurn>> histories = new HashMap<>();
	private final Set<UUID> inFlight = new HashSet<>();
	private final Deque<Long> banterTimes = new ArrayDeque<>();
	private LlmConfig config;
	private long generation;
	private long lastBanterAt;
	private boolean banterInFlight;

	private record ChatTurn(String role, String content) {
	}

	LlmChatService(LlmClient client, LlmConfig config) {
		this.client = client;
		this.config = config;
	}

	synchronized LlmConfig config() {
		return config;
	}

	/** 配置热重载：丢弃进行中的请求与全部会话历史。 */
	synchronized void reload(LlmConfig newConfig) {
		this.config = newConfig;
		this.generation++;
		histories.clear();
		inFlight.clear();
		banterTimes.clear();
		banterInFlight = false;
	}

	synchronized int sessionCount() {
		return histories.size();
	}

	synchronized int inFlightCount() {
		return inFlight.size();
	}

	synchronized void clear(UUID session) {
		histories.remove(session);
	}

	synchronized void clearAll() {
		histories.clear();
	}

	/** 单轮提问；异常经由返回的 future 传递给调用方。 */
	CompletableFuture<String> ask(UUID session, String text) {
		LlmConfig current;
		long requestGeneration;
		Deque<ChatTurn> history;
		JsonArray messages = new JsonArray();

		synchronized (this) {
			current = config;
			if (current.api().apiKey().isBlank()) {
				return CompletableFuture.failedFuture(
					new LlmClient.LlmException("未配置 API 密钥，请编辑 config.json 后执行 /npc reload")
				);
			}
			if (!inFlight.add(session)) {
				return CompletableFuture.failedFuture(new LlmClient.LlmException("还在思考上一条消息，请稍候"));
			}
			requestGeneration = generation;
			history = histories.computeIfAbsent(session, key -> new ArrayDeque<>());

			messages.add(LlmClient.turn("system", current.api().systemPrompt()));
			for (ChatTurn item : new ArrayList<>(history)) {
				messages.add(LlmClient.turn(item.role(), item.content()));
			}
		}
		messages.add(LlmClient.turn("user", text));

		CompletableFuture<String> future;
		try {
			future = client.complete(current, messages, current.api().maxTokens());
		} catch (RuntimeException exception) {
			synchronized (this) {
				inFlight.remove(session);
			}
			return CompletableFuture.failedFuture(exception);
		}

		return future.handle((content, throwable) -> {
			synchronized (LlmChatService.this) {
				inFlight.remove(session);
				if (requestGeneration != generation) {
					throw new CancellationException("配置已重新加载");
				}
				if (throwable != null) {
					throw new CompletionException(unwrap(throwable));
				}
				String reply = content == null ? "" : content.trim();
				if (reply.isEmpty()) {
					throw new CompletionException(new LlmClient.LlmException("模型没有返回内容"));
				}
				history.addLast(new ChatTurn("user", text));
				history.addLast(new ChatTurn("assistant", reply));
				trimHistory(history, current.npc().historyTurns());
				return reply;
			}
		});
	}

	/**
	 * 随机吐槽。普通调用会走概率/冷却/时间窗限制；{@code force} 只保留"同时只有一个请求"的保护。
	 * 返回 {@code Optional.empty()} 表示按配置跳过本次吐槽。
	 */
	CompletableFuture<Optional<String>> banter(String prompt, boolean force) {
		LlmConfig current;
		LlmConfig.Banter settings;
		long requestGeneration;
		String text = prompt == null ? "" : prompt.trim();

		synchronized (this) {
			current = config;
			settings = current.banter();
			if (!settings.enabled() || current.api().apiKey().isBlank()) {
				return CompletableFuture.completedFuture(Optional.empty());
			}
			if (text.length() < settings.minLength()) {
				return CompletableFuture.completedFuture(Optional.empty());
			}
			if (banterInFlight) {
				return CompletableFuture.completedFuture(Optional.empty());
			}

			long now = System.currentTimeMillis();
			if (!force) {
				long windowMillis = settings.windowSeconds() * 1000L;
				while (!banterTimes.isEmpty() && now - banterTimes.peekFirst() >= windowMillis) {
					banterTimes.removeFirst();
				}
				if (banterTimes.size() >= settings.maxPerWindow()) {
					return CompletableFuture.completedFuture(Optional.empty());
				}
				if (now - lastBanterAt < settings.cooldownSeconds() * 1000L) {
					return CompletableFuture.completedFuture(Optional.empty());
				}
				if (ThreadLocalRandom.current().nextDouble() >= settings.chance()) {
					return CompletableFuture.completedFuture(Optional.empty());
				}
			}

			banterInFlight = true;
			lastBanterAt = now;
			banterTimes.addLast(now);
			requestGeneration = generation;
		}

		JsonArray messages = new JsonArray();
		messages.add(LlmClient.turn("system", settings.systemPrompt()));
		messages.add(LlmClient.turn("user", text));

		CompletableFuture<String> future;
		try {
			future = client.complete(current, messages, settings.maxTokens());
		} catch (RuntimeException exception) {
			synchronized (this) {
				banterInFlight = false;
			}
			return CompletableFuture.failedFuture(exception);
		}

		return future.handle((content, throwable) -> {
			synchronized (LlmChatService.this) {
				banterInFlight = false;
				if (requestGeneration != generation) {
					throw new CancellationException("配置已重新加载");
				}
				if (throwable != null) {
					throw new CompletionException(unwrap(throwable));
				}
				String reply = content == null ? "" : content.trim();
				return Optional.ofNullable(reply.isEmpty() ? null : reply);
			}
		});
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

	private static Throwable unwrap(Throwable throwable) {
		Throwable current = throwable;
		while ((current instanceof CompletionException || current instanceof ExecutionException)
			&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	static String rootMessage(Throwable throwable) {
		Throwable current = unwrap(throwable);
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}

	static boolean isCancellation(Throwable throwable) {
		return unwrap(throwable) instanceof CancellationException;
	}
}
