package com.xiyuan.plugmc.hub;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 主城大厅配置。数值都钳制到安全范围，避免手改配置把大厅造到世界外。
 */
public record HubConfig(
	String worldName,
	int floorY,
	int radius,
	boolean autoBuild,
	double spawnX,
	double spawnY,
	double spawnZ,
	float spawnYaw,
	float spawnPitch,
	String title,
	String subtitle,
	boolean joinAuto,
	boolean respawnInHub,
	boolean rescueVoid,
	boolean denyBuild,
	boolean denyPvp,
	boolean denyMobSpawn,
	boolean noFallDamage
) {
	public HubConfig {
		worldName = worldName == null || worldName.isBlank() ? "hub" : worldName.trim();
		floorY = clamp(floorY, -32, 200);
		radius = clamp(radius, 8, 48);
	}

	public static HubConfig load(FileConfiguration config) {
		return new HubConfig(
			config.getString("world.name", "hub"),
			config.getInt("world.floor-y", 64),
			config.getInt("world.radius", 24),
			config.getBoolean("world.auto-build", true),
			config.getDouble("spawn.x", 0.5),
			config.getDouble("spawn.y", 65.0),
			config.getDouble("spawn.z", 8.5),
			(float) config.getDouble("spawn.yaw", 180.0),
			(float) config.getDouble("spawn.pitch", 0.0),
			config.getString("text.title", "&6&l主城大厅"),
			config.getString("text.subtitle", "&7/menu 打开菜单 · &f/hub back &7返回原位置"),
			config.getBoolean("teleport.join-auto", false),
			config.getBoolean("teleport.respawn-in-hub", true),
			config.getBoolean("teleport.rescue-void", true),
			config.getBoolean("protect.deny-build", true),
			config.getBoolean("protect.deny-pvp", true),
			config.getBoolean("protect.deny-mob-spawn", true),
			config.getBoolean("protect.no-fall-damage", true)
		);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
