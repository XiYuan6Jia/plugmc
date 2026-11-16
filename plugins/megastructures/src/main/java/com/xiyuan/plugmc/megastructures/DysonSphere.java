package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * 戴森球：位于 (-800, 180, 0) 的半径 80 格空心球壳，铜制肋条 + 染色玻璃，
 * 内部悬浮一颗发光恒星与一圈能量环；东侧开有半径 19 的入口，
 * 外接一条栈桥与观景平台。
 */
public final class DysonSphere implements MegaStructure {
	static final int CENTER_X = -800;
	static final int CENTER_Y = 180;
	static final int CENTER_Z = 0;
	static final int RADIUS = 80;
	private static final double INNER = 78.0;
	private static final double OUTER = 81.0;
	private static final double OPENING = 19.0;
	private static final double RIM = 22.0;
	private static final int STAR_RADIUS = 12;
	private static final int DECK_Y = 179;
	private static final int DECK_MIN_X = -681;
	private static final int DECK_MAX_X = -661;
	private static final int DECK_Z = 10;
	private static final int BRIDGE_END_X = -720;
	private static final int BRIDGE_HALF_Z = 6;

	@Override
	public String id() {
		return "dyson";
	}

	@Override
	public String name() {
		return "戴森球";
	}

	@Override
	public int minX() {
		return CENTER_X - RADIUS - 2;
	}

	@Override
	public int maxX() {
		return DECK_MAX_X + 1;
	}

	@Override
	public int minZ() {
		return CENTER_Z - RADIUS - 2;
	}

	@Override
	public int maxZ() {
		return CENTER_Z + RADIUS + 2;
	}

	@Override
	public void fill(ChunkGenerator.ChunkData data, int baseX, int baseZ) {
		for (int localX = 0; localX < 16; localX++) {
			int x = baseX + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int z = baseZ + localZ;
				buildDeckAndBridge(data, baseX, baseZ, x, z);
				if (Math.hypot(x - CENTER_X, z - CENTER_Z) > OUTER + 1.0) {
					continue;
				}
				buildSphereColumn(data, baseX, baseZ, x, z);
			}
		}
	}

	private void buildDeckAndBridge(ChunkGenerator.ChunkData data, int baseX, int baseZ, int x, int z) {
		boolean onDeck = x >= DECK_MIN_X && x <= DECK_MAX_X && Math.abs(z) <= DECK_Z;
		if (onDeck && (x == DECK_MIN_X || x == DECK_MAX_X || Math.abs(z) == DECK_Z)) {
			MegaBlocks.set(data, baseX, baseZ, x, DECK_Y, z, Material.DEEPSLATE_TILES);
			boolean bridgeMouth = x == DECK_MIN_X && Math.abs(z) <= BRIDGE_HALF_Z;
			if (!bridgeMouth) {
				MegaBlocks.set(data, baseX, baseZ, x, DECK_Y + 1, z, Material.TINTED_GLASS);
			}
		} else if (onDeck) {
			MegaBlocks.set(data, baseX, baseZ, x, DECK_Y, z, Material.SMOOTH_QUARTZ);
		}

		boolean onBridge = x >= BRIDGE_END_X && x <= DECK_MIN_X && Math.abs(z) <= BRIDGE_HALF_Z;
		if (onBridge) {
			MegaBlocks.set(data, baseX, baseZ, x, DECK_Y, z, Material.SMOOTH_QUARTZ);
			if (Math.abs(z) == BRIDGE_HALF_Z) {
				MegaBlocks.set(data, baseX, baseZ, x, DECK_Y + 1, z, Material.TINTED_GLASS);
			}
			if ((x == -716 || x == -704 || x == -692) && Math.abs(z) == BRIDGE_HALF_Z) {
				for (int y = DECK_Y - 20; y < DECK_Y; y++) {
					MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.WAXED_COPPER_BLOCK);
				}
			}
		}
	}

	private void buildSphereColumn(ChunkGenerator.ChunkData data, int baseX, int baseZ, int x, int z) {
		double dx = x - CENTER_X;
		double dz = z - CENTER_Z;
		double planar = Math.hypot(dx, dz);
		for (int y = CENTER_Y - RADIUS - 1; y <= CENTER_Y + RADIUS + 1; y++) {
			double dy = y - CENTER_Y;
			double distanceSquared = dx * dx + dy * dy + dz * dz;
			if (distanceSquared >= INNER * INNER && distanceSquared <= OUTER * OUTER) {
				buildShellBlock(data, baseX, baseZ, x, y, z, dx, dy, dz, distanceSquared);
			} else if (distanceSquared <= (double) STAR_RADIUS * STAR_RADIUS) {
				Material star = Math.floorMod(x + y + z, 9) == 0
					? Material.MAGMA_BLOCK
					: Material.GLOWSTONE;
				MegaBlocks.set(data, baseX, baseZ, x, y, z, star);
			} else if (Math.abs(dy) <= 1.0 && Math.abs(planar - 34.0) <= 1.0) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.SEA_LANTERN);
			}
		}
	}

	private void buildShellBlock(
		ChunkGenerator.ChunkData data,
		int baseX,
		int baseZ,
		int x,
		int y,
		int z,
		double dx,
		double dy,
		double dz,
		double distanceSquared
	) {
		double distance = Math.sqrt(distanceSquared);
		double longitude = Math.toDegrees(Math.atan2(dz, dx));
		double latitude = Math.toDegrees(Math.asin(dy / distance));
		boolean rib = MegaBlocks.nearMultiple(longitude, 15.0, 1.0)
			|| MegaBlocks.nearMultiple(latitude, 15.0, 1.0);
		double axisDistance = Math.hypot(dy, dz);
		if (dx > 0 && axisDistance < RIM) {
			if (axisDistance < OPENING) {
				return;
			}
			MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.WAXED_COPPER_BLOCK);
			return;
		}
		MegaBlocks.set(data, baseX, baseZ, x, y, z,
			rib ? Material.WAXED_COPPER_BLOCK : Material.TINTED_GLASS);
	}

	@Override
	public List<MegaCheck> checks() {
		return List.of(
			new MegaCheck("戴森球能量核心", CENTER_X, CENTER_Y, CENTER_Z, Material.GLOWSTONE),
			new MegaCheck("戴森球外壳", CENTER_X, CENTER_Y, CENTER_Z + RADIUS, Material.WAXED_COPPER_BLOCK),
			new MegaCheck("戴森球入口", CENTER_X + RADIUS, CENTER_Y, CENTER_Z, Material.AIR),
			new MegaCheck("戴森球栈桥", -700, DECK_Y, 0, Material.SMOOTH_QUARTZ),
			new MegaCheck("戴森球观景台", -671, DECK_Y, 0, Material.SMOOTH_QUARTZ)
		);
	}
}
