package com.xiyuan.plugmc.train;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 列车插件的运行参数。
 *
 * <p>速度类数值的单位都是「格 / tick」：0.4 约等于每秒 8 格，和原版矿车在直道上的上限接近。</p>
 */
public record TrainConfig(
	boolean enabled,
	int maxCars,
	int tickInterval,
	double travelSpeed,
	double approachSpeed,
	double brakeDistance,
	double stopRadius,
	int defaultDwellSeconds,
	int maxSearchBlocks,
	double carSpacing,
	boolean linkParticles,
	boolean freeInCreative,
	int maxFuelPerCart
) {
	public static TrainConfig load(FileConfiguration config) {
		return new TrainConfig(
			config.getBoolean("enabled", true),
			clamp(config.getInt("limits.max-cars", 12), 2, 40),
			clamp(config.getInt("limits.tick-interval", 1), 1, 10),
			clamp(config.getDouble("speed.travel", 0.34), 0.05, 0.9),
			clamp(config.getDouble("speed.approach", 0.12), 0.02, 0.6),
			clamp(config.getDouble("speed.brake-distance", 14.0), 2.0, 64.0),
			clamp(config.getDouble("speed.stop-radius", 1.1), 0.6, 3.0),
			clamp(config.getInt("station.default-dwell-seconds", 8), 1, 600),
			clamp(config.getInt("route.max-search-blocks", 96), 16, 512),
			// 车厢间距按需求收在 0.5~1.0 格：现实里的列车就是这么贴着的。
			// 上限卡 1.0 是有意的 —— 再宽就不像编组而像一队各自跑的车了。
			clamp(config.getDouble("limits.car-spacing", 0.9), 0.5, 1.0),
			config.getBoolean("effects.link-particles", true),
			config.getBoolean("limits.free-in-creative", true),
			clamp(config.getInt("limits.max-fuel-per-cart", 12000), 0, 200000)
		);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
