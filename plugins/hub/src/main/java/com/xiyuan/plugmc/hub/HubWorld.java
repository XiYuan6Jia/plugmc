package com.xiyuan.plugmc.hub;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

/**
 * 主城维度的加载与规则设置：虚空生成、无怪物、永昼、无天气、不掉落、不掉虚空。
 */
public final class HubWorld {
	private final HubPlugin plugin;
	private World world;

	public HubWorld(HubPlugin plugin) {
		this.plugin = plugin;
	}

	public boolean load(HubConfig config) {
		World existing = Bukkit.getWorld(config.worldName());
		if (existing == null) {
			existing = new WorldCreator(config.worldName())
				.generator(new VoidChunkGenerator())
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

	public boolean isHub(Location location) {
		return world != null && location != null && world.equals(location.getWorld());
	}

	public Location spawn(HubConfig config) {
		return new Location(
			world,
			config.spawnX(),
			config.spawnY(),
			config.spawnZ(),
			config.spawnYaw(),
			config.spawnPitch()
		);
	}

	public void applyRules(HubConfig config) {
		world.setAutoSave(true);
		world.setDifficulty(Difficulty.PEACEFUL);
		world.setStorm(false);
		world.setThundering(false);
		world.setTime(6000L);
		// 26.x 的 GameRules 常量是当前接口，旧的 GameRule 常量已标记待删除
		world.setGameRule(GameRules.ADVANCE_TIME, false);
		world.setGameRule(GameRules.ADVANCE_WEATHER, false);
		world.setGameRule(GameRules.SPAWN_MOBS, false);
		world.setGameRule(GameRules.MOB_GRIEFING, false);
		world.setGameRule(GameRules.KEEP_INVENTORY, true);
		world.setSpawnLocation(spawn(config));
	}

	/** 返回 true 表示这次真的重新生成了大厅。 */
	public boolean ensureLobby(HubConfig config, boolean force) {
		if (!config.autoBuild() && !force) {
			return false;
		}
		if (!force && HubLobby.isBuilt(world, config)) {
			return false;
		}
		long start = System.currentTimeMillis();
		HubLobby.build(world, config);
		plugin.getLogger().info("主城大厅已生成，用时 " + (System.currentTimeMillis() - start) + " ms");
		return true;
	}
}
