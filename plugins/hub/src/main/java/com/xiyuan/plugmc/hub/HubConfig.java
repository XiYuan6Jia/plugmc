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
	String difficulty,
	boolean joinAuto,
	int teleportWarmupSeconds,
	boolean respawnInHub,
	boolean rescueVoid,
	boolean denyBuild,
	boolean denyPvp,
	boolean denyMobSpawn,
	boolean noFallDamage,
	boolean lockFood,
	boolean lockHealth,
	boolean noNaturalRegen,
	boolean parkourEnabled,
	boolean parkourAnnounce,
	boolean bellEnabled,
	int bellX,
	int bellY,
	int bellZ,
	double bellBoardX,
	double bellBoardY,
	double bellBoardZ,
	int bellBoardRows,
	double parkourBoardX,
	double parkourBoardY,
	double parkourBoardZ,
	int parkourBoardRows,
	Integer blueprintMinX,
	Integer blueprintMinY,
	Integer blueprintMinZ,
	Integer blueprintMaxX,
	Integer blueprintMaxY,
	Integer blueprintMaxZ
) {
	public HubConfig {
		worldName = worldName == null || worldName.isBlank() ? "hub" : worldName.trim();
		floorY = clamp(floorY, -32, 200);
		radius = clamp(radius, 8, 48);
		teleportWarmupSeconds = clamp(teleportWarmupSeconds, 0, 60);
		bellBoardRows = clamp(bellBoardRows, 1, 20);
		parkourBoardRows = clamp(parkourBoardRows, 1, 20);
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
			config.getString("protect.difficulty", "NORMAL"),
			config.getBoolean("teleport.join-auto", false),
			config.getInt("teleport.warmup-seconds", 3),
			config.getBoolean("teleport.respawn-in-hub", true),
			config.getBoolean("teleport.rescue-void", true),
			config.getBoolean("protect.deny-build", true),
			config.getBoolean("protect.deny-pvp", true),
			config.getBoolean("protect.deny-mob-spawn", true),
			config.getBoolean("protect.no-fall-damage", true),
			config.getBoolean("protect.lock-food", true),
			config.getBoolean("protect.lock-health", true),
			config.getBoolean("protect.no-natural-regen", true),
			config.getBoolean("parkour.enabled", true),
			config.getBoolean("parkour.announce-records", true),
			config.getBoolean("bell.enabled", true),
			config.getInt("bell.x", 0),
			config.getInt("bell.y", 66),
			config.getInt("bell.z", 58),
			config.getDouble("bell.board.x", 0.437),
			config.getDouble("bell.board.y", 66.0),
			config.getDouble("bell.board.z", 61.5),
			config.getInt("bell.board.rows", 10),
			config.getDouble("parkour.board.x", 34.5),
			config.getDouble("parkour.board.y", 65.0),
			config.getDouble("parkour.board.z", 5.5),
			config.getInt("parkour.board.rows", 10),
			// 蓝本区域：留空（null）表示用默认的「大厅方形占地」
			config.isSet("blueprint.min-x") ? config.getInt("blueprint.min-x") : null,
			config.isSet("blueprint.min-y") ? config.getInt("blueprint.min-y") : null,
			config.isSet("blueprint.min-z") ? config.getInt("blueprint.min-z") : null,
			config.isSet("blueprint.max-x") ? config.getInt("blueprint.max-x") : null,
			config.isSet("blueprint.max-y") ? config.getInt("blueprint.max-y") : null,
			config.isSet("blueprint.max-z") ? config.getInt("blueprint.max-z") : null
		);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
