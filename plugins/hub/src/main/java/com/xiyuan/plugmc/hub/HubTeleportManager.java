package com.xiyuan.plugmc.hub;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /hub 进入主城的读秒：读秒期间移动或受伤会取消。
 */
public final class HubTeleportManager implements Listener {
	private final HubPlugin plugin;
	private final Map<UUID, PendingTeleport> pending = new HashMap<>();

	public HubTeleportManager(HubPlugin plugin) {
		this.plugin = plugin;
	}

	public boolean request(Player player) {
		HubConfig config = plugin.settings();
		if (!plugin.hubWorld().ready()) {
			player.sendMessage(HubText.component("&c主城维度尚未加载，请联系管理员。"));
			return false;
		}
		if (plugin.hubWorld().isHub(player.getLocation())) {
			// 已经在主城：直接回出生点，不再读秒
			player.teleport(plugin.hubWorld().spawn(config));
			return true;
		}
		int warmup = config.teleportWarmupSeconds();
		if (warmup <= 0) {
			return plugin.hubManager().performEnter(player);
		}
		cancel(player.getUniqueId(), null);
		PendingTeleport pendingTeleport = new PendingTeleport();
		pendingTeleport.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (pending.remove(player.getUniqueId()) == null) {
				return;
			}
			plugin.hubManager().performEnter(player);
		}, warmup * 20L);
		pending.put(player.getUniqueId(), pendingTeleport);
		player.sendMessage(HubText.component("&a将在 &f" + warmup
			+ " &a秒后传送到主城大厅，移动或受伤会取消。"));
		return true;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event) {
		if (!pending.containsKey(event.getPlayer().getUniqueId())) {
			return;
		}
		Location from = event.getFrom();
		Location to = event.getTo();
		if (from.getBlockX() == to.getBlockX()
			&& from.getBlockY() == to.getBlockY()
			&& from.getBlockZ() == to.getBlockZ()) {
			return;
		}
		cancel(event.getPlayer().getUniqueId(), "&c移动已取消传送。");
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player player) {
			cancel(player.getUniqueId(), "&c受到伤害，传送已取消。");
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		cancel(event.getPlayer().getUniqueId(), null);
	}

	private void cancel(UUID id, String message) {
		PendingTeleport pendingTeleport = pending.remove(id);
		if (pendingTeleport == null) {
			return;
		}
		if (pendingTeleport.task != null) {
			pendingTeleport.task.cancel();
		}
		if (message != null) {
			Player player = Bukkit.getPlayer(id);
			if (player != null) {
				player.sendMessage(HubText.component(message));
			}
		}
	}

	private static final class PendingTeleport {
		private BukkitTask task;
	}
}
