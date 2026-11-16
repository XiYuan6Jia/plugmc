package com.xiyuan.plugmc.megastructures;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * 星际巨构：开机时创建（或加载）虚空维度 mega，区块生成时自动摆出
 * 轨道环、核心尖塔、戴森球、太空电梯与星际之门，提供 /mega 传送与观景辅助。
 */
public final class MegaPlugin extends JavaPlugin {
	private MegaConfig settings;
	private MegaWorld megaWorld;
	private MegaReturns returns;
	private MegaManager manager;
	private MegaComfort comfort;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		reloadSettings();

		megaWorld = new MegaWorld(this);
		if (!megaWorld.load(settings)) {
			getLogger().log(Level.SEVERE, "巨构维度 " + settings.worldName() + " 创建失败，插件将被禁用");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		returns = new MegaReturns(getDataFolder(), getLogger());
		returns.load();
		manager = new MegaManager(this, returns);
		comfort = new MegaComfort(this);
		getServer().getPluginManager().registerEvents(new MegaListener(this), this);

		PluginCommand command = getCommand("mega");
		if (command != null) {
			MegaCommand executor = new MegaCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		} else {
			getLogger().warning("命令 mega 未在 plugin.yml 中注册");
		}

		getLogger().info("星际巨构 已启用（维度 " + settings.worldName()
			+ "，观景点 " + MegaStructures.viewpoints().size() + " 个）");
	}

	@Override
	public void onDisable() {
		if (comfort != null) {
			comfort.releaseAll();
		}
		getLogger().info("星际巨构 已停用");
	}

	public void reloadSettings() {
		this.settings = MegaConfig.load(getConfig());
	}

	public MegaConfig settings() {
		return settings;
	}

	public MegaWorld megaWorld() {
		return megaWorld;
	}

	public MegaManager manager() {
		return manager;
	}

	public MegaComfort comfort() {
		return comfort;
	}
}
