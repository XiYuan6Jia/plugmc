package com.xiyuan.plugmc.megastructures;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** 巨构维度保护：禁建造、禁 PvP、禁刷怪、掉虚空拉回、死亡原地重生、飞行/夜视状态维护。 */
public final class MegaListener implements Listener {
	private final MegaPlugin plugin;

	public MegaListener(MegaPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent event) {
		MegaConfig config = plugin.settings();
		if (!config.denyBuild() || !plugin.megaWorld().isMega(event.getBlock().getLocation())) {
			return;
		}
		if (event.getPlayer().hasPermission("mega.build")) {
			return;
		}
		event.setCancelled(true);
		event.getPlayer().sendMessage(MegaText.component("&c巨构维度里不允许建造。"));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		MegaConfig config = plugin.settings();
		if (!config.denyBuild() || !plugin.megaWorld().isMega(event.getBlock().getLocation())) {
			return;
		}
		if (event.getPlayer().hasPermission("mega.build")) {
			return;
		}
		event.setCancelled(true);
		event.getPlayer().sendMessage(MegaText.component("&c巨构维度里不允许破坏方块。"));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPvp(EntityDamageByEntityEvent event) {
		MegaConfig config = plugin.settings();
		if (!config.denyPvp() || !plugin.megaWorld().isMega(event.getEntity().getLocation())) {
			return;
		}
		if (event.getEntity() instanceof Player && event.getDamager() instanceof Player) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onSpawn(CreatureSpawnEvent event) {
		MegaConfig config = plugin.settings();
		if (!config.denyMobSpawn() || !plugin.megaWorld().isMega(event.getLocation())) {
			return;
		}
		event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFallDamage(EntityDamageEvent event) {
		MegaConfig config = plugin.settings();
		if (!config.noFallDamage() || !(event.getEntity() instanceof Player)) {
			return;
		}
		if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
			return;
		}
		if (plugin.megaWorld().isMega(event.getEntity().getLocation())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event) {
		MegaConfig config = plugin.settings();
		if (!config.rescueVoid() || !plugin.megaWorld().isMega(event.getTo())) {
			return;
		}
		if (event.getTo().getY() >= config.voidY()) {
			return;
		}
		event.setTo(plugin.megaWorld().spawn(config));
		event.getPlayer().sendMessage(MegaText.component("&7你掉出了巨构，已送回出生点。"));
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		MegaConfig config = plugin.settings();
		if (!config.respawnInMega() || !plugin.megaWorld().ready()) {
			return;
		}
		if (plugin.megaWorld().isMega(event.getPlayer().getLocation())) {
			event.setRespawnLocation(plugin.megaWorld().spawn(config));
		}
		Player player = event.getPlayer();
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			if (player.isOnline()) {
				plugin.comfort().sync(player);
			}
		});
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		if (plugin.megaWorld().isMega(event.getTo()) && !plugin.megaWorld().isMega(event.getFrom())) {
			plugin.manager().rememberOnEnter(event.getPlayer(), event.getFrom());
		}
	}

	@EventHandler
	public void onChangedWorld(PlayerChangedWorldEvent event) {
		Player player = event.getPlayer();
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			if (player.isOnline()) {
				plugin.comfort().sync(player);
			}
		});
	}

	@EventHandler
	public void onGameModeChange(PlayerGameModeChangeEvent event) {
		Player player = event.getPlayer();
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			if (player.isOnline()) {
				plugin.comfort().sync(player);
			}
		});
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		// 退出前撤销我们授予的飞行/夜视，避免能力被写进玩家数据
		plugin.comfort().release(event.getPlayer());
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			if (!player.isOnline()) {
				return;
			}
			if (plugin.megaWorld().isMega(player.getLocation())) {
				plugin.comfort().sync(player);
			} else if (plugin.settings().joinAuto()) {
				plugin.manager().enter(player, "ring");
			}
		}, 2L);
	}
}
