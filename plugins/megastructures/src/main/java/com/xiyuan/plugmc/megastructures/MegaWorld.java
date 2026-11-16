package com.xiyuan.plugmc.megastructures;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

/**
 * 巨构维度的加载与规则：虚空生成、固定午夜、无怪物、无天气、无摔落伤害。
 */
public final class MegaWorld {
	private final MegaPlugin plugin;
	private World world;

	public MegaWorld(MegaPlugin plugin) {
		this.plugin = plugin;
	}

	public boolean load(MegaConfig config) {
		World existing = Bukkit.getWorld(config.worldName());
		if (existing == null) {
			existing = new WorldCreator(config.worldName())
				.generator(new MegaGenerator())
				.environment(World.Environment.NORMAL)
				.type(WorldType.FLAT)
				.generateStructures(false)
				.createWorld();
		}
		if (existing == null) {
			return false;
		}
		this.world = existing;
		applyRules(config);
		return true;
	}

	public World world() {
		return world;
	}

	public boolean ready() {
		return world != null;
	}

	public boolean isMega(Location location) {
		return world != null && location != null && world.equals(location.getWorld());
	}

	public Location spawn(MegaConfig config) {
		return new Location(
			world,
			config.spawnX(),
			config.spawnY(),
			config.spawnZ(),
			config.spawnYaw(),
			config.spawnPitch()
		);
	}

	public Location viewpoint(MegaViewpoint viewpoint) {
		return new Location(
			world,
			viewpoint.x(),
			viewpoint.y(),
			viewpoint.z(),
			viewpoint.yaw(),
			viewpoint.pitch()
		);
	}

	public void applyRules(MegaConfig config) {
		world.setAutoSave(true);
		world.setDifficulty(Difficulty.NORMAL);
		world.setStorm(false);
		world.setThundering(false);
		world.setTime(18000L);

		world.setGameRule(GameRules.ADVANCE_TIME, false);
		world.setGameRule(GameRules.ADVANCE_WEATHER, false);
		world.setGameRule(GameRules.SPAWN_MOBS, !config.denyMobSpawn());
		world.setGameRule(GameRules.MOB_GRIEFING, false);
		world.setGameRule(GameRules.KEEP_INVENTORY, true);
		world.setGameRule(GameRules.FALL_DAMAGE, !config.noFallDamage());
		world.setGameRule(GameRules.PVP, !config.denyPvp());
		world.setGameRule(GameRules.RAIDS, false);
		world.setGameRule(GameRules.SPAWN_PATROLS, false);
		world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
		world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
		world.setGameRule(GameRules.SPECTATORS_GENERATE_CHUNKS, false);
		world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);

		WorldBorder border = world.getWorldBorder();
		border.setCenter(0.5, 0.5);
		border.setSize(config.borderSize());
		border.setWarningDistance(5);
		border.setDamageAmount(0.0);

		world.setSpawnLocation(spawn(config));
		plugin.getLogger().info("星际巨构世界规则：固定午夜，禁刷怪 " + config.denyMobSpawn()
			+ "，禁建造 " + config.denyBuild()
			+ "，允许飞行 " + config.flight()
			+ "，夜视 " + config.nightVision());
	}
}
