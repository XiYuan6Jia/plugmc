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
		// 右键唱机时交给原版处理（数据包已注册 jukebox_song 的情况下）
		if (event.getClickedBlock() != null
			&& event.getClickedBlock().getType() == Material.JUKEBOX) {
			return;
		}
		event.setCancelled(true);
		if (player.isSneaking()) {
			plugin.playback().stop(player);
			return;
		}
		Optional<Song> song = plugin.library().find(id);
		if (song.isEmpty()) {
			player.sendMessage(MusicText.component("&c这张唱片已经不在唱片库里了。"));
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
