package com.xiyuan.plugmc.hub;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** 主城保护：禁建造、禁 PvP、禁刷怪、掉虚空拉回、主城死亡原地重生。 */
public final class HubListener implements Listener {
	private final HubPlugin plugin;

	public HubListener(HubPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent event) {
		HubConfig config = plugin.settings();
		if (!config.denyBuild() || !plugin.hubWorld().isHub(event.getBlock().getLocation())) {
			return;
		}
		if (event.getPlayer().hasPermission("hub.build")) {
			return;
		}
		event.setCancelled(true);
		event.getPlayer().sendMessage(HubText.component("&c主城大厅不允许建造。"));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		HubConfig config = plugin.settings();
		if (!config.denyBuild() || !plugin.hubWorld().isHub(event.getBlock().getLocation())) {
			return;
		}
		if (event.getPlayer().hasPermission("hub.build")) {
			return;
		}
		event.setCancelled(true);
		event.getPlayer().sendMessage(HubText.component("&c主城大厅不允许破坏方块。"));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPvp(EntityDamageByEntityEvent event) {
		HubConfig config = plugin.settings();
		if (!config.denyPvp() || !plugin.hubWorld().isHub(event.getEntity().getLocation())) {
			return;
		}
		if (!(event.getEntity() instanceof Player)) {
			return;
		}
		if (event.getDamager() instanceof Player) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onSpawn(CreatureSpawnEvent event) {
		HubConfig config = plugin.settings();
		if (!config.denyMobSpawn() || !plugin.hubWorld().isHub(event.getLocation())) {
			return;
		}
		event.setCancelled(true);
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		HubConfig config = plugin.settings();
		if (!config.respawnInHub() || !plugin.hubWorld().ready()) {
			return;
		}
		// 死亡时玩家还在主城里，直接在主城出生点重生
		if (!plugin.hubWorld().isHub(event.getPlayer().getLocation())) {
			return;
		}
		event.setRespawnLocation(plugin.hubWorld().spawn(config));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event) {
		HubConfig config = plugin.settings();
		if (!config.rescueVoid() || !plugin.hubWorld().isHub(event.getTo())) {
			return;
		}
		if (event.getTo().getY() >= 0) {
			return;
		}
		event.setTo(plugin.hubWorld().spawn(config));
		event.getPlayer().sendMessage(HubText.component("&7你掉出了主城，已送回大厅。"));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFallDamage(EntityDamageEvent event) {
		HubConfig config = plugin.settings();
		if (!config.noFallDamage() || !(event.getEntity() instanceof Player)) {
			return;
		}
		if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
			return;
		}
		if (!plugin.hubWorld().isHub(event.getEntity().getLocation())) {
			return;
		}
		event.setCancelled(true);
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		if (!plugin.settings().joinAuto()) {
			return;
		}
		Player player = event.getPlayer();
		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			if (player.isOnline()) {
				plugin.hubManager().enter(player);
			}
		}, 1L);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		plugin.hubManager().forget(event.getPlayer().getUniqueId());
	}
}
