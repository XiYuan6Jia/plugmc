package com.xiyuan.plugmc.sethome;

import com.xiyuan.plugmc.sethome.api.HomeService;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 家园与死亡点传送：/sethome、/home、/delhome、/homes、/deathpoint。
 */
public final class SetHomePlugin extends JavaPlugin {
	private static final String[] COMMANDS = {"sethome", "home", "delhome", "homes", "deathpoint"};

	private HomeStore homes;
	private TeleportManager teleports;
	private HomeService homeService;
	private int maxHomes;
	private int warmupSeconds;
	private int cooldownSeconds;
	private boolean deathpointEnabled;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		loadSettings();

		homes = new HomeStore(getDataFolder(), getLogger());
		homes.load();
		teleports = new TeleportManager(this);
		getServer().getPluginManager().registerEvents(teleports, this);
		getServer().getPluginManager().registerEvents(new DeathPointListener(this), this);

		// 别的插件（比如申请传送）要查「某人的家在哪」，只给只读入口
		homeService = new HomeServiceImpl(homes);
		getServer().getServicesManager().register(HomeService.class, homeService, this, ServicePriority.Normal);

		HomeCommand executor = new HomeCommand(this);
		for (String name : COMMANDS) {
			PluginCommand command = getCommand(name);
			if (command == null) {
				getLogger().warning("命令 " + name + " 未在 plugin.yml 中注册");
				continue;
			}
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		}

		getLogger().info("家园与死亡点传送 已启用（默认上限 " + maxHomes + " 个，读秒 "
			+ warmupSeconds + " 秒，冷却 " + cooldownSeconds + " 秒）");
	}

	@Override
	public void onDisable() {
		if (homeService != null) {
			getServer().getServicesManager().unregister(HomeService.class, homeService);
			homeService = null;
		}
		if (homes != null) {
			homes.save();
		}
	}

	public void loadSettings() {
		reloadConfig();
		maxHomes = Math.max(1, getConfig().getInt("max-homes", 3));
		warmupSeconds = Math.max(0, getConfig().getInt("teleport-warmup-seconds", 3));
		cooldownSeconds = Math.max(0, getConfig().getInt("teleport-cooldown-seconds", 10));
		deathpointEnabled = getConfig().getBoolean("deathpoint-enabled", true);
	}

	public HomeStore homes() {
		return homes;
	}

	public TeleportManager teleports() {
		return teleports;
	}

	/**
	 * 玩家家园上限：配置默认值，权限 sethome.max.&lt;数字&gt; 可提高。
	 */
	public int maxHomesFor(Player player) {
		int max = maxHomes;
		for (int candidate = max + 1; candidate <= 100; candidate++) {
			if (player.hasPermission("sethome.max." + candidate)) {
				max = candidate;
			}
		}
		return max;
	}

	public int warmupSeconds() {
		return warmupSeconds;
	}

	public int cooldownSeconds() {
		return cooldownSeconds;
	}

	public boolean deathpointEnabled() {
		return deathpointEnabled;
	}
}
