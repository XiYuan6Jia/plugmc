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
		// 和平难度会自动补满饱食度并持续回血，主城默认使用普通难度；
		// 禁刷怪由 protect.deny-mob-spawn 与 SPAWN_MOBS 游戏规则负责。
		world.setDifficulty(parseDifficulty(config.difficulty()));
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
		plugin.getLogger().info("主城世界规则：难度 " + world.getDifficulty()
			+ "，禁刷怪 " + config.denyMobSpawn()
			+ "，锁血 " + config.lockHealth()
			+ "，锁饱食度 " + config.lockFood());
	}

	private Difficulty parseDifficulty(String raw) {
		if (raw != null) {
			for (Difficulty value : Difficulty.values()) {
				if (value.name().equalsIgnoreCase(raw.trim())) {
					return value;
				}
			}
		}
		return Difficulty.NORMAL;
	}

	/**
	 * 返回 true 表示这次真的重新生成了大厅。
	 *
	 * <p>「建没建过」只看 {@link HubMarker} 里的文件，不看世界里的方块 —— 用方块当标记的话，
	 * 建筑师一动那颗灯，下次开服就会把整片清空重建。
	 */
	public boolean ensureLobby(HubConfig config, boolean force) {
		if (!config.autoBuild() && !force) {
			return false;
		}
		if (!force) {
			// 老存档：文件还没写，但世界里的旧标记还在 → 补写文件，不重建
			if (plugin.marker().migrateFromLegacyBlock(world, config)) {
				plugin.marker().warnIfGeometryChanged(config);
				return false;
			}
			if (plugin.marker().isBuilt()) {
				plugin.marker().warnIfGeometryChanged(config);
				return false;
			}
		}
		long start = System.currentTimeMillis();
		// 先清场：清悬浮文字 + 清方块。蓝本若不清场会被旧方块漏出来。
		HubLobby.clear(world, config);
		// 有蓝本就用蓝本（建筑师改过的那一版），没有才退回内置的硬编码大厅。
		boolean placed = plugin.blueprint().place(world, config);
		if (!placed) {
			HubLobby.buildDefaults(world, config);
		}
		// 悬浮文字永远按配置现场生成 —— 它不进蓝本，进了就会每次重建叠一层。
		HubLobby.spawnTexts(world, config);
		plugin.marker().markBuilt(config);
		plugin.getLogger().info("主城大厅已" + (placed ? "按蓝本摆放" : "生成")
			+ "，用时 " + (System.currentTimeMillis() - start) + " ms");
		return true;
	}

	/**
	 * 把当前世界里的状态存成蓝本，供以后 {@code /hub build} 还原。
	 *
	 * @return 成功返回方块数（> 0），失败返回 -1
	 */
	public long saveBlueprint(HubConfig config) {
		return plugin.blueprint().save(world, config);
	}
}
