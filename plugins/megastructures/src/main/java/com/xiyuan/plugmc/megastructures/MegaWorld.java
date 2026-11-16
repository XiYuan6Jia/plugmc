package com.xiyuan.plugmc.megastructures;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;

/**
 * 巨构维度：由 datapacks/plugmc-mega 定义的 2048 格高度虚空维度。
 * 数据包负责“有这个世界”，插件负责世界规则、巨构补绘与观景辅助。
 */
public final class MegaWorld {
	private final MegaPlugin plugin;
	private World world;

	public MegaWorld(MegaPlugin plugin) {
		this.plugin = plugin;
	}

	public boolean load(MegaConfig config) {
		NamespacedKey key = NamespacedKey.fromString(config.worldKey());
		if (key == null) {
			plugin.getLogger().severe("无效的维度键：" + config.worldKey());
			return false;
		}
		World existing = Bukkit.getWorld(key);
		if (existing == null) {
			plugin.getLogger().severe("找不到数据包维度 " + key
				+ "；请先部署 datapacks/plugmc-mega 并重启服务端。");
			return false;
		}
		this.world = existing;
		if (world.getPopulators().stream().noneMatch(populator -> populator instanceof MegaPopulator)) {
			world.getPopulators().add(0, new MegaPopulator(MegaStructures.all()));
		}
		// 数据包世界在插件启用前就准备好了出生区区块，这些区块没有经过 populator。
		// 先记下当时的出生点，再把出生区完整补绘一遍。
		Location originalSpawn = world.getSpawnLocation();
		applyRules(config);
		plugin.getLogger().info("已接入数据包维度 " + key + "（高度 " + world.getMinHeight()
			+ " ~ " + (world.getMaxHeight() - 1) + "）");
		patchSpawnArea(originalSpawn, 5);
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

	/**
	 * 补绘出生区（Paper 启动时会在插件之前生成 11×11 区块）。
	 * 这些区块可能已经从磁盘读入且没有巨构方块，强制加载后按区块重写一遍。
	 */
	private void patchSpawnArea(Location center, int radiusChunks) {
		int centerX = center.getBlockX() >> 4;
		int centerZ = center.getBlockZ() >> 4;
		long start = System.currentTimeMillis();
		int count = 0;
		for (int x = centerX - radiusChunks; x <= centerX + radiusChunks; x++) {
			for (int z = centerZ - radiusChunks; z <= centerZ + radiusChunks; z++) {
				Chunk chunk = world.getChunkAt(x, z);
				patchChunk(chunk);
				count++;
			}
		}
		plugin.getLogger().info("出生区巨构补绘完成：" + count + " 个区块，用时 "
			+ (System.currentTimeMillis() - start) + " ms");
	}

	void patchChunk(Chunk chunk) {
		int baseX = chunk.getX() << 4;
		int baseZ = chunk.getZ() << 4;
		WorldChunkWriter writer = new WorldChunkWriter(world, baseX, baseZ);
		for (MegaStructure structure : MegaStructures.all()) {
			if (structure.intersects(baseX, baseZ, baseX + 15, baseZ + 15)) {
				structure.fill(writer, baseX, baseZ);
			}
		}
	}
}
