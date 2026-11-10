package com.xiyuan.plugmc.tpa;

import org.bukkit.plugin.java.JavaPlugin;

public final class TpaPlugin extends JavaPlugin {
	private TpaManager manager;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		manager = new TpaManager(this, TpaConfig.load(getConfig()));
		TpaGui gui = new TpaGui(manager);
		TpaCommand command = new TpaCommand(this, manager, gui);
		for (String name : new String[]{"tpa", "tpahere", "tpaccept", "tpadeny", "tpcancel"}) {
			var pluginCommand = getCommand(name);
			if (pluginCommand == null) {
				getLogger().warning("plugin.yml 缺少命令定义：" + name);
				continue;
			}
			pluginCommand.setExecutor(command);
			pluginCommand.setTabCompleter(command);
		}
		getServer().getPluginManager().registerEvents(manager, this);
		getServer().getPluginManager().registerEvents(gui, this);
		getLogger().info("申请传送 已启用（热身 " + manager.config().warmupSeconds()
			+ " 秒，冷却 " + manager.config().cooldownSeconds() + " 秒）");
	}

	@Override
	public void onDisable() {
		manager = null;
		getLogger().info("申请传送 已停用");
	}
}
