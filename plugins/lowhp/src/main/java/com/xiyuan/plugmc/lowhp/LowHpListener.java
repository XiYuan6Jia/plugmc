package com.xiyuan.plugmc.lowhp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 进服下发提示音资源包、下线/死亡时停止播放。
 */
public final class LowHpListener implements Listener {
	private final LowHpPlugin plugin;

	public LowHpListener(LowHpPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		int delay = plugin.settings().joinDelayTicks();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (player.isOnline() && plugin.packServer().running()) {
				plugin.packServer().send(player, plugin.settings());
			}
		}, delay);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		plugin.alerts().stop(event.getPlayer());
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		plugin.alerts().stop(event.getEntity());
	}
}
