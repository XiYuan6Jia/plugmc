package com.xiyuan.plugmc.megastructures;

import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * 一座巨构：在区块生成时按世界坐标把自己写进 ChunkData。
 * 实现必须无状态且线程安全（Paper 可能并行生成区块）。
 */
public interface MegaStructure {
	String id();

	String name();

	int minX();

	int maxX();

	int minZ();

	int maxZ();

	void fill(ChunkGenerator.ChunkData data, int baseX, int baseZ);

	List<MegaCheck> checks();

	default boolean intersects(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
		return minX() <= maxChunkX && maxX() >= minChunkX && minZ() <= maxChunkZ && maxZ() >= minChunkZ;
	}
}
