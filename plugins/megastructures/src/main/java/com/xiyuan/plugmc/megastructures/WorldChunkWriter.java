package com.xiyuan.plugmc.megastructures;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.material.MaterialData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把巨构几何写进一个已经存在的世界（数据包维度）。
 * 供启动补绘与 BlockPopulator 共用；坐标语义与 ChunkGenerator.ChunkData 一致。
 */
final class WorldChunkWriter implements ChunkGenerator.ChunkData {
	private static final Map<Material, BlockData> BLOCK_DATA = new ConcurrentHashMap<>();

	private final World world;
	private final int baseX;
	private final int baseZ;

	WorldChunkWriter(World world, int baseX, int baseZ) {
		this.world = world;
		this.baseX = baseX;
		this.baseZ = baseZ;
	}

	private static BlockData blockData(Material material) {
		return BLOCK_DATA.computeIfAbsent(material, Material::createBlockData);
	}

	@Override
	public int getMinHeight() {
		return world.getMinHeight();
	}

	@Override
	public int getMaxHeight() {
		return world.getMaxHeight();
	}

	@Override
	public Biome getBiome(int x, int y, int z) {
		return Biome.PLAINS;
	}

	@Override
	public void setBlock(int x, int y, int z, Material material) {
		// 关掉物理更新：巨构是几万到几十万方块的一次性摆放，触发连锁更新会卡死服务端
		world.getBlockAt(baseX + x, y, baseZ + z).setBlockData(blockData(material), false);
	}

	@Override
	public void setBlock(int x, int y, int z, MaterialData material) {
		setBlock(x, y, z, material.getItemType());
	}

	@Override
	public void setBlock(int x, int y, int z, BlockData blockData) {
		world.getBlockAt(baseX + x, y, baseZ + z).setBlockData(blockData, false);
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
		for (int x = xMin; x < xMax; x++) {
			for (int y = yMin; y < yMax; y++) {
				for (int z = zMin; z < zMax; z++) {
					setBlock(x, y, z, blockData);
				}
			}
		}
	}

	@Override
	public Material getType(int x, int y, int z) {
		return world.getBlockAt(baseX + x, y, baseZ + z).getType();
	}

	@Override
	public MaterialData getTypeAndData(int x, int y, int z) {
		return new MaterialData(getType(x, y, z));
	}

	@Override
	public BlockData getBlockData(int x, int y, int z) {
		return world.getBlockAt(baseX + x, y, baseZ + z).getBlockData();
	}

	@Override
	public byte getData(int x, int y, int z) {
		return 0;
	}

	@Override
	public int getHeight(HeightMap heightMap, int x, int z) {
		return world.getHighestBlockYAt(baseX + x, baseZ + z, heightMap);
	}
}
