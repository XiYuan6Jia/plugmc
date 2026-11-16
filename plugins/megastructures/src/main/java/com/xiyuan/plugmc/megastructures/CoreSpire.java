package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * 核心尖塔：轨道环圆心的巨塔，半径 18、从 Y=54 一直升到 Y=299，
 * 外壳是带深板岩肋条与铜环带的染色玻璃，内部贯穿发光能量核心；
 * Y=286 有一圈观景台，塔顶是发光尖冠与钻石晶体。
 */
public final class CoreSpire implements MegaStructure {
	static final int RADIUS = 18;
	static final int CORE_RADIUS = 5;
	static final int Y_MIN = 54;
	static final int Y_MAX = 299;
	static final int BALCONY_Y = 286;
	static final int BALCONY_OUTER = 30;

	@Override
	public String id() {
		return "spire";
	}

	@Override
	public String name() {
		return "核心尖塔";
	}

	@Override
	public int minX() {
		return -BALCONY_OUTER - 4;
	}

	@Override
	public int maxX() {
		return BALCONY_OUTER + 4;
	}

	@Override
	public int minZ() {
		return -BALCONY_OUTER - 4;
	}

	@Override
	public int maxZ() {
		return BALCONY_OUTER + 4;
	}

	@Override
	public void fill(ChunkGenerator.ChunkData data, int baseX, int baseZ) {
		for (int localX = 0; localX < 16; localX++) {
			int x = baseX + localX;
			for (int localZ = 0; localZ < 16; localZ++) {
				int z = baseZ + localZ;
				double radius = Math.hypot(x, z);
				if (radius > BALCONY_OUTER + 1.0) {
					continue;
				}
				double angle = Math.toDegrees(Math.atan2(z, x));
				boolean rib = MegaBlocks.nearMultiple(angle, 15.0, 1.4);
				buildTowerColumn(data, baseX, baseZ, x, z, radius, rib);
				buildBalconyColumn(data, baseX, baseZ, x, z, radius, angle);
				buildCrownColumn(data, baseX, baseZ, x, z, radius);
			}
		}
		// 塔尖晶体只在 (0,0) 这一根柱子上，避免在别的区块里重复摆放
		MegaBlocks.set(data, baseX, baseZ, 0, Y_MAX + 13, 0, Material.DIAMOND_BLOCK);
		MegaBlocks.set(data, baseX, baseZ, 0, Y_MAX + 14, 0, Material.SEA_LANTERN);
	}

	private void buildTowerColumn(
		ChunkGenerator.ChunkData data,
		int baseX,
		int baseZ,
		int x,
		int z,
		double radius,
		boolean rib
	) {
		for (int y = Y_MIN; y <= Y_MAX; y++) {
			if (radius <= CORE_RADIUS) {
				Material core = Math.floorMod(y, 10) < 2
					? Material.WAXED_COPPER_BLOCK
					: Material.SEA_LANTERN;
				MegaBlocks.set(data, baseX, baseZ, x, y, z, core);
				continue;
			}
			if (radius > 16.0 && radius <= RADIUS) {
				boolean band = Math.floorMod(y - Y_MIN, 24) < 2;
				Material shell = rib
					? Material.DEEPSLATE_TILES
					: band ? Material.WAXED_COPPER_BLOCK : Material.TINTED_GLASS;
				MegaBlocks.set(data, baseX, baseZ, x, y, z, shell);
			}
		}
		// 塔基四条巨大的扶壁
		if (radius > RADIUS && radius <= 34.0 && MegaBlocks.nearMultiple(
			Math.toDegrees(Math.atan2(z, x)), 90.0, 3.0
		)) {
			for (int y = Y_MIN; y <= Y_MIN + 36; y++) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z, Material.DEEPSLATE_TILES);
			}
		}
	}

	private void buildBalconyColumn(
		ChunkGenerator.ChunkData data,
		int baseX,
		int baseZ,
		int x,
		int z,
		double radius,
		double angle
	) {
		if (radius <= RADIUS || radius > BALCONY_OUTER) {
			return;
		}
		MegaBlocks.set(data, baseX, baseZ, x, BALCONY_Y, z, Material.POLISHED_DEEPSLATE);
		if (radius > BALCONY_OUTER - 0.9 && !MegaBlocks.nearMultiple(angle, 90.0, 5.0)) {
			MegaBlocks.set(data, baseX, baseZ, x, BALCONY_Y + 1, z, Material.TINTED_GLASS);
		}
	}

	private void buildCrownColumn(
		ChunkGenerator.ChunkData data,
		int baseX,
		int baseZ,
		int x,
		int z,
		double radius
	) {
		for (int y = Y_MAX + 1; y <= Y_MAX + 12; y++) {
			double maxRadius = RADIUS * (1.0 - (y - (Y_MAX + 1)) / 12.0);
			if (radius <= maxRadius) {
				MegaBlocks.set(data, baseX, baseZ, x, y, z,
					y % 2 == 0 ? Material.SEA_LANTERN : Material.DEEPSLATE_TILES);
			}
		}
	}

	@Override
	public List<MegaCheck> checks() {
		return List.of(
			new MegaCheck("尖塔外壳", RADIUS, 203, 0, Material.DEEPSLATE_TILES),
			new MegaCheck("尖塔能量核心", 0, 203, 0, Material.SEA_LANTERN),
			new MegaCheck("尖塔观景台", 0, BALCONY_Y, 25, Material.POLISHED_DEEPSLATE),
			new MegaCheck("尖塔顶冠", 0, Y_MAX + 13, 0, Material.DIAMOND_BLOCK)
		);
	}
}
