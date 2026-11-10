package com.xiyuan.plugmc.hub;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主城大厅东门外的跑酷赛道：连接走廊 → 起点台 → 20 段跳台（每 5 段一个检查点）→ 终点台。
 * 构造时就把轨迹、检查点和平垫算好（不碰方块），{@link #build()} 才真正摆放方块与悬浮字。
 */
public final class ParkourCourse {
	public enum PadType {
		START,
		CHECKPOINT,
		FINISH
	}

	public record Pad(PadType type, int index) {
	}

	private record BlockSpec(int x, int y, int z, Material material) {
	}

	private record Check(String name, int x, int y, int z, Material expected) {
	}

	private static final int PLATFORM_GAP = 4;
	private static final int PLATFORM_COUNT = 20;
	private static final int CHECKPOINT_EVERY = 5;
	private static final int PLATFORM_HALF = 1;
	private static final int CHECKPOINT_HALF = 2;
	private static final int FINISH_HALF = 3;

	private final World world;
	private final int floorY;
	private final int radius;
	private final int startX;
	private final int finishX;
	private final int finishY;
	private final Map<String, Pad> pads = new HashMap<>();
	private final List<Location> checkpoints = new ArrayList<>();
	private final List<BlockSpec> blocks = new ArrayList<>();
	private final List<Check> checks = new ArrayList<>();
	private final Location startSpawn;
	private final Location finishSpawn;

	public ParkourCourse(World world, HubConfig config) {
		this.world = world;
		this.floorY = config.floorY();
		this.radius = config.radius();
		this.startX = config.radius() + 10;
		this.finishX = startX + (PLATFORM_COUNT + 1) * PLATFORM_GAP;
		this.finishY = floorY + PLATFORM_COUNT / 4 + 1;
		this.startSpawn = new Location(world, startX + 0.5, floorY + 1, 0.5, 270f, 0f);
		this.finishSpawn = new Location(world, finishX + 0.5, finishY + 1, 0.5, 90f, 0f);
		layout();
	}

	public static boolean isBuilt(World world, HubConfig config) {
		return world.getBlockAt(config.radius() + 10, config.floorY(), 0).getType() == Material.GOLD_BLOCK;
	}

	private void layout() {
		for (int x = radius + 1; x < startX - CHECKPOINT_HALF; x++) {
			for (int z = -1; z <= 1; z++) {
				blocks.add(new BlockSpec(
					x, floorY, z,
					x % 5 == 0 && z == 0 ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ
				));
			}
		}

		platform(startX, floorY, 0, CHECKPOINT_HALF, Material.SMOOTH_QUARTZ);
		blocks.add(new BlockSpec(startX, floorY, 0, Material.GOLD_BLOCK));
		register(startX, floorY, 0, PadType.START, 0);
		checkpoints.add(new Location(world, startX + 0.5, floorY + 1, 0.5));
		checks.add(new Check("跑酷起点", startX, floorY, 0, Material.GOLD_BLOCK));
		checks.add(new Check("起点平台", startX + 2, floorY, 0, Material.STONE_BRICKS));
		checks.add(new Check("连接走廊", radius + 2, floorY, 1, Material.SMOOTH_QUARTZ));

		for (int step = 1; step <= PLATFORM_COUNT; step++) {
			int x = startX + step * PLATFORM_GAP;
			int z = switch (step % 3) {
				case 1 -> -3;
				case 2 -> 3;
				default -> 0;
			};
			int y = floorY + (step - 1) / 4;
			boolean checkpoint = step % CHECKPOINT_EVERY == 0 && step < PLATFORM_COUNT;
			platform(x, y, z, checkpoint ? CHECKPOINT_HALF : PLATFORM_HALF, Material.SMOOTH_QUARTZ);
			if (checkpoint) {
				blocks.add(new BlockSpec(x, y, z, Material.GOLD_BLOCK));
				register(x, y, z, PadType.CHECKPOINT, checkpoints.size());
				checkpoints.add(new Location(world, x + 0.5, y + 1, z + 0.5));
				checks.add(new Check("检查点 " + (checkpoints.size() - 1), x, y, z, Material.GOLD_BLOCK));
				continue;
			}
			blocks.add(new BlockSpec(x, y, z, Material.POLISHED_ANDESITE));
			if (step == 1 || step == PLATFORM_COUNT) {
				checks.add(new Check("跳台 #" + step, x, y, z, Material.POLISHED_ANDESITE));
			}
		}

		platform(finishX, finishY, 0, FINISH_HALF, Material.SMOOTH_QUARTZ);
		blocks.add(new BlockSpec(finishX, finishY, 0, Material.DIAMOND_BLOCK));
		register(finishX, finishY, 0, PadType.FINISH, checkpoints.size() - 1);
		checks.add(new Check("跑酷终点", finishX, finishY, 0, Material.DIAMOND_BLOCK));
	}

	private void platform(int centerX, int y, int centerZ, int half, Material material) {
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				boolean border = Math.abs(dx) == half || Math.abs(dz) == half;
				blocks.add(new BlockSpec(
					centerX + dx, y, centerZ + dz,
					border ? Material.STONE_BRICKS : material
				));
			}
		}
	}

	public void build() {
		clearTexts();
		for (BlockSpec block : blocks) {
			world.getBlockAt(block.x(), block.y(), block.z()).setType(block.material(), false);
		}
		text(new Location(world, startX + 0.5, floorY + 3.2, 0.5), "&a&l跑酷起点 &7· &f/hub parkour");
		text(new Location(world, finishX + 0.5, finishY + 3.2, 0.5), "&6&l跑酷终点");
	}

	/** 检查点数量（不含起点）。 */
	public int checkpointCount() {
		return checkpoints.size() - 1;
	}

	public Pad padAt(int x, int y, int z) {
		return pads.get(x + "," + y + "," + z);
	}

	public Location startSpawn() {
		return startSpawn.clone();
	}

	public Location spawnOfCheckpoint(int index) {
		int safe = Math.max(0, Math.min(index, checkpoints.size() - 1));
		return checkpoints.get(safe).clone();
	}

	public Location finishSpawn() {
		return finishSpawn.clone();
	}

	public int platformCount() {
		return PLATFORM_COUNT;
	}

	/** 赛道结构自检：起点/检查点/终点垫方块与关键平台。 */
	public List<String> verify(World world) {
		List<String> lines = new ArrayList<>();
		for (Check check : checks) {
			Material actual = world.getBlockAt(check.x(), check.y(), check.z()).getType();
			boolean ok = actual == check.expected();
			lines.add((ok ? "&a✔ " : "&c✘ ") + check.name() + " &7(" + check.x() + "," + check.y() + ","
				+ check.z() + " "
				+ (ok ? check.expected().name() : "期望 " + check.expected().name() + "，实际 " + actual.name())
				+ ")");
		}
		return lines;
	}

	private void register(int x, int y, int z, PadType type, int index) {
		pads.put(x + "," + y + "," + z, new Pad(type, index));
	}

	private void text(Location location, String content) {
		world.spawn(location, TextDisplay.class, display -> {
			display.text(HubText.component(content));
			display.setBillboard(Display.Billboard.CENTER);
			display.setViewRange(1.5f);
			display.setShadowed(true);
			display.setSeeThrough(false);
			display.setPersistent(true);
		});
	}

	private void clearTexts() {
		Location center = new Location(world, (startX + finishX) / 2.0, floorY + 6.0, 0.5);
		double halfSpan = (finishX - startX) / 2.0 + 4;
		for (Entity entity : world.getNearbyEntities(center, halfSpan, 40, 24)) {
			// 只清理赛道范围内的悬浮字，避免误删大厅标题
			if (entity instanceof TextDisplay && entity.getLocation().getBlockX() >= startX - 4) {
				entity.remove();
			}
		}
	}
}
