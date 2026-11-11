package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * 唱片物品的右键播放、进服自动下发资源包。
 */
public final class MusicListener implements Listener {
	private final MusicPlugin plugin;

	public MusicListener(MusicPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		if (event.getAction() != Action.RIGHT_CLICK_AIR
			&& event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		ItemStack item = event.getItem();
		String id = plugin.discs().songId(item);
		if (id == null) {
			return;
		}
		Player player = event.getPlayer();
		Optional<Song> song = plugin.library().find(id);
		if (song.isEmpty()) {
			return;
		}
		// 旧版唱片（没有 jukebox_playable 组件 / 还在用原版唱片材质）自动升级成新物品
		if (!plugin.discs().isUpToDate(item, song.get(), plugin.settings())) {
			event.setCancelled(true);
			player.getInventory().setItemInMainHand(
				plugin.discs().create(song.get(), plugin.settings())
			);
			player.sendMessage(MusicText.component("&a这张唱片已升级为新版，可以放进唱机了。"));
			return;
		}
		// 右键唱机：新版唱片（带 jukebox_playable）交给原版插碟/取碟；
		// 其余情况用插件版唱机交互，保证新上传的曲目立刻可用。
		if (event.getClickedBlock() != null
			&& event.getClickedBlock().getType() == Material.JUKEBOX) {
			Block block = event.getClickedBlock();
			ItemStack inside = block.getState() instanceof org.bukkit.block.Jukebox jukebox
				? jukebox.getRecord()
				: null;
			boolean managed = plugin.jukeboxes().playing(block)
				|| (inside != null && plugin.jukeboxes().isOurDisc(inside));
			event.setCancelled(true);
			if (managed) {
				plugin.jukeboxes().eject(player, block);
				return;
			}
			if (inside != null && !inside.getType().isAir()) {
				// 里面是原版唱片，交回原版逻辑（原版会弹出唱片）
				event.setCancelled(false);
				return;
			}
			if (plugin.discs().hasJukeboxSong(song.get(), plugin.settings())) {
				// 数据包已加载：走原版插碟
				event.setCancelled(false);
				return;
			}
			plugin.jukeboxes().insert(player, block, song.get());
			return;
		}
		event.setCancelled(true);
		if (player.isSneaking()) {
			plugin.playback().stop(player);
			return;
		}
		// 手持唱片直接播放给附近玩家
		plugin.playback().playNearby(player, song.get(), player.getLocation());
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		MusicConfig config = plugin.settings();
		if (!config.enabled()) {
			return;
		}
		Player player = event.getPlayer();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (player.isOnline() && plugin.packServer().running()) {
				plugin.packServer().send(player, plugin.settings());
			}
		}, plugin.settings().joinDelayTicks());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		if (!plugin.settings().stopOnQuit()) {
			return;
		}
		plugin.playback().stop(event.getPlayer());
	}

	/**
	 * 唱机被破坏时停止插件版播放（唱片本身由原版掉落）。
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
		if (event.getBlock().getType() == Material.JUKEBOX) {
			plugin.jukeboxes().stop(event.getBlock());
		}
	}
}
