package com.xiyuan.plugmc.cosmetic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 装饰实体管理器：每个（玩家 × 槽位）一个 {@link ItemDisplay}。
 *
 * <p><b>为什么不挂乘客</b>：{@code Entity#canAddPassenger} 的实现是
 * {@code return this.getPassengers().isEmpty();}，一个玩家只能有 1 个直接乘客，
 * 而 ride 插件已经占用了这个位置（ride 会把玩家挂到玩家身上）。
 * 因此这里全部走「服务端算坐标 + teleport + setTeleportDuration」，
 * 让客户端自己插值，视觉上依然是贴身的。</p>
 *
 * <p>所有装饰实体都带 {@link #TAG} 记分板标签，便于启动时清扫上一轮遗留的孤儿实体。</p>
 */
final class CosmeticDisplays {
	/** 用来识别本插件生成的装饰实体，也是孤儿实体清扫的判据。 */
	static final String TAG = "plugmc_cosmetic";

	private final JavaPlugin plugin;
	private final CosmeticConfig config;
	private final CosmeticWardrobe wardrobe;

	/** playerId -> (slotId -> 实体) */
	private final Map<UUID, Map<String, ItemDisplay>> active = new HashMap<>();
	/** 被 /cosmetic toggle 临时隐藏的玩家：数据还在，只是不渲染 */
	private final java.util.Set<UUID> hidden = new java.util.HashSet<>();

	CosmeticDisplays(JavaPlugin plugin, CosmeticConfig config, CosmeticWardrobe wardrobe) {
		this.plugin = plugin;
		this.config = config;
		this.wardrobe = wardrobe;
	}

	/**
	 * 清扫上一次运行遗留的装饰实体（崩溃、强杀、/reload 都会留下）。
	 * 只在启动时调用一次。
	 */
	void sweepOrphans() {
		int removed = 0;
		for (World world : Bukkit.getWorlds()) {
			for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
				if (display.getScoreboardTags().contains(TAG)) {
					display.remove();
					removed++;
				}
			}
		}
		if (removed > 0) {
			plugin.getLogger().info("已清扫 " + removed + " 个上次遗留的装饰实体");
		}
	}

	/**
	 * 同步一名玩家的全部装饰实体。每 tick（或按配置降频）调用。
	 *
	 * @param tick 自插件启动以来的 tick 序号，用于自转与环绕的相位
	 */
	void sync(Player player, long tick) {
		if (!player.isOnline() || player.isDead() || !player.isValid()) {
			removeAll(player.getUniqueId(), false);
			return;
		}
		UUID id = player.getUniqueId();
		if (hidden.contains(id)) {
			return;
		}
		List<String> slotIds = wardrobe.slotIds(id);
		if (slotIds.isEmpty()) {
			removeAll(id, false);
			return;
		}
		Map<String, ItemDisplay> existing = active.computeIfAbsent(id, key -> new LinkedHashMap<>());

		// 槽位被移除或物品被取走 -> 销毁多余实体
		for (String stale : new ArrayList<>(existing.keySet())) {
			if (!slotIds.contains(stale)) {
				ItemDisplay display = existing.remove(stale);
				if (display != null) {
					display.remove();
				}
			}
		}

		for (String slotId : slotIds) {
			CosmeticConfig.Slot slot = config.slot(slotId);
			if (slot == null) {
				continue; // 配置里已删除的槽位：留数据不渲染
			}
			ItemStack stack = wardrobe.get(id, slotId);
			if (stack == null) {
				ItemDisplay display = existing.remove(slotId);
				if (display != null) {
					display.remove();
				}
				continue;
			}
			ItemDisplay display = existing.get(slotId);
			if (display == null || !display.isValid() || display.isDead()) {
				display = spawn(player, slot);
				if (display == null) {
					continue;
				}
				existing.put(slotId, display);
			}
			applyItem(display, stack, slot);
			moveTo(player, display, slot, tick);
		}
	}

	private ItemDisplay spawn(Player player, CosmeticConfig.Slot slot) {
		Location at = player.getLocation().clone();
		World world = player.getWorld();
		try {
			ItemDisplay display = world.spawn(at, ItemDisplay.class, entity -> {
				entity.setPersistent(false);
				entity.setInvulnerable(true);
				entity.setSilent(true);
				entity.setGravity(false);
				entity.setVisibleByDefault(!config.selfOnly);
				entity.setViewRange(config.viewRange);
				entity.setTeleportDuration(config.teleportDuration);
				entity.setInterpolationDuration(config.teleportDuration);
				entity.setShadowRadius(0.0f);
				entity.setShadowStrength(0.0f);
				entity.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
				entity.addScoreboardTag(TAG);
				entity.addScoreboardTag(TAG + "_" + slot.id());
			});
			if (config.selfOnly) {
				for (Player viewer : Bukkit.getOnlinePlayers()) {
					if (!viewer.equals(player)) {
						viewer.hideEntity(plugin, display);
					}
				}
			}
			return display;
		} catch (RuntimeException exception) {
			plugin.getLogger().warning("生成装饰实体失败（部位 " + slot.id() + "）：" + exception.getMessage());
			return null;
		}
	}

	private void applyItem(ItemDisplay display, ItemStack stack, CosmeticConfig.Slot slot) {
		ItemStack render = CosmeticItems.renderCopy(stack);
		ItemStack current = display.getItemStack();
		if (current == null || !current.isSimilar(render)) {
			display.setItemStack(render);
		}
		if (display.getItemDisplayTransform() != slot.transform()) {
			display.setItemDisplayTransform(slot.transform());
		}
		float scale = slot.scale();
		Transformation wanted = new Transformation(
			new Vector3f(0.0f, 0.0f, 0.0f),
			new AxisAngle4f(0.0f, 0.0f, 0.0f, 1.0f),
			new Vector3f(scale, scale, scale),
			new AxisAngle4f(0.0f, 0.0f, 0.0f, 1.0f)
		);
		display.setTransformation(wanted);
	}

	/**
	 * 把实体摆到玩家身上的目标位置。
	 *
	 * <p>坐标系：forward = (-sin yaw, 0, cos yaw)，right = (-forward.z, 0, forward.x)。
	 * 这与 Minecraft 的 yaw 定义一致（yaw 0 面向 +Z 即南）。</p>
	 */
	private void moveTo(Player player, ItemDisplay display, CosmeticConfig.Slot slot, long tick) {
		Location base = player.getLocation();
		float yaw = player.getBodyYaw();
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad);
		double fz = Math.cos(rad);
		double rx = -fz;
		double rz = fx;

		double forward = slot.forward();
		double right = slot.right();
		double up = slot.up();

		if (slot.orbit()) {
			// 环绕：把 (forward, right) 这个偏移整体绕玩家旋转
			double phase = Math.toRadians((tick * slot.spinDegrees() * 0.05) % 360.0);
			double radius = slot.orbitRadius();
			forward = Math.cos(phase) * radius;
			right = Math.sin(phase) * radius;
			up = slot.up();
		}

		double dx = fx * forward + rx * right;
		double dz = fz * forward + rz * right;
		double dy = up;

		if (slot.pitchFollow()) {
			// 头顶槽：抬头时物品稍微后移、低头时稍微前移，避免穿模
			double pitchRad = Math.toRadians(player.getPitch());
			dx += fx * (-pitchRad * 0.18);
			dz += fz * (-pitchRad * 0.18);
			dy -= Math.abs(pitchRad) * 0.04;
		}

		Location target = new Location(base.getWorld(), base.getX() + dx, base.getY() + dy, base.getZ() + dz);
		float displayYaw = slot.orbit()
			? (float) ((tick * slot.spinDegrees() * 0.05) % 360.0)
			: (float) ((yaw + (tick * slot.spinDegrees() * 0.05)) % 360.0);

		Location current = display.getLocation();
		if (current.getWorld() != target.getWorld() || current.distanceSquared(target) > 1.0e-6
			|| Math.abs(current.getYaw() - displayYaw) > 0.5f) {
			target.setYaw(displayYaw);
			target.setPitch(0.0f);
			display.teleport(target);
		}
	}

	/** 销毁某玩家的全部装饰实体（下线、死亡、清空时用）。 */
	void removeAll(UUID playerId, boolean dropNothing) {
		Map<String, ItemDisplay> displays = active.remove(playerId);
		if (displays == null) {
			return;
		}
		for (ItemDisplay display : displays.values()) {
			if (display != null && display.isValid()) {
				display.remove();
			}
		}
	}

	/** 只销毁一个槽的实体（槽内容变化后强制重建）。 */
	void remove(UUID playerId, String slotId) {
		Map<String, ItemDisplay> displays = active.get(playerId);
		if (displays == null) {
			return;
		}
		ItemDisplay display = displays.remove(slotId);
		if (display != null && display.isValid()) {
			display.remove();
		}
	}

	int activeCount() {
		int total = 0;
		for (Map<String, ItemDisplay> displays : active.values()) {
			total += displays.size();
		}
		return total;
	}

	boolean isHidden(UUID playerId) {
		return hidden.contains(playerId);
	}

	void setHidden(UUID playerId, boolean value) {
		if (value) {
			hidden.add(playerId);
		} else {
			hidden.remove(playerId);
		}
	}

	void shutdown() {
		for (UUID id : new ArrayList<>(active.keySet())) {
			removeAll(id, true);
		}
	}
}
