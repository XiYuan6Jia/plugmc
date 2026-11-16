package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * 太空电梯：位于 (800, 0) 的 11×11 玻璃井道，从 Y=61 一直升到 Y=299，
 * 内部是发光能量柱，每 48 格一层悬挑平台；底部是 41×41 基座，
 * 顶端是 31×31 平台与钻石晶体。
 */
public final class SpaceElevator implements MegaStructure {
	static final int CENTER_X = 800;
	static final int CENTER_Z = 0;
	static final int BASE_Y = 60;
	static final int SHAFT_TOP = 299;
	private static final int BASE_HALF = 20;
	private static final int SHAFT_HALF = 5;
	private static final int DECK_HALF = 8;
	private static final int TOP_HALF = 15;
	private static final int[] DECK_LEVELS = {108, 156, 204, 252};

	@Override
	public String id() {
		return "elevator";
	}

	@Override
	public String name() {
		return "太空电梯";
	}

	@Override
	public int minX() {
		return CENTER_X - BASE_HALF - 1;
	}

	@Override
	public int maxX() {
		return CENTER_X + BASE_HALF + 1;
	}

	@Override
	public int minZ() {
		return CENTER_Z - BASE_HALF - 1;
	}

	@Override
	public int maxZ() {
		return CENTER_Z + BASE_HALF + 1;
	}

	@Override
	public void fill(ChunkGenerator.ChunkData data, int baseX, int baseZ) {
		for (int localX = 0; localX < 16; localX++) {
			int x = baseX + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int z = baseZ + localZ;
				int dx = x - CENTER_X;
				int dz = z - CENTER_Z;
				for (int y = BASE_Y; y <= SHAFT_TOP + 9; y++) {
					buildBase(data, baseX, baseZ, x, z, dx, dz, y);
					buildShaft(data, baseX, baseZ, x, z, dx, dz, y);
					buildDecks(data, baseX, baseZ, x, z, dx, dz, y);
					buildTop(data, baseX, baseZ, x, z, dx, dz, y);
				}
			}
		}
	}

	private void buildBase(ChunkGenerator.ChunkData data, int baseX, int baseZ, int x, int z, int dx, int dz, int y) {
		if (y == BASE_Y && Math.abs(dx) <= BASE_HALF && Math.abs(dz) <= BASE_HALF) {
			boolean border = Math.abs(dx) == BASE_HALF || Math.abs(dz) == BASE_HALF;
			Material floor;
			if (border) {
				floor = Material.POLISHED_BLACKSTONE;
			} else if (Math.floorMod(dx, 5) == 0 || Math.floorMod(dz, 5) == 0) {
				floor = Material.POLISHED_DEEPSLATE;
			} else {
				floor = Material.SMOOTH_STONE;
			}
			MegaBlocks.set(data, baseX, baseZ, x, y, z, floor);
		}
		if (y == BASE_Y + 1
			&& (Math.abs(dx) == BASE_HALF || Math.abs(dz) == BASE_HALF)
			&& !(dz == BASE_HALF && Math.abs(dx) <= 3)) {
			MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.TINTED_GLASS);
		}
	}

	private void buildShaft(ChunkGenerator.ChunkData data, int baseX, int baseZ, int x, int z, int dx, int dz, int y) {
		if (y <= BASE_Y || y > SHAFT_TOP || Math.abs(dx) > SHAFT_HALF || Math.abs(dz) > SHAFT_HALF) {
			return;
		}
		if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
			Material core = y % 12 < 2 ? Material.WAXED_COPPER_BLOCK : Material.SEA_LANTERN;
			MegaBlocks.set(data, baseX, baseZ, x, y, z, core);
			return;
		}
		if (Math.abs(dx) == SHAFT_HALF || Math.abs(dz) == SHAFT_HALF) {
			boolean corner = Math.abs(dx) == SHAFT_HALF && Math.abs(dz) == SHAFT_HALF;
			boolean band = Math.floorMod(y, 15) < 2;
			MegaBlocks.set(data, baseX, baseZ, x, y, z,
				corner || band ? Material.WAXED_COPPER_BLOCK : Material.GLASS);
		}
	}

	private void buildDecks(ChunkGenerator.ChunkData data, int baseX, int baseZ, int x, int z, int dx, int dz, int y) {
		if (Math.abs(dx) > DECK_HALF || Math.abs(dz) > DECK_HALF) {
			return;
		}
		for (int level : DECK_LEVELS) {
			if (y == level) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.POLISHED_BLACKSTONE);
			} else if (y == level + 1
				&& (Math.abs(dx) == DECK_HALF || Math.abs(dz) == DECK_HALF)
				&& !(dz == -DECK_HALF && Math.abs(dx) <= 2)) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.TINTED_GLASS);
			}
		}
	}

	private void buildTop(ChunkGenerator.ChunkData data, int baseX, int baseZ, int x, int z, int dx, int dz, int y) {
		if (y == SHAFT_TOP + 1 && Math.abs(dx) <= TOP_HALF && Math.abs(dz) <= TOP_HALF) {
			boolean border = Math.abs(dx) == TOP_HALF || Math.abs(dz) == TOP_HALF;
			MegaBlocks.set(data, baseX, baseZ, x, y, z,
				border ? Material.DEEPSLATE_TILES : Material.POLISHED_BLACKSTONE);
		}
		if (y == SHAFT_TOP + 2
			&& (Math.abs(dx) == TOP_HALF || Math.abs(dz) == TOP_HALF)
			&& !(Math.abs(dx) <= 2 && Math.abs(dz) == TOP_HALF)
			&& !(Math.abs(dz) <= 2 && Math.abs(dx) == TOP_HALF)) {
			MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.TINTED_GLASS);
		}
		if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
			if (y >= SHAFT_TOP + 2 && y <= SHAFT_TOP + 8) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.DIAMOND_BLOCK);
			} else if (y == SHAFT_TOP + 9) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.SEA_LANTERN);
			}
		}
	}

	@Override
	public List<MegaCheck> checks() {
		return List.of(
			new MegaCheck("太空电梯基座", 818, BASE_Y, 18, Material.SMOOTH_STONE),
			new MegaCheck("太空电梯井壁", 805, 100, 0, Material.GLASS),
			new MegaCheck("太空电梯顶端平台", 800, SHAFT_TOP + 1, 12, Material.POLISHED_BLACKSTONE),
			new MegaCheck("太空电梯晶体", 800, SHAFT_TOP + 6, 0, Material.DIAMOND_BLOCK)
		);
	}
}
