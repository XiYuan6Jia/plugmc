package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * 星际之门：立在 (0, 175, -800) 的巨大圆环，环半径 100、管径 14，
 * 外层是带灯带的抛光黑石，中层是铜，内层是发光核心；
 * 环内还有两道细能量环，正下方是一座 61×61 的启航平台。
 */
public final class StarGate implements MegaStructure {
	static final int CENTER_X = 0;
	static final int CENTER_Y = 175;
	static final int CENTER_Z = -800;
	static final int RADIUS = 100;
	private static final int TUBE_RADIUS = 7;
	private static final int PLATFORM_HALF = 30;
	private static final int PLATFORM_Y = 60;

	@Override
	public String id() {
		return "gate";
	}

	@Override
	public String name() {
		return "星际之门";
	}

	@Override
	public int minX() {
		return CENTER_X - RADIUS - TUBE_RADIUS - 3;
	}

	@Override
	public int maxX() {
		return CENTER_X + RADIUS + TUBE_RADIUS + 3;
	}

	@Override
	public int minZ() {
		return CENTER_Z - PLATFORM_HALF - 1;
	}

	@Override
	public int maxZ() {
		return CENTER_Z + PLATFORM_HALF + 1;
	}

	@Override
	public void fill(ChunkGenerator.ChunkData data, int baseX, int baseZ) {
		for (int localX = 0; localX < 16; localX++) {
			int x = baseX + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int z = baseZ + localZ;
				int dz = z - CENTER_Z;
				buildPlatform(data, baseX, baseZ, x, z, dz);
				buildTorus(data, baseX, baseZ, x, z, dz);
			}
		}
	}

	private void buildPlatform(ChunkGenerator.ChunkData data, int baseX, int baseZ, int x, int z, int dz) {
		if (Math.abs(x) > PLATFORM_HALF || Math.abs(dz) > PLATFORM_HALF) {
			return;
		}
		boolean border = Math.abs(x) == PLATFORM_HALF || Math.abs(dz) == PLATFORM_HALF;
		Material floor = border ? Material.POLISHED_BLACKSTONE : Material.SMOOTH_STONE;
		MegaBlocks.set(data, baseX, baseZ, x, PLATFORM_Y, z, floor);
		if (border && !(dz == PLATFORM_HALF && Math.abs(x) <= 3)) {
			MegaBlocks.set(data, baseX, baseZ, x, PLATFORM_Y + 1, z, Material.TINTED_GLASS);
		}
	}

	private void buildTorus(ChunkGenerator.ChunkData data, int baseX, int baseZ, int x, int z, int dz) {
		boolean plane = dz == 0;
		for (int y = CENTER_Y - RADIUS - TUBE_RADIUS - 1; y <= CENTER_Y + RADIUS + TUBE_RADIUS + 1; y++) {
			double dy = y - CENTER_Y;
			double radial = Math.hypot(x, dy);
			double delta = radial - RADIUS;
			double distanceSquared = delta * delta + dz * dz;
			if (distanceSquared <= TUBE_RADIUS * TUBE_RADIUS) {
				Material material;
				if (distanceSquared >= 40.0) {
					material = MegaBlocks.nearMultiple(Math.toDegrees(Math.atan2(dy, x)), 10.0, 1.3)
						? Material.SEA_LANTERN
						: Material.POLISHED_BLACKSTONE;
				} else if (distanceSquared >= 24.0) {
					material = Material.WAXED_COPPER_BLOCK;
				} else {
					material = Material.GLOWSTONE;
				}
				MegaBlocks.set(data, baseX, baseZ, x, y, z, material);
				continue;
			}
			if (plane && Math.abs(radial - 60.0) <= 0.7) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.SEA_LANTERN);
			} else if (plane && Math.abs(radial - 80.0) <= 0.7) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.AMETHYST_BLOCK);
			}
		}
	}

	@Override
	public List<MegaCheck> checks() {
		return List.of(
			new MegaCheck("星际之门能量环", 0, 275, CENTER_Z, Material.GLOWSTONE),
			new MegaCheck("星际之门外壳", 100, 175, CENTER_Z - 6, Material.WAXED_COPPER_BLOCK),
			new MegaCheck("星际之门内环", 0, 235, CENTER_Z, Material.SEA_LANTERN),
			new MegaCheck("星际之门平台", 10, PLATFORM_Y, CENTER_Z - 10, Material.SMOOTH_STONE)
		);
	}
}
