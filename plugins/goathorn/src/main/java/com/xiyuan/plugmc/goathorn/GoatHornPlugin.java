package com.xiyuan.plugmc.goathorn;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * 山羊角宠物哨：手持山羊角 shift 右键驯服的动物绑定，吹奏召回。
 */
public final class GoatHornPlugin extends JavaPlugin {
	private HornConfig settings;
	private HornBindings bindings;
	private HornService service;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		reloadSettings();
		bindings = new HornBindings(this);
		service = new HornService(this, bindings);
		getServer().getPluginManager().registerEvents(new HornListener(this), this);
		HornCommand command = new HornCommand(this);
		if (getCommand("goathorn") != null) {
			getCommand("goathorn").setExecutor(command);
			getCommand("goathorn").setTabCompleter(command);
		}
		getLogger().info("山羊角宠物哨 已启用（冷却 " + settings.cooldownSeconds()
			+ " 秒，每只羊角上限 " + (settings.maxAnimalsPerHorn() <= 0 ? "无限" : settings.maxAnimalsPerHorn() + " 只") + "）");
	}

	@Override
	public void onDisable() {
		getLogger().info("山羊角宠物哨 已关闭");
	}

	public void reloadSettings() {
		reloadConfig();
		settings = HornConfig.load(getConfig());
	}

	HornConfig settings() {
		return settings;
	}

	HornBindings bindings() {
		return bindings;
	}

	HornService service() {
		return service;
	}
}
