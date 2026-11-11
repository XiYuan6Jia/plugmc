package com.xiyuan.plugmc.ride;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;

/**
 * 骑乘相关事件：潜行下车、受伤害/死亡/传送自动下车、互相伤害保护、速度过快甩下。
 */
public final class RideListener implements Listener {
	private final RidePlugin plugin;
	private BukkitTask speedTask;

	public RideListener(RidePlugin plugin) {
		this.plugin = plugin;
	}

	public void start() {
		stop();
		speedTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkSpeed, 10L, 10L);
	}

	public void stop() {
		if (speedTask != null) {
			speedTask.cancel();
			speedTask = null;
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSneak(PlayerToggleSneakEvent event) {
		if (!event.isSneaking() || !plugin.settings().allowSneakDismount()) {
			return;
		}
		Player rider = event.getPlayer();
		if (plugin.rides().isRiding(rider.getUniqueId())) {
			// 潜行本身就会让客户端请求下车，这里只补一句提示
			plugin.rides().dismount(rider, null);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onDismount(EntityDismountEvent event) {
		if (event.getEntity() instanceof Player rider) {
			plugin.getServer().getScheduler().runTask(plugin, () -> {
				if (rider.isOnline() && !rider.isInsideVehicle()) {
					plugin.rides().handleExternalDismount(rider);
				}
			});
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (!(event.getEntity() instanceof Player player)) {
			return;
		}
		RideConfig config = plugin.settings();
		if (config.dismountOnDamage() && plugin.rides().isRiding(player.getUniqueId())) {
			plugin.rides().dismount(player, "&c受到伤害，已结束骑乘。");
		}
		if (config.dismountOnDamage()) {
			plugin.rides().dismountAll(player, "&c坐骑受到伤害，你被甩下来了。");
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onMutualDamage(EntityDamageByEntityEvent event) {
		if (!plugin.settings().denyMutualDamage()) {
			return;
		}
		if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player damager)) {
			return;
		}
		if (plugin.rides().carrierOf(damager.getUniqueId()) == victim.getUniqueId()
			|| plugin.rides().carrierOf(victim.getUniqueId()) == damager.getUniqueId()) {
			event.setCancelled(true);
			damager.sendMessage(RideText.component("&7骑乘中不能互相伤害。"));
		}
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		if (plugin.settings().dismountOnDeath()) {
			plugin.rides().dismountAll(player, null);
			if (plugin.rides().isRiding(player.getUniqueId())) {
				plugin.rides().dismount(player, null);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		RideConfig config = plugin.settings();
		Player player = event.getPlayer();
		if (config.dismountOnTeleport() && plugin.rides().isRiding(player.getUniqueId())) {
			plugin.rides().dismount(player, "&7传送已结束骑乘。");
		}
		if (config.dismountOnWorldChange() && event.getFrom().getWorld() != null
			&& event.getTo().getWorld() != null
			&& !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
			plugin.rides().dismountAll(player, "&7坐骑切换了世界，你被放下来了。");
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		plugin.rides().dismountAll(player, null);
		if (plugin.rides().isRiding(player.getUniqueId())) {
			plugin.rides().dismount(player, null);
		}
		plugin.rides().forget(player.getUniqueId());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onVehicleEnter(VehicleEnterEvent event) {
		if (event.getEntered() instanceof Player player && plugin.rides().isRiding(player.getUniqueId())) {
			plugin.rides().dismount(player, "&7你进入了载具，骑乘已结束。");
		}
	}

	/**
	 * 坐骑位移过快时把骑手甩下来，避免用骑乘穿越地形或卡进方块。
	 */
	private void checkSpeed() {
		double limit = plugin.settings().maxCarrierSpeed();
		if (limit <= 0) {
			return;
		}
		for (Map.Entry<UUID, UUID> entry : plugin.rides().snapshot().entrySet()) {
			Player rider = Bukkit.getPlayer(entry.getKey());
			Player carrier = Bukkit.getPlayer(entry.getValue());
			if (rider == null || carrier == null) {
				continue;
			}
			if (rider.getVehicle() != carrier) {
				plugin.rides().handleExternalDismount(rider);
				continue;
			}
			double speed = carrier.getVelocity().length() * 20.0;
			if (speed > limit) {
				plugin.rides().dismount(rider, "&e坐骑移动太快，你被甩下来了。");
			}
		}
	}
}
