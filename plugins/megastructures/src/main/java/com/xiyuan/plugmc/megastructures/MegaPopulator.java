package com.xiyuan.plugmc.megastructures;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;

import java.util.List;
import java.util.Random;

/**
 * 数据包维度没有 Bukkit ChunkGenerator，所以用 BlockPopulator 在新区块生成后补上巨构方块。
 * 和生成器一样只写落在本区块内的部分，跨区块的巨构由相邻区块各自补齐。
 */
final class MegaPopulator extends BlockPopulator {
	private final List<MegaStructure> structures;

	MegaPopulator(List<MegaStructure> structures) {
		this.structures = List.copyOf(structures);
	}

	@Override
	public void populate(World world, Random random, Chunk chunk) {
		int baseX = chunk.getX() << 4;
		int baseZ = chunk.getZ() << 4;
		WorldChunkWriter writer = new WorldChunkWriter(world, baseX, baseZ);
		for (MegaStructure structure : structures) {
			if (structure.intersects(baseX, baseZ, baseX + 15, baseZ + 15)) {
				structure.fill(writer, baseX, baseZ);
			}
		}
	}
}
