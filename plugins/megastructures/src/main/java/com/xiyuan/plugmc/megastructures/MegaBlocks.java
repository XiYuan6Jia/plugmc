package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

/**
 * 区块生成期间写方块的小工具：自动把世界坐标换算成区块内坐标，并裁掉世界高度之外的部分。
 */
final class MegaBlocks {
	private MegaBlocks() {
	}

	static void set(
		ChunkGenerator.ChunkData data,
		int baseX,
		int baseZ,
		int x,
		int y,
		int z,
		Material material
	) {
		if (y < data.getMinHeight() || y >= data.getMaxHeight()) {
			return;
		}
		int localX = x - baseX;
		int localZ = z - baseZ;
		if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
			return;
		}
		data.setBlock(localX, y, localZ, material);
	}

	/**
	 * 角度是否落在某个步长附近（用于辐条、灯带等周期性装饰）。
	 * 支持负角度，例如 -90 与 step=90 会命中。
	 */
	static boolean nearMultiple(double angleDegrees, double step, double tolerance) {
		double wrapped = ((angleDegrees % step) + step) % step;
		return wrapped <= tolerance || step - wrapped <= tolerance;
	}
}
