package com.xiyuan.plugmc.sethome;

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
 * 传送读秒：读秒期间移动或受伤会取消，传送成功后进入冷却。
 */
public final class TeleportManager implements Listener {
	private final SetHomePlugin plugin;
	private final Map<UUID, PendingTeleport> pending = new HashMap<>();
	private final Map<UUID, Long> cooldowns = new HashMap<>();

	public TeleportManager(SetHomePlugin plugin) {
		this.plugin = plugin;
	}

	public void request(Player player, Location target, String label) {
		UUID id = player.getUniqueId();
		long now = System.currentTimeMillis();
		long readyAt = cooldowns.getOrDefault(id, 0L);
		if (now < readyAt) {
			long seconds = Math.max(1, (readyAt - now + 999) / 1000);
			player.sendMessage(HomeText.of("&e传送冷却中，还需 &f" + seconds + " &e秒。"));
			return;
		}
		cancel(id, null);
		int warmup = plugin.warmupSeconds();
		if (warmup <= 0) {
			teleport(player, target, label);
			return;
		}
		PendingTeleport taskHolder = new PendingTeleport();
		taskHolder.target = target.clone();
		taskHolder.label = label;
		taskHolder.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (pending.remove(id) == null) {
				return;
			}
			teleport(player, taskHolder.target, taskHolder.label);
		}, warmup * 20L);
		pending.put(id, taskHolder);
		player.sendMessage(HomeText.of("&a将在 &f" + warmup + " &a秒后传送到 &f" + label
			+ "&a，移动或受伤会取消。"));
	}

	private void teleport(Player player, Location target, String label) {
		if (!player.isOnline()) {
			return;
		}
		if (player.teleport(target)) {
			cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + plugin.cooldownSeconds() * 1000L);
			player.sendMessage(HomeText.of("&a已传送到 &f" + label + "&a。"));
			return;
		}
		player.sendMessage(HomeText.of("&c传送失败，请稍后再试。"));
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
		UUID id = event.getPlayer().getUniqueId();
		cancel(id, null);
		cooldowns.remove(id);
	}

	private void cancel(UUID id, String message) {
		PendingTeleport teleport = pending.remove(id);
		if (teleport == null) {
			return;
		}
		if (teleport.task != null) {
			teleport.task.cancel();
		}
		if (message != null) {
			Player player = Bukkit.getPlayer(id);
			if (player != null) {
				player.sendMessage(HomeText.of(message));
			}
		}
	}

	public boolean pending(UUID id) {
		return pending.containsKey(id);
	}

	public long cooldownRemainingMillis(UUID id) {
		return Math.max(0L, cooldowns.getOrDefault(id, 0L) - System.currentTimeMillis());
	}

	private static final class PendingTeleport {
		private Location target;
		private String label;
		private BukkitTask task;
	}
}
