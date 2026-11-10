package com.xiyuan.plugmc.minigames;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 主城小游戏插件：21 点、幸运转盘、老虎机，主城大厅北侧三个实体摊位。
 * 资金全部走 economy 的 {@code EconomyService}，投注/派彩在账本里按 GAME_* 原因区分。
 */
public final class MinigamesPlugin extends JavaPlugin {
	private GamesConfig settings;
	private EconomyBridge economy;
	private GamesGui gui;
	private VenueBuilder venue;
	private final Map<UUID, Long> lastPlayed = new HashMap<>();

	@Override
	public void onEnable() {
		saveDefaultConfig();
		reloadSettings();

		economy = new EconomyBridge(this);
		economy.refresh();
		gui = new GamesGui(this, economy);
		venue = new VenueBuilder(this);
		getServer().getPluginManager().registerEvents(new GamesListener(this, gui), this);

		PluginCommand command = getCommand("games");
		if (command != null) {
			GamesCommand executor = new GamesCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		} else {
			getLogger().warning("命令 games 未在 plugin.yml 中注册");
		}

		venue.build(false);
		// hub 维度可能晚于本插件创建，延迟一拍再兜底建一次实体店
		getServer().getScheduler().runTask(this, () -> {
			if (venue.venueCount() == 0) {
				venue.build(false);
			}
		});
		getLogger().info("小游戏 已启用（21点 / 转盘 / 老虎机，注额 "
			+ settings.minBet() + " ~ " + settings.maxBet() + "）");
	}

	@Override
	public void onDisable() {
		getLogger().info("小游戏 已停用");
	}

	public void reloadSettings() {
		this.settings = GamesConfig.load(getConfig());
	}

	public GamesConfig settings() {
		return settings;
	}

	public EconomyBridge economy() {
		return economy;
	}

	public GamesGui gui() {
		return gui;
	}

	public VenueBuilder venue() {
		return venue;
	}

	/** 冷却检查；返回 true 表示可以继续，false 表示还在冷却并已提示玩家。 */
	public boolean checkCooldown(Player player, int seconds) {
		if (seconds <= 0) {
			return true;
		}
		long now = System.currentTimeMillis();
		Long last = lastPlayed.get(player.getUniqueId());
		if (last != null && now - last < seconds * 1000L) {
			long left = (seconds * 1000L - (now - last) + 999) / 1000L;
			player.sendMessage(Text.of("&7别急，&f" + left + " &7秒后再来。"));
			return false;
		}
		lastPlayed.put(player.getUniqueId(), now);
		return true;
	}
}
