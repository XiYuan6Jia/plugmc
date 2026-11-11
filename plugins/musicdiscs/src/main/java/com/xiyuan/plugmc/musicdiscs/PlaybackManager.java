package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 插件侧播放。
 *
 * <ul>
 *   <li>{@link #play} —— 试听：只有该玩家自己能听到；</li>
 *   <li>{@link #playNearby} —— 手持唱片播放：附近玩家按距离衰减都能听到。</li>
 * </ul>
 */
public final class PlaybackManager {
	private final JavaPlugin plugin;
	private final SongLibrary library;
	private final MusicConfig config;
	private final Map<UUID, Playing> playing = new HashMap<>();

	private record Playing(
		String sound,
		Location origin,
		double range,
		BukkitTask task,
		boolean personal
	) {
	}

	public PlaybackManager(JavaPlugin plugin, SongLibrary library, MusicConfig config) {
		this.plugin = plugin;
		this.library = library;
		this.config = config;
	}

	public boolean isPlaying(Player player) {
		return playing.containsKey(player.getUniqueId());
	}

	public String currentSong(Player player) {
		Playing current = playing.get(player.getUniqueId());
		return current == null ? null : current.sound();
	}

	/**
	 * 试听：只有执行命令的玩家能听到。
	 */
	public void play(Player player, Song song) {
		stop(player);
		if (!prepare(player, song)) {
			return;
		}
		String sound = song.soundKey(config.namespace());
		player.playSound(player.getLocation(), sound, SoundCategory.RECORDS, 1.0f, 1.0f);
		schedule(player, song, sound, player.getLocation().clone(), true);
		player.sendMessage(MusicText.component("&a正在试听：&f" + song.displayName()
			+ " &7(" + song.durationText() + ")，只有你能听到，&f/music stop &7停止。"));
	}

	/**
	 * 手持唱片播放：附近玩家都能听到。
	 */
	public void playNearby(Player player, Song song, Location origin) {
		stop(player);
		if (!prepare(player, song)) {
			return;
		}
		Location center = origin.clone();
		String sound = song.soundKey(config.namespace());
		float volume = (float) Math.min(4.0, config.volume() * (config.playbackRange() / 16.0));
		if (center.getWorld() != null) {
			center.getWorld().playSound(center, sound, SoundCategory.RECORDS, volume, 1.0f);
		}
		schedule(player, song, sound, center, false);
		player.sendMessage(MusicText.component("&a正在播放：&f" + song.displayName()
			+ " &7(" + song.durationText() + ")，附近玩家都能听到，&f/music stop &7停止。"));
	}

	private boolean prepare(Player player, Song song) {
		if (library.audioFile(song.id()).isEmpty()) {
			player.sendMessage(MusicText.component("&c这首唱片缺少音频文件，请让管理员重新导入。"));
			return false;
		}
		return true;
	}

	private void schedule(
		Player player,
		Song song,
		String sound,
		Location center,
		boolean personal
	) {
		long ticks = Math.max(20L, (long) ((song.seconds() + 1.5) * 20.0));
		BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
			Playing current = playing.remove(player.getUniqueId());
			if (current != null) {
				stopSound(player, current);
			}
		}, ticks);
		playing.put(
			player.getUniqueId(),
			new Playing(sound, center, config.playbackRange(), task, personal)
		);
	}

	public void stop(Player player) {
		Playing current = playing.remove(player.getUniqueId());
		if (current == null) {
			return;
		}
		if (current.task() != null) {
			current.task().cancel();
		}
		stopSound(player, current);
	}

	public void stopAll() {
		for (UUID id : Map.copyOf(playing).keySet()) {
			Player player = Bukkit.getPlayer(id);
			Playing current = playing.remove(id);
			if (current == null) {
				continue;
			}
			if (current.task() != null) {
				current.task().cancel();
			}
			stopSound(player, current);
			if (player != null) {
				player.sendMessage(MusicText.component("&7播放已停止。"));
			}
		}
	}

	private void stopSound(Player owner, Playing current) {
		if (current.personal()) {
			if (owner != null && owner.isOnline()) {
				owner.stopSound(current.sound(), SoundCategory.RECORDS);
			}
			return;
		}
		if (current.origin() == null || current.origin().getWorld() == null) {
			return;
		}
		double rangeSquared = current.range() * current.range();
		for (Player nearby : current.origin().getWorld().getPlayers()) {
			if (nearby.getLocation().distanceSquared(current.origin()) <= rangeSquared) {
				nearby.stopSound(current.sound(), SoundCategory.RECORDS);
			}
		}
	}
}
