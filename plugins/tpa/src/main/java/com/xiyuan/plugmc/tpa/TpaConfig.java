package com.xiyuan.plugmc.tpa;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 申请传送的运行时配置。所有数值都会钳制到安全范围。
 */
public record TpaConfig(
	boolean enabled,
	int requestTimeoutSeconds,
	int warmupSeconds,
	int cooldownSeconds,
	boolean cancelOnMove,
	boolean cancelOnDamage,
	boolean allowCrossWorld,
	boolean allowHomeRequest
) {
	public TpaConfig {
		requestTimeoutSeconds = clamp(requestTimeoutSeconds, 10, 600);
		warmupSeconds = clamp(warmupSeconds, 0, 30);
		cooldownSeconds = clamp(cooldownSeconds, 0, 600);
	}

	public static TpaConfig load(FileConfiguration config) {
		return new TpaConfig(
			config.getBoolean("enabled", true),
			config.getInt("request-timeout-seconds", 60),
			config.getInt("warmup-seconds", 3),
			config.getInt("cooldown-seconds", 10),
			config.getBoolean("cancel-on-move", true),
			config.getBoolean("cancel-on-damage", true),
			config.getBoolean("allow-cross-world", true),
			config.getBoolean("allow-home-request", true)
		);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
