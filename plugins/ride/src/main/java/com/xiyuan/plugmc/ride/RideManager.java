package com.xiyuan.plugmc.ride;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 骑乘关系的维护：谁骑着谁、冷却、以及各种情况下的自动下来。
 *
 * <p>骑乘通过 {@code carrier.addPassenger(rider)} 实现，位置由服务端原生跟随，
 * 插件只负责关系记录与解除，避免手动传送造成的抖动。</p>
 */
public final class RideManager {
	private final RidePlugin plugin;
	/** rider -> carrier */
	private final Map<UUID, UUID> riding = new HashMap<>();
	/** rider -> 允许下次骑乘的时间戳 */
	private final Map<UUID, Long> cooldowns = new HashMap<>();

	public RideManager(RidePlugin plugin) {
		this.plugin = plugin;
	}

	public boolean isRiding(UUID rider) {
		return riding.containsKey(rider);
	}

	public UUID carrierOf(UUID rider) {
		return riding.get(rider);
	}

	public int riderCount() {
		return riding.size();
	}

	public long cooldownRemaining(UUID rider) {
		Long until = cooldowns.get(rider);
		if (until == null) {
			return 0L;
		}
		long remaining = until - System.currentTimeMillis();
		if (remaining <= 0) {
			cooldowns.remove(rider);
			return 0L;
		}
		return (remaining + 999L) / 1000L;
	}

	/**
	 * 让 rider 骑到 carrier 身上，返回是否成功（失败时会向玩家说明原因）。
	 */
	public boolean mount(Player rider, Player carrier) {
		RideConfig config = plugin.settings();
		if (!config.enabled()) {
			rider.sendMessage(RideText.component("&c骑乘功能暂时关闭。"));
			return false;
		}
		if (rider.getUniqueId().equals(carrier.getUniqueId())) {
			rider.sendMessage(RideText.component("&c不能骑自己。"));
			return false;
		}
		if (!rider.getWorld().equals(carrier.getWorld())) {
			rider.sendMessage(RideText.component("&c你们不在同一个世界，无法骑乘。"));
			return false;
		}
		if (carrier.hasPermission("ride.immune") && !rider.hasPermission("ride.admin")) {
			rider.sendMessage(RideText.component("&c" + carrier.getName() + " 禁止被骑乘。"));
			return false;
		}
		if (carrier.isInsideVehicle() || carrier.isSleeping() || carrier.isDead()) {
			rider.sendMessage(RideText.component("&c" + carrier.getName() + " 现在不能载人。"));
			return false;
		}
		if (isRiding(rider.getUniqueId())) {
			rider.sendMessage(RideText.component("&e你已经在骑乘中，先用 &f/ride off &e下来。"));
			return false;
		}
		if (formsLoop(rider, carrier)) {
			rider.sendMessage(RideText.component("&c不能形成骑乘循环。"));
			return false;
		}
		long cooldown = cooldownRemaining(rider.getUniqueId());
		if (cooldown > 0) {
			rider.sendMessage(RideText.component("&e冷却中，还需 &f" + cooldown + " &e秒。"));
			return false;
		}
		// 骑手自己坐在载具里时先下车，否则 addPassenger 会被拒绝
		if (rider.isInsideVehicle()) {
			rider.leaveVehicle();
		}
		if (!carrier.addPassenger(rider)) {
			rider.sendMessage(RideText.component("&c骑乘失败，请稍后再试。"));
			return false;
		}
		riding.put(rider.getUniqueId(), carrier.getUniqueId());
		cooldowns.put(rider.getUniqueId(), System.currentTimeMillis() + config.cooldownSeconds() * 1000L);
		rider.sendMessage(RideText.component("&a你骑到了 &f" + carrier.getName()
			+ " &a身上，潜行或 &f/ride off &a下来。"));
		carrier.sendMessage(RideText.component("&e" + rider.getName() + " &7骑到了你身上。"));
		return true;
	}

	/**
	 * carrier 身上是否已有 rider 的上级，形成环时拒绝。
	 */
	private boolean formsLoop(Player rider, Player carrier) {
		Set<UUID> visited = new HashSet<>();
		UUID current = carrier.getUniqueId();
		while (current != null) {
			if (!visited.add(current)) {
				return true;
			}
			if (current.equals(rider.getUniqueId())) {
				return true;
			}
			current = riding.get(current);
		}
		return false;
	}

	public boolean dismount(Player rider, String reason) {
		UUID carrierId = riding.remove(rider.getUniqueId());
		if (carrierId == null) {
			return false;
		}
		Player carrier = Bukkit.getPlayer(carrierId);
		if (carrier != null) {
			carrier.removePassenger(rider);
			if (reason != null && !reason.isBlank()) {
				carrier.sendMessage(RideText.component("&7" + rider.getName() + " 从你身上下来了。"));
			}
		} else if (rider.getVehicle() != null) {
			rider.leaveVehicle();
		}
		if (reason != null && !reason.isBlank()) {
			rider.sendMessage(RideText.component(reason));
		}
		return true;
	}

	/**
	 * 让坐骑身上的所有玩家下来（玩家死亡、下线、传送时用）。
	 */
	public void dismountAll(Player carrier, String reason) {
		for (UUID riderId : Set.copyOf(riding.keySet())) {
			if (!carrier.getUniqueId().equals(riding.get(riderId))) {
				continue;
			}
			Player rider = Bukkit.getPlayer(riderId);
			if (rider != null) {
				dismount(rider, reason);
			} else {
				riding.remove(riderId);
			}
		}
	}

	/**
	 * 玩家被动离开载具（潜行、被插件强制下车等），清理记录。
	 */
	public void handleExternalDismount(Player rider) {
		if (riding.remove(rider.getUniqueId()) != null) {
			rider.sendMessage(RideText.component("&7你结束了骑乘。"));
		}
	}

	public void forget(UUID playerId) {
		riding.values().removeIf(carrier -> carrier.equals(playerId));
		riding.remove(playerId);
		cooldowns.remove(playerId);
	}

	public Map<UUID, UUID> snapshot() {
		return Map.copyOf(riding);
	}
}
