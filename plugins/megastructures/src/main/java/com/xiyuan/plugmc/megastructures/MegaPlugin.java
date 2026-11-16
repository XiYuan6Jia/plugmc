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
	private MegaInteractions interactions;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		migrateConfig();
		reloadSettings();

		megaWorld = new MegaWorld(this);
		if (!megaWorld.load(settings)) {
			getLogger().log(Level.SEVERE, "巨构维度 " + settings.worldKey()
				+ " 不可用；请确认 datapacks/plugmc-mega 已安装并启用，插件将被禁用");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		returns = new MegaReturns(getDataFolder(), getLogger());
		returns.load();
		manager = new MegaManager(this, returns);
		comfort = new MegaComfort(this);
		interactions = new MegaInteractions(this);
		getServer().getPluginManager().registerEvents(new MegaListener(this), this);
		getServer().getScheduler().runTask(this, interactions::ensureAllLoaded);

		PluginCommand command = getCommand("mega");
		if (command != null) {
			MegaCommand executor = new MegaCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		} else {
			getLogger().warning("命令 mega 未在 plugin.yml 中注册");
		}

		getLogger().info("星际巨构 已启用（维度 " + settings.worldKey()
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

	/**
	 * 旧版配置只有 world.name，没有数据包维度键；出生点也是 384 格版本的位置。
	 * 发现旧配置时把维度键、出生点与边界迁到新高度版本，其他保护设置原样保留。
	 */
	private void migrateConfig() {
		int version = getConfig().getInt("config-version", 1);
		boolean changed = false;
		if (version < 2) {
			getConfig().set("world.key", MegaConfig.DEFAULT_WORLD_KEY);
			getConfig().set("world.border-size", 5000);
			getConfig().set("world.spawn.x", 0.5);
			getConfig().set("world.spawn.y", 420.0);
			getConfig().set("world.spawn.z", 380.5);
			getConfig().set("world.spawn.yaw", 180.0);
			getConfig().set("world.spawn.pitch", -8.0);
			getConfig().set("protect.void-y", -460);
			getConfig().set("config-version", 2);
			version = 2;
			changed = true;
		}
		if (version < 3) {
			// 旧边界 3000 会卡在戴森球(-1600)与太空电梯/星际之门(±1600)之间
			getConfig().set("world.border-size", 5000);
			getConfig().set("config-version", 3);
			changed = true;
		}
		if (changed) {
			saveConfig();
			getLogger().info("已把旧版巨构配置迁移到 2048 格高度版本（边界 5000）");
		}
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

	public MegaInteractions interactions() {
		return interactions;
	}
}
