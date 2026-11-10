package com.xiyuan.plugmc.sleepvote;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 入睡半径：以第一个上床的玩家为圆心，统计半径内玩家；
 * 半径内达到比例的玩家入睡后跳过夜晚。
 */
public final class SleepVotePlugin extends JavaPlugin implements Listener {
	static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private int radius;
	private int percentage;
	private int minPlayers;
	private boolean disableVanillaSleep;
	private boolean skipWeather;
	private String progressMessage;
	private String skippedMessage;
	private String aloneMessage;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		loadSettings();
		getServer().getPluginManager().registerEvents(new SleepListener(this), this);
		getServer().getPluginManager().registerEvents(this, this);
		if (disableVanillaSleep) {
			for (World world : Bukkit.getWorlds()) {
				applyGamerule(world);
			}
		}
		PluginCommand command = getCommand("sleepvote");
		if (command == null) {
			getLogger().warning("命令 sleepvote 未在 plugin.yml 中注册");
		} else {
			SleepVoteCommand executor = new SleepVoteCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		}
		getLogger().info("入睡半径 已启用（半径 " + radius + " 格，比例 " + percentage + "%）");
	}

	@EventHandler
	public void onWorldLoad(WorldLoadEvent event) {
		if (disableVanillaSleep) {
			applyGamerule(event.getWorld());
		}
	}

	private void applyGamerule(World world) {
		world.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, 101);
	}

	public void loadSettings() {
		reloadConfig();
		radius = Math.max(1, getConfig().getInt("radius", 100));
		percentage = Math.max(1, Math.min(100, getConfig().getInt("percentage", 50)));
		minPlayers = Math.max(1, getConfig().getInt("min-players", 1));
		disableVanillaSleep = getConfig().getBoolean("disable-vanilla-sleep", true);
		skipWeather = getConfig().getBoolean("skip-weather", true);
		progressMessage = getConfig().getString("messages.progress",
			"&e附近玩家入睡：&f{sleeping}/{required}&e（半径 {radius} 格）");
		skippedMessage = getConfig().getString("messages.skipped", "&a附近玩家已入睡，夜晚被跳过。");
		aloneMessage = getConfig().getString("messages.alone", "&7附近没有其他需要入睡的玩家。");
	}

	/**
	 * 玩家上床后由监听器延迟调用：统计半径内玩家并决定是否跳过夜晚。
	 */
	public void checkSleep(Player sleeper) {
		if (!sleeper.isOnline() || !sleeper.isSleeping()) {
			return;
		}
		List<Player> nearby = nearbyPlayers(sleeper);
		if (nearby.size() < minPlayers) {
			send(nearby, aloneMessage);
			return;
		}
		int sleeping = 0;
		for (Player other : nearby) {
			if (other.isSleeping()) {
				sleeping++;
			}
		}
		int required = required(nearby.size());
		if (sleeping >= required) {
			skipNight(sleeper.getWorld());
			send(nearby, skippedMessage);
			return;
		}
		String message = progressMessage
			.replace("{sleeping}", Integer.toString(sleeping))
			.replace("{required}", Integer.toString(required))
			.replace("{eligible}", Integer.toString(nearby.size()))
			.replace("{radius}", Integer.toString(radius));
		for (Player other : nearby) {
			other.sendActionBar(LEGACY.deserialize(message));
		}
	}

	public List<Player> nearbyPlayers(Player center) {
		World world = center.getWorld();
		double radiusSquared = (double) radius * radius;
		List<Player> nearby = new ArrayList<>();
		for (Player other : world.getPlayers()) {
			if (other.getGameMode() == GameMode.SPECTATOR) {
				continue;
			}
			if (other.getLocation().distanceSquared(center.getLocation()) > radiusSquared) {
				continue;
			}
			nearby.add(other);
		}
		return nearby;
	}

	public int[] counts(Player center) {
		List<Player> nearby = nearbyPlayers(center);
		int sleeping = 0;
		for (Player other : nearby) {
			if (other.isSleeping()) {
				sleeping++;
			}
		}
		return new int[]{nearby.size(), sleeping, required(nearby.size())};
	}

	private int required(int eligible) {
		return Math.max(1, (int) Math.ceil(eligible * (percentage / 100.0)));
	}

	private void skipNight(World world) {
		world.setTime(0L);
		if (skipWeather) {
			world.setStorm(false);
			world.setThundering(false);
		}
		for (Player player : new ArrayList<>(world.getPlayers())) {
			if (player.isSleeping()) {
				player.wakeup(true);
			}
		}
	}

	private static void send(List<Player> players, String message) {
		for (Player player : players) {
			player.sendMessage(LEGACY.deserialize(message));
		}
	}

	public int radius() {
		return radius;
	}

	public int percentage() {
		return percentage;
	}

	public int minPlayers() {
		return minPlayers;
	}
}
