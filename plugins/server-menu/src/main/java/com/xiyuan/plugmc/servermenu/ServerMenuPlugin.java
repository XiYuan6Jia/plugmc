package com.xiyuan.plugmc.servermenu;

import com.xiyuan.plugmc.economy.api.EconomyAuditService;
import com.xiyuan.plugmc.economy.api.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ServerMenuPlugin extends JavaPlugin {
	private EconomyService economy;
	private MenuManager menuManager;
	private NamespacedKey hudPreferenceKey;
	private BukkitTask hudTask;
	private final Map<UUID, Scoreboard> originalScoreboards = new HashMap<>();
	private final Map<UUID, Scoreboard> hudScoreboards = new HashMap<>();

	@Override
	public void onEnable() {
		saveDefaultConfig();
		economy = Bukkit.getServicesManager().load(EconomyService.class);
		if (economy == null) {
			throw new IllegalStateException("EconomyService 未注册；请确认 economy 插件已启用");
		}

		hudPreferenceKey = new NamespacedKey(this, "money_hud_enabled");
		menuManager = new MenuManager(this, economy);
		menuManager.reload();
		getServer().getPluginManager().registerEvents(new MenuListener(this, menuManager), this);
		MenuCommand command = new MenuCommand(this, menuManager);
		getCommand("menu").setExecutor(command);
		getCommand("moneyhud").setExecutor(command);
		Runnable hudUpdater = this::updateHud;
		hudTask = getServer().getScheduler().runTaskTimer(this, hudUpdater, 1L, 20L);
		getLogger().info("测试服菜单 已启用");
	}

	@Override
	public void onDisable() {
		if (hudTask != null) {
			hudTask.cancel();
		}
		for (Player player : Bukkit.getOnlinePlayers()) {
			restoreScoreboard(player);
		}
	}

	EconomyService getEconomy() {
		return economy;
	}

	EconomyAuditService auditService() {
		EconomyAuditService audit = Bukkit.getServicesManager().load(EconomyAuditService.class);
		return audit;
	}

	boolean isHudEnabled(Player player) {
		Byte preference = player.getPersistentDataContainer().get(hudPreferenceKey, PersistentDataType.BYTE);
		return preference == null ? getConfig().getBoolean("hud.enabled-by-default", true) : preference != 0;
	}

	void setHudEnabled(Player player, boolean enabled) {
		player.getPersistentDataContainer().set(hudPreferenceKey, PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
		updateHud(player);
	}

	void toggleHud(Player player) {
		setHudEnabled(player, !isHudEnabled(player));
	}

	void handleJoin(Player player) {
		getServer().getScheduler().runTask(this, () -> updateHud(player));
	}

	void handleQuit(Player player) {
		restoreScoreboard(player);
	}

	void refreshHud() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			restoreScoreboard(player);
			updateHud(player);
		}
	}

	private void updateHud() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			updateHud(player);
		}
	}

	private void updateHud(Player player) {
		if (!isHudEnabled(player)) {
			restoreScoreboard(player);
			return;
		}

		UUID playerId = player.getUniqueId();
		Scoreboard scoreboard = hudScoreboards.get(playerId);
		if (scoreboard == null) {
			scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
			Objective objective = scoreboard.registerNewObjective(
				"plugmcmoney",
				Criteria.DUMMY,
				MenuText.component(getConfig().getString("hud.title", "&6&l经济"))
			);
			objective.setDisplaySlot(DisplaySlot.SIDEBAR);
			objective.getScore("\u00a70").setScore(2);
			objective.getScore("\u00a71").setScore(1);

			var currencyTeam = scoreboard.registerNewTeam("plugmoney1");
			currencyTeam.addEntry("\u00a70");
			currencyTeam.prefix(MenuText.component(getConfig().getString("hud.balance-label", "&7余额")));
			var balanceTeam = scoreboard.registerNewTeam("plugmoney2");
			balanceTeam.addEntry("\u00a71");
			hudScoreboards.put(playerId, scoreboard);
		}
		if (player.getScoreboard() != scoreboard) {
			originalScoreboards.put(playerId, player.getScoreboard());
			player.setScoreboard(scoreboard);
		}

		String balance = economy.format(economy.getBalance(playerId));
		scoreboard.getTeam("plugmoney2").prefix(MenuText.component("&e" + balance));
	}

	private void restoreScoreboard(Player player) {
		UUID playerId = player.getUniqueId();
		Scoreboard hudScoreboard = hudScoreboards.remove(playerId);
		Scoreboard original = originalScoreboards.remove(playerId);
		if (hudScoreboard != null && original != null && player.getScoreboard() == hudScoreboard) {
			player.setScoreboard(original);
		}
	}
}
