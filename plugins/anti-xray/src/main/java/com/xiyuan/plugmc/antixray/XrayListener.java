package com.xiyuan.plugmc.antixray;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录玩家挖到的矿石数量与其中「周围没有空气/液体」的隐藏矿数量。
 */
public final class XrayListener implements Listener {
	private static final BlockFace[] FACES = {
		BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
	};

	private final AntiXrayPlugin plugin;
	private final Map<UUID, OreStats> stats = new ConcurrentHashMap<>();

	public XrayListener(AntiXrayPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		if (!plugin.settings().detectionEnabled()) {
			return;
		}
		Player player = event.getPlayer();
		GameMode mode = player.getGameMode();
		if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
			return;
		}
		Block block = event.getBlock();
		if (!isOre(block.getType())) {
			return;
		}
		OreStats oreStats = stats.computeIfAbsent(
			player.getUniqueId(),
			id -> new OreStats(player.getName())
		);
		oreStats.record(isHidden(block));
		plugin.checkAlert(player, oreStats);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		stats.remove(event.getPlayer().getUniqueId());
	}

	public Map<UUID, OreStats> stats() {
		return stats;
	}

	public void reset(UUID playerId) {
		stats.remove(playerId);
	}

	public void resetAll() {
		stats.clear();
	}

	public static boolean isOre(Material material) {
		return material != null && (material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS);
	}

	/**
	 * 六个面都没有空气/液体，说明玩家不是从洞穴里直接看到这块矿。
	 */
	public static boolean isHidden(Block block) {
		for (BlockFace face : FACES) {
			Material type = block.getRelative(face).getType();
			if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
				return false;
			}
		}
		return true;
	}
}
