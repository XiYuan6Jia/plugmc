package com.xiyuan.plugmc.minigames;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主城大厅北侧的实体店：三个摊位（21 点 / 转盘 / 老虎机），
 * 每个摊位有一块可右键的柜台方块和一串悬浮字说明。
 */
public final class VenueBuilder {
	private record BlockSpec(int x, int y, int z, Material material) {
	}

	private record Venue(GameType game, int centerX, int centerZ) {
	}

	private final MinigamesPlugin plugin;
	private final Map<String, GameType> counters = new HashMap<>();

	public VenueBuilder(MinigamesPlugin plugin) {
		this.plugin = plugin;
	}

	public GameType counterAt(Block block) {
		if (block == null) {
			return null;
		}
		return counters.get(key(block.getX(), block.getY(), block.getZ()));
	}

	public int venueCount() {
		return counters.size();
	}

	/** 生成（或重建）三个摊位；返回是否成功。 */
	public boolean build(boolean force) {
		GamesConfig config = plugin.settings();
		if (!config.venueEnabled()) {
			plugin.getLogger().info("实体店已关闭（venue.enabled=false）");
			return false;
		}
		World world = org.bukkit.Bukkit.getWorld(config.venueWorld());
		if (world == null) {
			plugin.getLogger().warning("实体店所在世界 " + config.venueWorld() + " 未加载，跳过建造");
			return false;
		}
		counters.clear();
		clearTexts(world, config);
		List<BlockSpec> blocks = new ArrayList<>();
		List<Venue> venues = new ArrayList<>();

		int laneZ = config.venueCenterZ() + 8;
		buildWalkway(blocks, config, laneZ);
		GameType[] games = {GameType.BLACKJACK, GameType.WHEEL, GameType.SLOTS};
		for (int index = 0; index < games.length; index++) {
			int centerX = config.venueCenterX() + (index - 1) * config.venueSpacing();
			venues.add(new Venue(games[index], centerX, config.venueCenterZ()));
			buildBooth(blocks, config, centerX, config.venueCenterZ(), games[index]);
		}
		for (BlockSpec spec : blocks) {
			world.getBlockAt(spec.x(), spec.y(), spec.z()).setType(spec.material(), false);
		}
		for (Venue venue : venues) {
			counters.put(
				key(venue.centerX(), config.venueFloorY() + 1, venue.centerZ()),
				venue.game()
			);
			text(world, new Location(world, venue.centerX() + 0.5, config.venueFloorY() + 5.4,
				venue.centerZ() + 0.5), venue.game().venueTitle());
			text(world, new Location(world, venue.centerX() + 0.5, config.venueFloorY() + 4.4,
				venue.centerZ() + 0.5), "&7右键柜台开始 · &f/games " + venue.game().command());
		}
		plugin.getLogger().info("实体店已生成：3 个摊位（" + (force ? "强制重建" : "首次建造") + "）");
		return true;
	}

	private void buildWalkway(List<BlockSpec> blocks, GamesConfig config, int laneZ) {
		int halfLength = config.venueSpacing() + 6;
		for (int x = -halfLength; x <= halfLength; x++) {
			for (int z = laneZ - 1; z <= laneZ + 1; z++) {
				boolean light = Math.abs(x) % 6 == 0 && z == laneZ;
				blocks.add(new BlockSpec(
					config.venueCenterX() + x, config.venueFloorY(), z,
					light ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ
				));
			}
		}
		// 从大厅北门接到摊位小巷
		for (int z = laneZ + 2; z <= laneZ + 12; z++) {
			for (int x = -1; x <= 1; x++) {
				blocks.add(new BlockSpec(config.venueCenterX() + x, config.venueFloorY(), z, Material.SMOOTH_QUARTZ));
			}
		}
	}

	private void buildBooth(List<BlockSpec> blocks, GamesConfig config, int centerX, int centerZ, GameType game) {
		int floorY = config.venueFloorY();
		int half = 3;
		Material accent = switch (game) {
			case BLACKJACK -> Material.RED_STAINED_GLASS;
			case WHEEL -> Material.YELLOW_STAINED_GLASS;
			case SLOTS -> Material.LIGHT_BLUE_STAINED_GLASS;
		};
		Material carpet = switch (game) {
			case BLACKJACK -> Material.RED_CARPET;
			case WHEEL -> Material.YELLOW_CARPET;
			case SLOTS -> Material.LIGHT_BLUE_CARPET;
		};
		// 地台
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				boolean border = Math.abs(dx) == half || Math.abs(dz) == half;
				blocks.add(new BlockSpec(centerX + dx, floorY, centerZ + dz,
					border ? Material.POLISHED_ANDESITE : carpet));
			}
		}
		// 四角柱子 + 屋顶
		int[][] corners = {{-half, -half}, {-half, half}, {half, -half}, {half, half}};
		for (int[] corner : corners) {
			for (int y = floorY + 1; y <= floorY + 4; y++) {
				blocks.add(new BlockSpec(centerX + corner[0], y, centerZ + corner[1], Material.QUARTZ_PILLAR));
			}
			blocks.add(new BlockSpec(centerX + corner[0], floorY + 4, centerZ + corner[1], Material.SEA_LANTERN));
		}
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				boolean border = Math.abs(dx) == half || Math.abs(dz) == half;
				blocks.add(new BlockSpec(centerX + dx, floorY + 5, centerZ + dz,
					border ? Material.SMOOTH_QUARTZ : accent));
			}
		}
		// 柜台与装饰
		blocks.add(new BlockSpec(centerX, floorY + 1, centerZ, game.counterMaterial()));
		blocks.add(new BlockSpec(centerX, floorY + 2, centerZ, Material.SMOOTH_QUARTZ_SLAB));
		blocks.add(new BlockSpec(centerX - 1, floorY + 1, centerZ - 2, Material.BARREL));
		blocks.add(new BlockSpec(centerX + 1, floorY + 1, centerZ - 2, Material.BARREL));
	}

	private void text(World world, Location location, String content) {
		world.spawn(location, TextDisplay.class, display -> {
			display.text(Text.of(content));
			display.setBillboard(Display.Billboard.CENTER);
			display.setViewRange(1.5f);
			display.setShadowed(true);
			display.setSeeThrough(false);
			display.setPersistent(true);
		});
	}

	private void clearTexts(World world, GamesConfig config) {
		Location center = new Location(world, config.venueCenterX() + 0.5,
			config.venueFloorY() + 5.0, config.venueCenterZ() - 6.0);
		int span = config.venueSpacing() * 2 + 8;
		for (Entity entity : world.getNearbyEntities(center, span, 20, 24)) {
			if (entity instanceof TextDisplay && entity.getLocation().getBlockZ() <= config.venueCenterZ() + 12) {
				entity.remove();
			}
		}
	}

	private static String key(int x, int y, int z) {
		return x + "," + y + "," + z;
	}
}
