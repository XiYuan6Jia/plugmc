package com.xiyuan.plugmc.hub;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 主城内的生命与饱食度锁定。
 *
 * <p>进入主城时记录当时的血量/饱食度/饱和度，之后每半秒校正一次，避免以下情况把状态补满：</p>
 * <ul>
 *   <li>主城维度难度为和平时的自动回满饱食度与回血；</li>
 *   <li>饱食度自然消耗或自然回血；</li>
 *   <li>其它插件在主城内改写血量。</li>
 * </ul>
 * <p>吃东西仍然生效，吃到的饱食度会成为新的锁定值；离开主城后不再干预。</p>
 */
public final class HubVitals {
	private static final long INTERVAL_TICKS = 10L;

	private final HubPlugin plugin;
	private final Map<UUID, Snapshot> snapshots = new HashMap<>();
	private BukkitTask task;

	public HubVitals(HubPlugin plugin) {
		this.plugin = plugin;
	}

	private record Snapshot(double health, int food, float saturation) {
	}

	public void start() {
		stop();
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, INTERVAL_TICKS, INTERVAL_TICKS);
	}

	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
		snapshots.clear();
	}

	public boolean locked(Player player) {
		return snapshots.containsKey(player.getUniqueId());
	}

	/**
	 * 进入主城时记录当前状态作为锁定值。
	 */
	public void capture(Player player) {
		HubConfig config = plugin.settings();
		if (!config.lockHealth() && !config.lockFood()) {
			snapshots.remove(player.getUniqueId());
			return;
		}
		snapshots.put(player.getUniqueId(), snapshotOf(player));
	}

	/**
	 * 吃东西后把新的饱食度作为锁定值，避免下一轮校正把食物吐回去。
	 */
	public void updateFood(Player player) {
		Snapshot snapshot = snapshots.get(player.getUniqueId());
		if (snapshot == null) {
			return;
		}
		snapshots.put(
			player.getUniqueId(),
			new Snapshot(snapshot.health(), player.getFoodLevel(), player.getSaturation())
		);
	}

	public void release(UUID id) {
		snapshots.remove(id);
	}

	private Snapshot snapshotOf(Player player) {
		return new Snapshot(player.getHealth(), player.getFoodLevel(), player.getSaturation());
	}

	private void tick() {
		if (snapshots.isEmpty()) {
			return;
		}
		HubConfig config = plugin.settings();
		Iterator<Map.Entry<UUID, Snapshot>> iterator = snapshots.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Snapshot> entry = iterator.next();
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null || !player.isOnline()) {
				iterator.remove();
				continue;
			}
			if (!plugin.hubWorld().isHub(player.getLocation())) {
				iterator.remove();
				continue;
			}
			if (player.isDead() || player.getHealth() <= 0) {
				continue;
			}
			Snapshot snapshot = entry.getValue();
			if (config.lockHealth()) {
				double maxHealth = maxHealth(player);
				double target = Math.max(1.0, Math.min(snapshot.health(), maxHealth));
				if (Math.abs(player.getHealth() - target) > 0.01) {
					player.setHealth(target);
				}
			}
			if (config.lockFood()) {
				boolean changed = player.getFoodLevel() != snapshot.food()
					|| Math.abs(player.getSaturation() - snapshot.saturation()) > 0.01f;
				if (changed) {
					player.setFoodLevel(snapshot.food());
					player.setSaturation(snapshot.saturation());
				}
			}
		}
	}

	private double maxHealth(Player player) {
		AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
		return attribute == null ? 20.0 : attribute.getValue();
	}
}
