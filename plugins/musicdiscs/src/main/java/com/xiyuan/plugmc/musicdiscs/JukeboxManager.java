package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

/**
 * 插件版唱片机交互。
 *
 * <p>原版唱机只认 {@code jukebox_playable} 组件，而这个组件必须引用启动时就存在的
 * {@code jukebox_song} 注册表，所以新上传的曲目在重启前无法用原版逻辑插碟。
 * 这里补上一层：把唱片放进唱机方块、由插件播放与停止，做到上传后立刻可用。</p>
 *
 * <p>重启后数据包生效时，唱片会自动带上组件并交回原版逻辑处理，两套路径互不冲突。</p>
 */
public final class JukeboxManager {
	private final MusicPlugin plugin;
	private final Map<String, Session> sessions = new HashMap<>();

	private record Session(String songId, Location location, BukkitTask task) {
	}

	public JukeboxManager(MusicPlugin plugin) {
		this.plugin = plugin;
	}

	public static String key(Location location) {
		return location.getWorld().getName() + ":" + location.getBlockX() + ":"
			+ location.getBlockY() + ":" + location.getBlockZ();
	}

	/**
	 * 是否是本插件放进去的唱片（带 musicdiscs:song 标记）。
	 */
	public boolean isOurDisc(ItemStack stack) {
		return plugin.discs().songId(stack) != null;
	}

	public boolean playing(Block block) {
		return sessions.containsKey(key(block.getLocation()));
	}

	/**
	 * 把唱片放进唱机并开始播放。
	 */
	public boolean insert(Player player, Block block, Song song) {
		if (plugin.library().audioFile(song.id()).isEmpty()) {
			player.sendMessage(MusicText.component("&c这首唱片缺少音频文件，请让管理员重新导入。"));
			return false;
		}
		ItemStack record = block.getState() instanceof Jukebox jukebox ? jukebox.getRecord() : null;
		if (record != null && !record.getType().isAir()) {
			player.sendMessage(MusicText.component("&e这个唱机里已经有唱片了，先右键取出来。"));
			return false;
		}
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir() || hand.getAmount() <= 0) {
			player.sendMessage(MusicText.component("&c请把唱片拿在主手。"));
			return false;
		}
		ItemStack disc = hand.clone();
		disc.setAmount(1);
		if (hand.getAmount() <= 1) {
			player.getInventory().setItemInMainHand(null);
		} else {
			hand.setAmount(hand.getAmount() - 1);
			player.getInventory().setItemInMainHand(hand);
		}
		if (!putRecord(block, disc)) {
			// 方块状态写不进去就把唱片还给玩家，避免吞物品
			giveBack(player, disc, block.getLocation());
			player.sendMessage(MusicText.component("&c唱机无法放入唱片，已退回。"));
			return false;
		}
		start(block, song);
		player.sendMessage(MusicText.component("&a已把 &f" + song.title()
			+ " &a放进唱机，附近玩家都能听到；右键唱机可以取出。"));
		return true;
	}

	/**
	 * 从唱机取出唱片并停止播放，优先放回玩家背包。
	 */
	public boolean eject(Player player, Block block) {
		ItemStack record = block.getState() instanceof Jukebox jukebox ? jukebox.getRecord() : null;
		if (record == null || record.getType().isAir()) {
			return false;
		}
		stop(block);
		putRecord(block, null);
		if (player != null && player.isOnline()) {
			giveBack(player, record, block.getLocation());
			player.sendMessage(MusicText.component("&7已取出唱片。"));
		} else {
			block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), record);
		}
		return true;
	}

	private void giveBack(Player player, ItemStack item, Location fallback) {
		var leftover = player.getInventory().addItem(item.clone());
		if (!leftover.isEmpty()) {
			for (ItemStack stack : leftover.values()) {
				fallback.getWorld().dropItemNaturally(fallback.clone().add(0.5, 1.0, 0.5), stack);
			}
		}
	}

	private boolean putRecord(Block block, ItemStack record) {
		if (!(block.getState() instanceof Jukebox jukebox)) {
			return false;
		}
		boolean present = record != null && !record.getType().isAir();
		try {
			jukebox.setRecord(record);
			jukebox.update(true, false);
			// 容器内容不会自动同步方块状态，这里显式同步 has_record，保证外观正确
			if (block.getBlockData() instanceof org.bukkit.block.data.type.Jukebox data) {
				data.setHasRecord(present);
				block.setBlockData(data, false);
			}
			return true;
		} catch (RuntimeException exception) {
			plugin.getLogger().warning("写入唱机失败：" + exception.getMessage());
			return false;
		}
	}

	private void start(Block block, Song song) {
		Location center = block.getLocation().add(0.5, 0.5, 0.5);
		stop(block);
		String sound = song.soundKey(plugin.settings().namespace());
		float volume = (float) Math.max(1.0, plugin.settings().jukeboxVolume());
		center.getWorld().playSound(center, sound, SoundCategory.RECORDS, volume, 1.0f);
		long ticks = Math.max(20L, (long) ((song.seconds() + 1.5) * 20.0));
		BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
			Session session = sessions.remove(key(block.getLocation()));
			if (session != null) {
				stopSound(session);
				if (block.getState() instanceof Jukebox jukebox) {
					ItemStack record = jukebox.getRecord();
					putRecord(block, null);
					if (record != null && !record.getType().isAir()) {
						block.getWorld().dropItemNaturally(
							block.getLocation().add(0.5, 1.0, 0.5),
							record
						);
					}
				}
			}
		}, ticks);
		sessions.put(key(block.getLocation()), new Session(song.id(), center, task));
	}

	public void stop(Block block) {
		Session session = sessions.remove(key(block.getLocation()));
		if (session == null) {
			return;
		}
		if (session.task() != null) {
			session.task().cancel();
		}
		stopSound(session);
	}

	public void stopAll() {
		for (Session session : Map.copyOf(sessions).values()) {
			if (session.task() != null) {
				session.task().cancel();
			}
			stopSound(session);
		}
		sessions.clear();
	}

	private void stopSound(Session session) {
		if (session.location() == null || session.location().getWorld() == null
			|| session.songId() == null) {
			return;
		}
		// 按声音键精确停止，避免影响玩家自己的其它播放
		String key = plugin.settings().namespace() + ":disc." + session.songId();
		for (Player nearby : session.location().getWorld().getPlayers()) {
			nearby.stopSound(key, SoundCategory.RECORDS);
		}
	}
}
