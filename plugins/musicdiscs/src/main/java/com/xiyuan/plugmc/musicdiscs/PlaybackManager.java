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
 * 插件侧播放：以唱片机音效分类在玩家附近播放自定义声音，到时或手动停止。
 */
public final class PlaybackManager {
	private final JavaPlugin plugin;
	private final SongLibrary library;
	private final MusicConfig config;
	private final Map<UUID, Playing> playing = new HashMap<>();

	private record Playing(String sound, Location origin, double range, BukkitTask task) {
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
	 * 播放一首歌；附近玩家按距离衰减都能听到。
	 */
	public void play(Player player, Song song) {
		playAt(player, song, player.getLocation());
	}

	/**
	 * 在指定位置播放（右键唱片机时用唱片机的位置）。
	 */
	public void playAt(Player player, Song song, Location origin) {
		stop(player);
		if (library.audioFile(song.id()).isEmpty()) {
			player.sendMessage(MusicText.component("&c这首唱片缺少音频文件，请让管理员重新导入。"));
			return;
		}
		Location center = origin.clone();
		String sound = song.soundKey(config.namespace());
		float volume = (float) Math.min(4.0, config.volume() * (config.playbackRange() / 16.0));
		if (center.getWorld() != null) {
			center.getWorld().playSound(center, sound, SoundCategory.RECORDS, volume, 1.0f);
		}
		long ticks = Math.max(20L, (long) ((song.seconds() + 1.5) * 20.0));
		BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
			Playing current = playing.remove(player.getUniqueId());
			if (current != null) {
				stopNearby(current);
			}
		}, ticks);
		playing.put(
			player.getUniqueId(),
			new Playing(sound, center, config.playbackRange(), task)
		);
		player.sendMessage(MusicText.component("&a正在播放：&f" + song.displayName()
			+ " &7(" + song.durationText() + ")，&f/music stop &7可停止。"));
	}

	public void stop(Player player) {
		Playing current = playing.remove(player.getUniqueId());
		if (current == null) {
			return;
		}
		if (current.task() != null) {
			current.task().cancel();
		}
		stopNearby(current);
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
			stopNearby(current);
			if (player != null) {
				player.sendMessage(MusicText.component("&7播放已停止。"));
			}
		}
	}

	private void stopNearby(Playing current) {
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
