package com.xiyuan.plugmc.megastructures;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 星际巨构配置。数值都钳制到安全范围，避免手改配置把巨构维度或出生点设到不可用的位置。
 */
public record MegaConfig(
	String worldKey,
	int borderSize,
	double spawnX,
	double spawnY,
	double spawnZ,
	float spawnYaw,
	float spawnPitch,
	boolean joinAuto,
	boolean denyBuild,
	boolean denyPvp,
	boolean denyMobSpawn,
	boolean noFallDamage,
	boolean rescueVoid,
	int voidY,
	boolean respawnInMega,
	boolean flight,
	boolean nightVision
) {
	public static final String DEFAULT_WORLD_KEY = "plugmc:mega";

	public MegaConfig {
		worldKey = worldKey == null || worldKey.isBlank() ? "plugmc:mega" : worldKey.trim();
		borderSize = clamp(borderSize, 200, 40000);
		spawnY = Math.max(-511.0, Math.min(1534.0, spawnY));
		voidY = clamp(voidY, -511, 100);
	}

	public static MegaConfig load(FileConfiguration config) {
		return new MegaConfig(
			config.getString("world.key", "plugmc:mega"),
			config.getInt("world.border-size", 5000),
			config.getDouble("world.spawn.x", 0.5),
			config.getDouble("world.spawn.y", 420.0),
			config.getDouble("world.spawn.z", 380.5),
			(float) config.getDouble("world.spawn.yaw", 180.0),
			(float) config.getDouble("world.spawn.pitch", -8.0),
			config.getBoolean("teleport.join-auto", false),
			config.getBoolean("protect.deny-build", true),
			config.getBoolean("protect.deny-pvp", true),
			config.getBoolean("protect.deny-mob-spawn", true),
			config.getBoolean("protect.no-fall-damage", true),
			config.getBoolean("protect.rescue-void", true),
			config.getInt("protect.void-y", -460),
			config.getBoolean("protect.respawn-in-mega", true),
			config.getBoolean("comfort.flight", true),
			config.getBoolean("comfort.night-vision", true)
		);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
