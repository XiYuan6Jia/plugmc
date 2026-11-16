package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * 轨道环：半径 380 的环形天桥，宽 19 格，四层结构，外圈/内圈有玻璃栏杆，
 * 底部每隔 45° 有一根 9×9 的支撑柱一直垂到 Y=-400 的虚空。
 */
public final class OrbitalRing implements MegaStructure {
	static final int RADIUS = 380;
	static final int HALF_WIDTH = 9;
	static final int BOTTOM_Y = 416;
	static final int TOP_Y = 419;
	static final int RAIL_Y = 420;
	private static final int PYLON_HALF = 4;
	private static final int PYLON_MIN_Y = -400;

	@Override
	public String id() {
		return "ring";
	}

	@Override
	public String name() {
		return "轨道环";
	}

	@Override
	public int minX() {
		return -RADIUS - HALF_WIDTH - 1;
	}

	@Override
	public int maxX() {
		return RADIUS + HALF_WIDTH + 1;
	}

	@Override
	public int minZ() {
		return -RADIUS - HALF_WIDTH - 1;
	}

	@Override
	public int maxZ() {
		return RADIUS + HALF_WIDTH + 1;
	}

	@Override
	public void fill(ChunkGenerator.ChunkData data, int baseX, int baseZ) {
		buildWalkway(data, baseX, baseZ);
		buildPylons(data, baseX, baseZ);
	}

	private void buildWalkway(ChunkGenerator.ChunkData data, int baseX, int baseZ) {
		int outer = RADIUS + HALF_WIDTH;
		int inner = RADIUS - HALF_WIDTH;
		for (int localX = 0; localX < 16; localX++) {
			int x = baseX + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int z = baseZ + localZ;
				double radius = Math.hypot(x, z);
				if (radius < inner || radius > outer) {
					continue;
				}
				double angle = Math.toDegrees(Math.atan2(z, x));
				boolean lightStrip = MegaBlocks.nearMultiple(angle, 30.0, 1.2);
				boolean copperBand = MegaBlocks.nearMultiple(angle, 45.0, 2.2);
				double edgeDistance = Math.abs(radius - RADIUS);

				MegaBlocks.set(data, baseX, baseZ, x, BOTTOM_Y, z,
					lightStrip ? Material.SEA_LANTERN : Material.DEEPSLATE_TILES);
				MegaBlocks.set(data, baseX, baseZ, x, BOTTOM_Y + 1, z, Material.POLISHED_DEEPSLATE);
				MegaBlocks.set(data, baseX, baseZ, x, BOTTOM_Y + 2, z,
					copperBand ? Material.WAXED_COPPER_BLOCK : Material.POLISHED_DEEPSLATE);

				Material top;
				if (edgeDistance > HALF_WIDTH - 0.9) {
					top = Material.POLISHED_BLACKSTONE;
				} else if (edgeDistance < 0.6 && Math.floorMod(x + z, 8) == 0) {
					top = Material.SEA_LANTERN;
				} else {
					top = copperBand ? Material.WAXED_COPPER_BLOCK : Material.SMOOTH_QUARTZ;
				}
				MegaBlocks.set(data, baseX, baseZ, x, TOP_Y, z, top);

				if (edgeDistance > HALF_WIDTH - 0.9 && !MegaBlocks.nearMultiple(angle, 45.0, 2.5)) {
					MegaBlocks.set(data, baseX, baseZ, x, RAIL_Y, z, Material.TINTED_GLASS);
				}
			}
		}
	}

	private void buildPylons(ChunkGenerator.ChunkData data, int baseX, int baseZ) {
		for (int spoke = 0; spoke < 8; spoke++) {
			double angle = Math.toRadians(spoke * 45.0);
			int centerX = (int) Math.round(Math.cos(angle) * RADIUS);
			int centerZ = (int) Math.round(Math.sin(angle) * RADIUS);
			int x0 = Math.max(baseX, centerX - PYLON_HALF);
			int x1 = Math.min(baseX + 15, centerX + PYLON_HALF);
			int z0 = Math.max(baseZ, centerZ - PYLON_HALF);
			int z1 = Math.min(baseZ + 15, centerZ + PYLON_HALF);
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					for (int y = PYLON_MIN_Y; y < BOTTOM_Y; y++) {
						Material material = Math.floorMod(y, 24) < 2
							? Material.WAXED_COPPER_BLOCK
							: Material.DEEPSLATE_TILES;
						if (x == centerX && z == centerZ && Math.floorMod(y, 30) < 2) {
							material = Material.SEA_LANTERN;
						}
						MegaBlocks.set(data, baseX, baseZ, x, y, z, material);
					}
				}
			}
		}
	}

	@Override
	public List<MegaCheck> checks() {
		return List.of(
			new MegaCheck("轨道环步道", 0, BOTTOM_Y + 1, RADIUS, Material.POLISHED_DEEPSLATE),
			new MegaCheck("轨道环支撑柱", RADIUS, 100, 0, Material.DEEPSLATE_TILES)
		);
	}
}
