package com.xiyuan.plugmc.megastructures;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * 巨构维度生成器：区块首次生成时把落在本区块里的巨构方块写进 ChunkData。
 * 巨构全部是纯数学几何，无存档、无素材、可重复生成。
 */
public final class MegaGenerator extends ChunkGenerator {
	private final List<MegaStructure> structures;

	public MegaGenerator() {
		this(MegaStructures.all());
	}

	MegaGenerator(List<MegaStructure> structures) {
		this.structures = List.copyOf(structures);
	}

	@Override
	public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
		int baseX = chunkX << 4;
		int baseZ = chunkZ << 4;
		for (MegaStructure structure : structures) {
			if (structure.intersects(baseX, baseZ, baseX + 15, baseZ + 15)) {
				structure.fill(chunkData, baseX, baseZ);
			}
		}
	}

	@Override
	public boolean isParallelCapable() {
		return true;
	}

	@Override
	public boolean shouldGenerateNoise() {
		return false;
	}

	@Override
	public boolean shouldGenerateSurface() {
		return false;
	}

	@Override
	public boolean shouldGenerateBedrock() {
		return false;
	}

	@Override
	public boolean shouldGenerateCaves() {
		return false;
	}

	@Override
	public boolean shouldGenerateDecorations() {
		return false;
	}

	@Override
	public boolean shouldGenerateMobs() {
		return false;
	}

	@Override
	public boolean shouldGenerateStructures() {
		return false;
	}

	@Override
	public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
		return new BiomeProvider() {
			@Override
			public Biome getBiome(WorldInfo info, int x, int y, int z) {
				return Biome.PLAINS;
			}

			@Override
			public List<Biome> getBiomes(WorldInfo info) {
				return List.of(Biome.PLAINS);
			}
		};
	}
}
