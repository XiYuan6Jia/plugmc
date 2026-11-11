package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家上传：游戏内申请一次性上传码，再到网页把音频 POST 上来。
 *
 * <p>每个账号限一首；上传码 15 分钟过期、只能成功使用一次。</p>
 */
public final class UploadService {
	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final MusicPlugin plugin;
	private final SongLibrary library;
	private final Map<String, Pending> pending = new HashMap<>();

	public UploadService(MusicPlugin plugin, SongLibrary library) {
		this.plugin = plugin;
		this.library = library;
	}

	private record Pending(UUID playerId, String playerName, long expiresAt) {
	}

	public record UploadResult(boolean ok, String message) {
	}

	/**
	 * 生成（或复用）一个上传码；玩家已经有曲目时返回失败原因。
	 */
	public String createToken(Player player, MusicConfig config) {
		Optional<Song> owned = library.ownedBy(player.getUniqueId());
		if (owned.isPresent()) {
			return null;
		}
		prune();
		for (Map.Entry<String, Pending> entry : pending.entrySet()) {
			if (entry.getValue().playerId().equals(player.getUniqueId())) {
				return entry.getKey();
			}
		}
		String token = randomToken();
		pending.put(
			token,
			new Pending(
				player.getUniqueId(),
				player.getName(),
				System.currentTimeMillis() + config.uploadTokenSeconds() * 1000L
			)
		);
		return token;
	}

	/**
	 * 管理员代为生成上传码（玩家不方便自己执行命令时使用，也便于后台排障）。
	 */
	public String createToken(UUID playerId, String playerName, MusicConfig config) {
		prune();
		for (Map.Entry<String, Pending> entry : pending.entrySet()) {
			if (entry.getValue().playerId().equals(playerId)) {
				return entry.getKey();
			}
		}
		String token = randomToken();
		pending.put(
			token,
			new Pending(
				playerId,
				playerName,
				System.currentTimeMillis() + config.uploadTokenSeconds() * 1000L
			)
		);
		return token;
	}

	public long tokenSecondsLeft(String token) {
		Pending entry = pending.get(token);
		if (entry == null) {
			return 0;
		}
		long left = entry.expiresAt() - System.currentTimeMillis();
		return left <= 0 ? 0 : (left + 999) / 1000;
	}

	/**
	 * 处理网页上传的原始字节。
	 *
	 * <p>HTTP 线程不能直接改唱片库：Bukkit 的数据结构与资源包打包都要求主线程，
	 * 因此这里把工作投递到主线程并等待结果。</p>
	 */
	public UploadResult accept(String token, String title, byte[] data, MusicConfig config) {
		if (Bukkit.isPrimaryThread()) {
			return acceptSync(token, title, data, config);
		}
		java.util.concurrent.CompletableFuture<UploadResult> future =
			new java.util.concurrent.CompletableFuture<>();
		Bukkit.getScheduler().runTask(plugin, () -> {
			try {
				future.complete(acceptSync(token, title, data, config));
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("处理上传失败：" + exception);
				future.complete(new UploadResult(false, "服务器内部错误：" + exception.getMessage()));
			}
		});
		try {
			return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new UploadResult(false, "上传被中断，请重试");
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
			return new UploadResult(false, "服务器繁忙，请稍后再试");
		}
	}

	private UploadResult acceptSync(String token, String title, byte[] data, MusicConfig config) {
		prune();
		Pending entry = token == null ? null : pending.get(token.toUpperCase());
		if (entry == null) {
			return new UploadResult(false, "上传码无效或已过期，请在游戏内重新执行 /music upload");
		}
		if (data == null || data.length == 0) {
			return new UploadResult(false, "没有收到文件内容");
		}
		if (data.length > config.uploadMaxBytes()) {
			return new UploadResult(false, "文件超过 " + (config.uploadMaxBytes() / 1024 / 1024)
				+ " MB 限制");
		}
		if (library.ownedBy(entry.playerId()).isPresent()) {
			pending.remove(token.toUpperCase());
			return new UploadResult(false, "你这个账号已经有唱片了，先删除旧唱片再上传");
		}
		String id = "u_" + entry.playerId().toString().replace("-", "").substring(0, 12);
		String cleanTitle = sanitizeTitle(title, entry.playerName());
		SongLibrary.ImportResult result = library.importBytes(
			id,
			data,
			cleanTitle,
			entry.playerName(),
			1,
			entry.playerId().toString(),
			entry.playerName(),
			config
		);
		if (!result.ok()) {
			return new UploadResult(false, result.message());
		}
		plugin.refreshPack(null);
		pending.remove(token.toUpperCase());
		Player player = Bukkit.getPlayer(entry.playerId());
		if (player != null && player.isOnline()) {
			player.sendMessage(MusicText.component("&a你的唱片已上传成功：&f"
				+ result.song().displayName() + " &7(" + result.song().durationText() + ")"));
			player.sendMessage(MusicText.component("&7用 &f/music get &7领取唱片，"
				+ "&f/music play " + id + " &7试听。"));
		}
		return new UploadResult(true, "上传成功：" + result.song().displayName()
			+ "（" + result.song().durationText() + "）");
	}

	private String sanitizeTitle(String title, String fallback) {
		if (title == null || title.isBlank()) {
			return fallback + " 的唱片";
		}
		String cleaned = title.replaceAll("[\\p{Cntrl}]", "").trim();
		if (cleaned.isEmpty()) {
			return fallback + " 的唱片";
		}
		return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
	}

	private void prune() {
		long now = System.currentTimeMillis();
		pending.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
	}

	private String randomToken() {
		StringBuilder builder = new StringBuilder(6);
		for (int index = 0; index < 6; index++) {
			builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return builder.toString();
	}
}
