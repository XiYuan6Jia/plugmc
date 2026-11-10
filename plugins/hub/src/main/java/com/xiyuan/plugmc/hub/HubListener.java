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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.block.Block;

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
		if (!plugin.hubWorld().isHub(event.getTo())) {
			return;
		}
		Player player = event.getPlayer();
		ParkourManager parkour = plugin.parkour();
		if (parkour != null && parkour.running(player) && event.getTo().getY() < config.floorY() - 8) {
			parkour.onFall(player);
			return;
		}
		if (config.rescueVoid() && event.getTo().getY() < 0) {
			event.setTo(plugin.hubWorld().spawn(config));
			player.sendMessage(HubText.component("&7你掉出了主城，已送回大厅。"));
			return;
		}
		ParkourCourse course = parkour == null ? null : parkour.course();
		if (course == null) {
			return;
		}
		Block from = event.getFrom().getBlock();
		Block to = event.getTo().getBlock();
		if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
			return;
		}
		ParkourCourse.Pad pad = course.padAt(to.getX(), to.getY() - 1, to.getZ());
		if (pad != null) {
			parkour.onPad(player, pad);
		}
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

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		if (!plugin.hubWorld().ready()) {
			return;
		}
		boolean leftHub = plugin.hubWorld().isHub(event.getFrom()) && !plugin.hubWorld().isHub(event.getTo());
		if (leftHub && plugin.parkour() != null) {
			plugin.parkour().onLeaveHub(event.getPlayer());
		}
		if (plugin.hubWorld().isHub(event.getTo()) && !plugin.hubWorld().isHub(event.getFrom())) {
			plugin.hubManager().rememberOnEnter(event.getPlayer(), event.getFrom());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		if (plugin.parkour() != null) {
			plugin.parkour().onQuit(event.getPlayer());
		}
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
}
