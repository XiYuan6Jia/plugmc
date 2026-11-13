package com.xiyuan.plugmc.hub;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

/**
 * 主城大厅建筑：地板、四面墙、四座门、柱子、屋顶天窗、中央喷泉和悬浮文字。
 * 全部用固定坐标生成，不依赖任何素材文件。
 */
public final class HubLobby {
	static final int WALL_HEIGHT = 10;
	static final int ROOF_OFFSET = WALL_HEIGHT + 1;
	private static final int DOOR_HALF_WIDTH = 3;
	private static final int DOOR_HEIGHT = 5;
	private static final int FOUNTAIN_INNER = 5;
	private static final int FOUNTAIN_OUTER = 6;

	private HubLobby() {
	}

	/**
	 * 老版本用来判断「大厅已生成」的标记方块（喷泉顶端的海晶灯）。
	 *
	 * <p><b>不要再把它当成「建没建过」的依据。</b>建筑师拆个灯、换块水晶、铺地时顺手盖一层，
	 * 就会让判断翻转成「没建过」，下次开服整片清空重建，改动全丢。判断改用
	 * {@link HubMarker}（数据目录里的 lobby-built.yml），这里只为老存档升级时认一下。
	 */
	public static boolean isLegacyBuilt(World world, HubConfig config) {
		return world.getBlockAt(0, config.floorY() + 4, 0).getType() == Material.SEA_LANTERN;
	}

	/**
	 * 关键结构自检：喷泉顶灯、喷泉、墙体柱子、屋顶天窗、大门。
	 *
	 * <p>第一行的喷泉顶灯只是<b>结构部件</b>的检查，跟「大厅建没建过」无关 ——
	 * 判断建没建过看 {@link HubMarker}。建筑师把它换成别的装饰，这条会红，但不会导致重建。
	 */
	public static java.util.List<String> verify(World world, HubConfig config, boolean markerBuilt) {
		int floorY = config.floorY();
		int radius = config.radius();
		java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add((markerBuilt ? "&a✔ " : "&c✘ ") + "生成标记 &7(lobby-built.yml "
			+ (markerBuilt ? "已记录" : "缺失，下次开服会重建大厅") + ")");
		check(lines, "喷泉顶灯", world, 0, floorY + 4, 0, Material.SEA_LANTERN);
		check(lines, "喷泉水池", world, 0, floorY, 3, Material.WATER);
		check(lines, "喷泉外圈", world, 0, floorY, FOUNTAIN_OUTER, Material.STONE_BRICKS);
		check(lines, "地板", world, 10, floorY, 10, Material.SMOOTH_QUARTZ);
		check(lines, "墙体柱子", world, radius, floorY + 7, 12, Material.QUARTZ_PILLAR);
		check(lines, "屋顶", world, radius, floorY + ROOF_OFFSET, 0, Material.SMOOTH_QUARTZ);
		check(lines, "屋顶天窗", world, 0, floorY + ROOF_OFFSET, 0, Material.GLASS);
		check(lines, "大门开口", world, 0, floorY + 1, radius, Material.AIR);
		return lines;
	}

	private static void check(
		java.util.List<String> lines,
		String name,
		World world,
		int x,
		int y,
		int z,
		Material expected
	) {
		Material actual = world.getBlockAt(x, y, z).getType();
		lines.add((actual == expected ? "&a✔ " : "&c✘ ") + name + " &7(" + x + "," + y + "," + z + " "
			+ (actual == expected ? expected.name() : "期望 " + expected.name() + "，实际 " + actual.name()) + ")");
	}

	public static void build(World world, HubConfig config) {
		int floorY = config.floorY();
		int radius = config.radius();
		clearTexts(world, config);
		clearBlocks(world, config);
		buildFloor(world, floorY, radius);
		buildWalls(world, floorY, radius);
		buildRoof(world, floorY, radius);
		buildInnerPillars(world, floorY, radius);
		spawnTexts(world, config);
	}

