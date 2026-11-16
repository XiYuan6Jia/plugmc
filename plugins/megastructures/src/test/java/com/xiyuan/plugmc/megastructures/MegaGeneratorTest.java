package com.xiyuan.plugmc.megastructures;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.material.MaterialData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MegaGeneratorTest {
	@Test
	void structureChecksMatchGeneratedBlocks() {
		for (MegaStructure structure : MegaStructures.all()) {
			for (MegaCheck check : structure.checks()) {
				RecordingChunkData data = generate(check.x(), check.z());
				assertEquals(
					check.expected(),
					data.typeAt(check.x(), check.y(), check.z()),
					structure.name() + " / " + check.label()
				);
			}
		}
	}

	@Test
	void viewpointsStandOnSolidGround() {
		for (MegaViewpoint viewpoint : MegaStructures.viewpoints()) {
			int x = (int) Math.floor(viewpoint.x());
			int y = (int) Math.floor(viewpoint.y());
			int z = (int) Math.floor(viewpoint.z());
			RecordingChunkData data = generate(x, z);
			assertNotEquals(Material.AIR, data.typeAt(x, y - 1, z), viewpoint.label() + " 脚下是空气");
			assertEquals(Material.AIR, data.typeAt(x, y, z), viewpoint.label() + " 站位被方块占用");
			assertEquals(Material.AIR, data.typeAt(x, y + 1, z), viewpoint.label() + " 头顶被方块占用");
		}
	}

	@Test
	void emptyVoidFarAwayFromStructures() {
		RecordingChunkData data = generate(1600, 1600);
		assertTrue(data.isEmpty(), "远处的区块不应该有任何方块");
	}

	private static RecordingChunkData generate(int worldX, int worldZ) {
		int chunkX = Math.floorDiv(worldX, 16);
		int chunkZ = Math.floorDiv(worldZ, 16);
		RecordingChunkData data = new RecordingChunkData(chunkX, chunkZ);
		new MegaGenerator().generateSurface(null, new Random(0L), chunkX, chunkZ, data);
		return data;
	}

	private static final class RecordingChunkData implements ChunkGenerator.ChunkData {
		private final int baseX;
		private final int baseZ;
		private final Map<String, Material> blocks = new HashMap<>();

		private RecordingChunkData(int chunkX, int chunkZ) {
			this.baseX = chunkX << 4;
			this.baseZ = chunkZ << 4;
		}

		private static String key(int x, int y, int z) {
			return x + ":" + y + ":" + z;
		}

		Material typeAt(int worldX, int worldY, int worldZ) {
			return blocks.getOrDefault(key(worldX - baseX, worldY, worldZ - baseZ), Material.AIR);
		}

		boolean isEmpty() {
			return blocks.isEmpty();
		}

		@Override
		public int getMinHeight() {
			return -64;
		}

		@Override
		public int getMaxHeight() {
			return 320;
		}

		@Override
		public Biome getBiome(int x, int y, int z) {
			return Biome.PLAINS;
		}

		@Override
		public void setBlock(int x, int y, int z, Material material) {
			blocks.put(key(x, y, z), material);
		}

		@Override
		public void setBlock(int x, int y, int z, MaterialData material) {
			setBlock(x, y, z, material.getItemType());
		}

		@Override
		public void setBlock(int x, int y, int z, BlockData blockData) {
			setBlock(x, y, z, blockData.getMaterial());
		}

		@Override
		public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, Material material) {
			for (int x = xMin; x < xMax; x++) {
				for (int y = yMin; y < yMax; y++) {
					for (int z = zMin; z < zMax; z++) {
						setBlock(x, y, z, material);
					}
				}
			}
		}

		@Override
		public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, MaterialData material) {
			setRegion(xMin, yMin, zMin, xMax, yMax, zMax, material.getItemType());
		}

		@Override
		public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, BlockData blockData) {
			setRegion(xMin, yMin, zMin, xMax, yMax, zMax, blockData.getMaterial());
		}

		@Override
		public Material getType(int x, int y, int z) {
			return blocks.getOrDefault(key(x, y, z), Material.AIR);
		}

		@Override
		public MaterialData getTypeAndData(int x, int y, int z) {
			return new MaterialData(getType(x, y, z));
		}

		@Override
		public BlockData getBlockData(int x, int y, int z) {
			return null;
		}

		@Override
		public byte getData(int x, int y, int z) {
			return 0;
		}

		@Override
		public int getHeight(HeightMap heightMap, int x, int z) {
			return getMinHeight();
		}
	}
}
