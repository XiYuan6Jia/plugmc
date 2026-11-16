package com.xiyuan.plugmc.train;

import com.xiyuan.plugmc.train.api.TrainService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 铁道列车：用拴绳把矿车连成列车，再让车头沿着设定好的路线自动逐站停靠。
 *
 * <p>为什么单开一个插件而不是塞进 ride：ride 做的是「玩家骑到玩家身上」，
 * 与矿车逻辑毫无交集；两者放在一起只会让各自的配置与权限纠缠。</p>
 */
public final class TrainPlugin extends JavaPlugin {
	private TrainConfig settings;
	private TrainManager trains;
	private TrainStations stations;
	private TrainRoutes routes;
	private TrainLines lines;
	private TrainDriver driver;
	private TrainListener listener;
	private TrainServiceImpl trainService;
	private int driverTask = -1;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		reloadSettings();

		trains = new TrainManager(this);
		trains.load();
		stations = new TrainStations(getDataFolder());
		stations.load();
		routes = new TrainRoutes(getDataFolder());
		routes.load();
		lines = new TrainLines(getDataFolder());
		lines.load();
		driver = new TrainDriver(this);
		listener = new TrainListener(this);
		getServer().getPluginManager().registerEvents(listener, this);

		PluginCommand command = getCommand("train");
		if (command != null) {
			TrainCommand executor = new TrainCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		}

		// 菜单要读「我有哪些列车」，只给它一个只读+本人操作的接口
		trainService = new TrainServiceImpl(this);
		getServer().getServicesManager().register(TrainService.class, trainService, this,
			org.bukkit.plugin.ServicePriority.Normal);

		startTasks();
		getLogger().info("铁道列车 已启用（站点 " + stations.size() + "，路线 " + routes.size()
			+ "，列车 " + lines.size() + "，车厢上限 " + settings.maxCars() + "）");
	}

	@Override
	public void onDisable() {
		stopTasks();
		if (trainService != null) {
			getServer().getServicesManager().unregister(TrainService.class, trainService);
			trainService = null;
		}
		if (driver != null) {
			driver.clear();
		}
		if (trains != null) {
			trains.prune();
		}
		stations = null;
		routes = null;
		lines = null;
		driver = null;
		listener = null;
		getLogger().info("铁道列车 已停用");
	}

	public void reloadSettings() {
		reloadConfig();
		settings = TrainConfig.load(getConfig());
		if (isEnabled()) {
			startTasks();
		}
	}

	private void startTasks() {
		stopTasks();
		if (settings == null || !settings.enabled()) {
			return;
		}
		driverTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
			if (driver != null) {
				driver.tick();
			}
			if (listener != null) {
				listener.tickParticles();
				// 每隔一拍清理一次已消失的实体，避免长期运行后记录膨胀
				if (trains != null) {
					trains.prune();
				}
			}
		}, settings.tickInterval(), settings.tickInterval()).getTaskId();
	}

	private void stopTasks() {
		if (driverTask != -1) {
			Bukkit.getScheduler().cancelTask(driverTask);
			driverTask = -1;
		}
	}

	public TrainConfig settings() {
		return settings;
	}

	public TrainManager trains() {
		return trains;
	}

	public TrainStations stations() {
		return stations;
	}

	public TrainRoutes routes() {
		return routes;
	}

	public TrainLines lines() {
		return lines;
	}

	public TrainDriver driver() {
		return driver;
	}

	public TrainListener listener() {
		return listener;
	}
}
