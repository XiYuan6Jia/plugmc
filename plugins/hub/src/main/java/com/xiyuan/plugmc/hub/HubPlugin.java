package com.xiyuan.plugmc.hub;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * 主城大厅插件：开机时创建（或加载）虚空维度 hub，按配置生成大厅建筑，
 * 提供 /hub（进入）、/hub back（返回原位置）以及建造/PvP/刷怪保护。
 */
public final class HubPlugin extends JavaPlugin {
	private HubConfig settings;
	private HubWorld hubWorld;
	private HubMarker marker;
	private HubBlueprint blueprint;
	private HubManager hubManager;
	private HubTeleportManager teleports;
	private HubVitals vitals;
	private HubReturns hubReturns;
	private ParkourManager parkour;
	private ParkourCourse parkourCourse;
	private BellRanking bell;
	private ParkourBoard parkourBoard;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		reloadSettings();

		hubWorld = new HubWorld(this);
		marker = new HubMarker(this);
		blueprint = new HubBlueprint(getDataFolder());
		if (!hubWorld.load(settings)) {
			getLogger().log(Level.SEVERE, "主城维度 " + settings.worldName() + " 创建失败，插件将被禁用");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		hubWorld.ensureLobby(settings, false);

		hubReturns = new HubReturns(getDataFolder(), getLogger());
		hubReturns.load();
		hubManager = new HubManager(this, hubReturns);
		teleports = new HubTeleportManager(this);
		getServer().getPluginManager().registerEvents(teleports, this);
		vitals = new HubVitals(this);
		vitals.start();

		parkour = new ParkourManager(this);
		parkour.load();
		ensureParkour(false);
		getServer().getPluginManager().registerEvents(new HubListener(this), this);

		// 敲钟与跑酷两块榜牌：都在主城维度里，跟着 hub 一起启停
		bell = new BellRanking(this);
		bell.load();
		getServer().getPluginManager().registerEvents(bell, this);
		parkourBoard = new ParkourBoard(this);
		getServer().getPluginManager().registerEvents(parkourBoard, this);
		Bukkit.getScheduler().runTaskLater(this, () -> {
			bell.ensureBoard();
			parkourBoard.ensureBoard();
		}, 40L);
		// 榜牌不每秒重画，只做「缺了就补」；内容变了由各自的 refresh 立即推
		Bukkit.getScheduler().runTaskTimer(this, () -> {
			bell.tick();
			parkourBoard.tick();
		}, 100L, 100L);

		PluginCommand command = getCommand("hub");
		if (command != null) {
			HubCommand executor = new HubCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		} else {
			getLogger().warning("命令 hub 未在 plugin.yml 中注册");
		}

		getLogger().info("主城大厅 已启用（维度 " + settings.worldName()
			+ "，出生点 " + settings.spawnX() + "," + settings.spawnY() + "," + settings.spawnZ() + "）");
	}

	@Override
	public void onDisable() {
		if (bell != null) {
			bell.save();
			bell = null;
		}
		if (parkourBoard != null) {
			parkourBoard = null;
		}
		if (vitals != null) {
			vitals.stop();
			vitals = null;
		}
		getLogger().info("主城大厅 已停用");
	}

	public void reloadSettings() {
		this.settings = HubConfig.load(getConfig());
	}

	public HubConfig settings() {
		return settings;
	}

	public HubWorld hubWorld() {
		return hubWorld;
	}

	public HubMarker marker() {
		return marker;
	}

	public HubBlueprint blueprint() {
		return blueprint;
	}

	public HubManager hubManager() {
		return hubManager;
	}

	public HubTeleportManager teleports() {
		return teleports;
	}

	public HubVitals vitals() {
		return vitals;
	}

	public HubReturns hubReturns() {
		return hubReturns;
	}

	public ParkourManager parkour() {
		return parkour;
	}

	public BellRanking bell() {
		return bell;
	}

	public ParkourBoard parkourBoard() {
		return parkourBoard;
	}

	/**
	 * 按配置准备跑酷赛道：已存在时只重建轨迹索引，force 时重新摆放方块。
	 */
	public void ensureParkour(boolean force) {
		if (!settings.parkourEnabled()) {
			getLogger().info("跑酷赛道已关闭（parkour.enabled=false）");
			return;
		}
		parkourCourse = new ParkourCourse(hubWorld.world(), settings);
		if (!force && marker.isParkourBuilt()) {
			parkour.course(parkourCourse);
			return;
		}
		long start = System.currentTimeMillis();
		parkourCourse.build();
		marker.markParkourBuilt();
		parkour.course(parkourCourse);
		getLogger().info("跑酷赛道已生成（" + parkourCourse.platformCount() + " 段跳台，"
			+ parkourCourse.checkpointCount() + " 个检查点），用时 "
			+ (System.currentTimeMillis() - start) + " ms");
	}
}
