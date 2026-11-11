package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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
		// 右键唱机：数据包已注册时交给原版插碟/取碟；
		// 未注册时退化成在唱机位置直接播放，避免右键毫无反应。
		if (event.getClickedBlock() != null
			&& event.getClickedBlock().getType() == Material.JUKEBOX) {
			if (plugin.discs().hasJukeboxSong(song.get(), plugin.settings())) {
				return;
			}
			event.setCancelled(true);
			plugin.playback().playAt(
				player,
				song.get(),
				event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5)
			);
			return;
		}
		event.setCancelled(true);
		if (player.isSneaking()) {
			plugin.playback().stop(player);
			return;
		}
		plugin.playback().play(player, song.get());
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
}
