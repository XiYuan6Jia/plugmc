package com.xiyuan.plugmc.cosmetic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 装备外观：把任意物品挂到玩家身上显示，不改客户端、不用资源包。
 *
 * <p>整体结构：</p>
 * <ul>
 *   <li>{@link CosmeticWardrobe} —— 谁身上挂了什么（物品字节序列化落盘）</li>
 *   <li>{@link CosmeticDisplays} —— 每个槽位一个 {@code ItemDisplay}，靠位置同步贴身</li>
 *   <li>{@link CosmeticAttributes} —— 槽内物品的 attribute_modifiers 透传到玩家</li>
 *   <li>{@link CosmeticGui} —— 放进/取下界面</li>
 *   <li>{@link CosmeticListener} —— 事件与生命周期清理</li>
 * </ul>
 *
 * <p>为什么不挂乘客：{@code Entity#canAddPassenger} 是 {@code passengers.isEmpty()}，
 * 一名玩家只能有 1 个直接乘客，而 ride 插件已经占了这个位置。</p>
 */
public final class CosmeticPlugin extends JavaPlugin {
	private static CosmeticPlugin instance;

	private CosmeticConfig config;
	private CosmeticWardrobe wardrobe;
	private CosmeticDisplays displays;
	private CosmeticAttributes attributes;
	private CosmeticGui gui;

	private BukkitTask syncTask;
	private long tick;
	/** 本轮需要强制刷新的玩家（登录/重生/换世界后）。 */
	private final Set<UUID> pendingRefresh = new HashSet<>();

	public static CosmeticPlugin instance() {
		return instance;
	}

	@Override
	public void onEnable() {
		instance = this;
		saveDefaultConfig();
		config = CosmeticConfig.load(this);
		if (!config.enabled) {
			getLogger().warning("配置里 enabled=false，装备外观不会生效");
			return;
		}
		wardrobe = new CosmeticWardrobe(this);
		attributes = new CosmeticAttributes(this, config, wardrobe);
		displays = new CosmeticDisplays(this, config, wardrobe);
		gui = new CosmeticGui(config, wardrobe);

		displays.sweepOrphans();

		getServer().getPluginManager().registerEvents(new CosmeticListener(this), this);
		getServer().getPluginManager().registerEvents(new CosmeticBlockUseListener(this, config), this);

		CosmeticCommand command = new CosmeticCommand(this);
		var pluginCommand = getCommand("cosmetic");
		if (pluginCommand != null) {
			pluginCommand.setExecutor(command);
			pluginCommand.setTabCompleter(command);
		}

		for (Player player : Bukkit.getOnlinePlayers()) {
			attributes.refresh(player);
			pendingRefresh.add(player.getUniqueId());
		}

		syncTask = Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 1L, config.syncIntervalTicks);
		getLogger().info("装备外观已启用：" + config.slots.size() + " 个部位，"
			+ (config.attributesEnabled ? "属性加成开" : "属性加成关")
			+ "，同步间隔 " + config.syncIntervalTicks + " tick");
	}

	@Override
	public void onDisable() {
		if (syncTask != null) {
			syncTask.cancel();
			syncTask = null;
		}
		if (displays != null) {
			displays.shutdown();
		}
		if (attributes != null) {
			for (Player player : Bukkit.getOnlinePlayers()) {
				attributes.strip(player);
			}
		}
		if (wardrobe != null) {
			wardrobe.save();
		}
		instance = null;
		getLogger().info("装备外观已停用");
	}

	/** 重新读配置（命令 /cosmetic reload 用）。槽位定义的变更需要重建全部实体。 */
	void reload() {
		reloadConfig();
		config = CosmeticConfig.load(this);
		if (displays != null) {
			displays.shutdown();
		}
		wardrobe = new CosmeticWardrobe(this);
		attributes = new CosmeticAttributes(this, config, wardrobe);
		displays = new CosmeticDisplays(this, config, wardrobe);
		gui = new CosmeticGui(config, wardrobe);
		if (syncTask != null) {
			syncTask.cancel();
		}
		syncTask = Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 1L, config.syncIntervalTicks);
		for (Player player : Bukkit.getOnlinePlayers()) {
			attributes.refresh(player);
			pendingRefresh.add(player.getUniqueId());
		}
	}

	private void tickAll() {
		tick++;
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (pendingRefresh.remove(player.getUniqueId())) {
				displays.removeAll(player.getUniqueId(), true);
			}
			displays.sync(player, tick);
		}
	}

	/** 立刻把这个玩家的装饰刷新一遍（下一 tick 生效）。 */
	void refresh(Player player) {
		pendingRefresh.add(player.getUniqueId());
	}

	/** 重生/换世界后延迟一 tick 重建。 */
	void runSyncLater(Player player) {
		pendingRefresh.add(player.getUniqueId());
		Bukkit.getScheduler().runTask(this, () -> {
			if (player.isOnline()) {
				displays.sync(player, tick);
			}
		});
	}

	CosmeticConfig config() {
		return config;
	}

	CosmeticWardrobe wardrobe() {
		return wardrobe;
	}

	CosmeticDisplays displayDisplays() {
		return displays;
	}

	CosmeticAttributes attributes() {
		return attributes;
	}

	CosmeticGui gui() {
		return gui;
	}

	long currentTick() {
		return tick;
	}
}
