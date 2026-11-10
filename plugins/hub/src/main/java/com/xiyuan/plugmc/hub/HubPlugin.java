package com.xiyuan.plugmc.hub;

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
	private HubManager hubManager;
	private HubReturns hubReturns;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		reloadSettings();

		hubWorld = new HubWorld(this);
		if (!hubWorld.load(settings)) {
			getLogger().log(Level.SEVERE, "主城维度 " + settings.worldName() + " 创建失败，插件将被禁用");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		hubWorld.ensureLobby(settings, false);

		hubReturns = new HubReturns(getDataFolder(), getLogger());
		hubReturns.load();
		hubManager = new HubManager(this, hubReturns);
		getServer().getPluginManager().registerEvents(new HubListener(this), this);
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

	public HubManager hubManager() {
		return hubManager;
	}

	public HubReturns hubReturns() {
		return hubReturns;
	}
}
