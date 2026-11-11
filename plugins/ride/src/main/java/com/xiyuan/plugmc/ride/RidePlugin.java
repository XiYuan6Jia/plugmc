package com.xiyuan.plugmc.ride;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 玩家骑乘插件：把玩家作为乘客挂到另一名玩家身上，暂时只开放给 OP。
 */
public final class RidePlugin extends JavaPlugin {
	private RideConfig settings;
	private RideManager rides;
	private RideListener listener;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		reloadSettings();
		rides = new RideManager(this);
		listener = new RideListener(this);
		getServer().getPluginManager().registerEvents(listener, this);
		listener.start();
		PluginCommand command = getCommand("ride");
		if (command != null) {
			RideCommand executor = new RideCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		} else {
			getLogger().warning("命令 ride 未在 plugin.yml 中注册");
		}
		getLogger().info("玩家骑乘 已启用（权限 ride.use 默认 OP，冷却 " + settings.cooldownSeconds() + " 秒）");
	}

	@Override
	public void onDisable() {
		if (listener != null) {
			listener.stop();
			listener = null;
		}
		if (rides != null) {
			for (var online : getServer().getOnlinePlayers()) {
				rides.dismount(online, null);
			}
			rides = null;
		}
		getLogger().info("玩家骑乘 已停用");
	}

	public void reloadSettings() {
		this.settings = RideConfig.load(getConfig());
	}

	public RideConfig settings() {
		return settings;
	}

	public RideManager rides() {
		return rides;
	}
}