	/**
	 * 清出大厅的方形占地，y 从地板到屋顶再高一格。
	 *
	 * <p>范围就是 {@code radius}，**不能 +1**：半径 24 的大厅外缘正好贴着光环廊（|x| 或 |z| 落在
	 * 25~28）和西南角商业街（z 从 24 起），多清一格就会把街区铺装一起抹掉。
	 */
	private static void clearBlocks(World world, HubConfig config) {
		int floorY = config.floorY();
		int radius = config.radius();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				for (int y = floorY; y <= floorY + ROOF_OFFSET + 1; y++) {
					set(world, x, y, z, Material.AIR);
				}
			}
		}
	}

	/**
	 * 只清大厅自己挂的悬浮文字。
	 *
	 * <p>范围必须收在大厅之内：以前按 64×48×64 扫，会把商业街摊位上的招牌、货台展示一起删掉
	 * （那些实体在 z 24~62、x -60~-24，落在旧范围里）。现在按 radius 收边，并且跳过带
	 * {@code plugmc_bazaar} 标签的实体 —— 那是商业街在管的。
	 */
	private static void clearTexts(World world, HubConfig config) {
		int radius = config.radius() + 1;
		Location center = new Location(world, 0.5, config.floorY() + 6.0, 0.5);
		for (Entity entity : world.getNearbyEntities(center, radius, ROOF_OFFSET + 6, radius)) {
			if (!(entity instanceof TextDisplay)) {
				continue;
			}
			if (entity.getScoreboardTags().contains("plugmc_bazaar")) {
				continue;
			}
			entity.remove();
		}
	}

	private static void buildFloor(World world, int floorY, int radius) {
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				int distanceSquared = x * x + z * z;
				Material material;
				if (distanceSquared <= FOUNTAIN_OUTER * FOUNTAIN_OUTER) {
					material = distanceSquared <= FOUNTAIN_INNER * FOUNTAIN_INNER
						? Material.WATER
						: Material.STONE_BRICKS;
				} else if (Math.abs(x) == radius || Math.abs(z) == radius) {
					material = Material.STONE_BRICKS;
				} else if (Math.abs(x) % 6 == 0 || Math.abs(z) % 6 == 0) {
					material = Material.POLISHED_ANDESITE;
				} else {
					material = Material.SMOOTH_QUARTZ;
				}
				set(world, x, floorY, z, material);
			}
		}
		// 喷泉中心：底座 + 石英柱 + 顶端海晶灯（同时作为已生成标记）
		set(world, 0, floorY, 0, Material.STONE_BRICKS);
		for (int y = floorY + 1; y <= floorY + 3; y++) {
			set(world, 0, y, 0, Material.QUARTZ_PILLAR);
		}
		set(world, 0, floorY + 4, 0, Material.SEA_LANTERN);
		// 地板灯带
		for (int x = -radius + 6; x <= radius - 6; x += 6) {
			for (int z = -radius + 6; z <= radius - 6; z += 6) {
				if (x * x + z * z > (FOUNTAIN_OUTER + 1) * (FOUNTAIN_OUTER + 1)) {
					set(world, x, floorY, z, Material.SEA_LANTERN);
				}
			}
		}
	}

	private static void buildWalls(World world, int floorY, int radius) {
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				boolean onX = Math.abs(x) == radius;
				boolean onZ = Math.abs(z) == radius;
				if (!onX && !onZ) {
					continue;
				}
				boolean door = (onZ ? Math.abs(x) : Math.abs(z)) <= DOOR_HALF_WIDTH;
				// 门洞范围内不放柱子，否则门中间会被柱子挡住
				boolean pillar = !door && ((onZ && Math.abs(x) % 6 == 0) || (onX && Math.abs(z) % 6 == 0));
				for (int y = floorY + 1; y <= floorY + WALL_HEIGHT; y++) {
					int height = y - floorY;
					if (pillar) {
						set(world, x, y, z, height == WALL_HEIGHT ? Material.SEA_LANTERN : Material.QUARTZ_PILLAR);
						continue;
					}
					if (door && height <= DOOR_HEIGHT) {
						set(world, x, y, z, Material.AIR);
						continue;
					}
					Material material = height <= 4
						? Material.STONE_BRICKS
						: height <= 6 ? Material.GLASS_PANE : Material.SMOOTH_QUARTZ;
					set(world, x, y, z, material);
				}
			}
		}
	}

	private static void buildRoof(World world, int floorY, int radius) {
		int roofY = floorY + ROOF_OFFSET;
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				boolean skylight = Math.abs(x) <= 6 && Math.abs(z) <= 6;
				set(world, x, roofY, z, skylight ? Material.GLASS : Material.SMOOTH_QUARTZ);
			}
		}
	}

	private static void buildInnerPillars(World world, int floorY, int radius) {
		int offset = Math.max(6, radius / 2);
		int[][] corners = {{-offset, -offset}, {-offset, offset}, {offset, -offset}, {offset, offset}};
		for (int[] corner : corners) {
			for (int y = floorY + 1; y <= floorY + WALL_HEIGHT; y++) {
				int height = y - floorY;
				set(world, corner[0], y, corner[1],
					height == 8 ? Material.LANTERN : Material.QUARTZ_PILLAR);
			}
		}
	}

	private static void spawnTexts(World world, HubConfig config) {
		int floorY = config.floorY();
		Location titleLocation = new Location(world, 0.5, floorY + 6.0, 0.5);
		world.spawn(titleLocation, TextDisplay.class, display -> {
			display.text(HubText.component(config.title()));
			display.setBillboard(Display.Billboard.CENTER);
			display.setViewRange(2.0f);
			display.setShadowed(true);
			display.setSeeThrough(false);
			display.setPersistent(true);
		});
		Location subtitleLocation = new Location(world, 0.5, floorY + 4.0, 11.5);
		world.spawn(subtitleLocation, TextDisplay.class, display -> {
			display.text(HubText.component(config.subtitle()));
			display.setBillboard(Display.Billboard.CENTER);
			display.setViewRange(1.0f);
			display.setShadowed(true);
			display.setPersistent(true);
		});
	}

	private static void set(World world, int x, int y, int z, Material material) {
		world.getBlockAt(x, y, z).setType(material, false);
	}
}
