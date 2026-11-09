package com.xiyuan.plugmc.economy.reward;

import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.JournalRecord;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每日签到与在线奖励。所有发放都走账本事务，并与签到记录一起原子提交。
 */
public final class RewardService {
	private final JavaPlugin plugin;
	private final LedgerService ledger;
	private final EconomyStore store;
	private volatile RewardConfig config;
	private final Map<UUID, Long> onlineSeconds = new HashMap<>();

	public RewardService(JavaPlugin plugin, LedgerService ledger, EconomyStore store, RewardConfig config) {
		this.plugin = plugin;
		this.ledger = ledger;
		this.store = store;
		this.config = config;
	}

	public RewardConfig config() {
		return config;
	}

	public void reload() {
		plugin.reloadConfig();
		this.config = RewardConfig.load(plugin.getConfig(), ledger.decimalPlaces());
		plugin.getLogger().info("奖励配置已重载：签到 " + config.dailyBase().toPlainString()
			+ "，在线每 " + config.playtimeMinutes() + " 分钟 "
			+ config.playtimeAmount().toPlainString());
	}

	/**
	 * 每秒检查在线时长，达标后发放在线奖励；发放次数受每日上限限制。
	 */
	public BukkitTask startTask() {
		return plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
	}

	public boolean claimDaily(Player player) {
		if (!config.enabled() || !config.dailyEnabled()) {
			player.sendMessage("签到奖励暂未开放。");
			return false;
		}
		if (!player.hasPermission("economy.reward.daily")) {
			player.sendMessage("你没有权限领取签到奖励。");
			return false;
		}

		String today = LocalDate.now().toString();
		if (today.equals(store.lastDailyDate(player.getUniqueId()))) {
			player.sendMessage("今天已经签到过了，明天再来吧。");
			return false;
		}
		String yesterday = LocalDate.now().minusDays(1L).toString();
		int streak = yesterday.equals(store.lastDailyDate(player.getUniqueId()))
			? store.dailyStreak(player.getUniqueId()) + 1
			: 1;

		BigDecimal amount = config.dailyAmount(streak, ledger.decimalPlaces());
		if (amount.signum() <= 0) {
			player.sendMessage("今日签到奖励为 0，请联系管理员检查配置。");
			return false;
		}

		boolean committed = ledger.commitEntries(
			List.of(
				new JournalRecord.Entry(LedgerService.MINT, amount.negate()),
				new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), amount)
			),
			List.of(new JournalRecord.StateOp(
				"PLAYER_DAILY",
				player.getUniqueId().toString(),
				streak + "|" + today
			)),
			"REWARD_DAILY",
			today
		);
		if (!committed) {
			player.sendMessage("签到失败，请稍后再试。");
			return false;
		}

		player.sendMessage("签到成功：连续 " + streak + " 天，获得 " + ledger.format(amount)
			+ "，余额 " + ledger.format(ledger.balance(player.getUniqueId())) + "。");
		return true;
	}

	public int todayDailyStreak(Player player) {
		String today = LocalDate.now().toString();
		return today.equals(store.lastDailyDate(player.getUniqueId()))
			? store.dailyStreak(player.getUniqueId())
			: 0;
	}

	private void tick() {
		if (!config.enabled() || !config.playtimeEnabled() || config.playtimeMaxPerDay() == 0) {
			onlineSeconds.clear();
			return;
		}
		int interval = config.playtimeMinutes() * 60;
		String today = LocalDate.now().toString();
		List<Player> online = List.copyOf(plugin.getServer().getOnlinePlayers());
		onlineSeconds.keySet().retainAll(online.stream().map(Player::getUniqueId).toList());

		for (Player player : online) {
			long seconds = onlineSeconds.merge(player.getUniqueId(), 1L, Long::sum);
			if (seconds < interval) {
				continue;
			}
			onlineSeconds.put(player.getUniqueId(), seconds - interval);
			awardPlaytime(player, today);
		}
	}

	private void awardPlaytime(Player player, String today) {
		String key = player.getUniqueId() + "|" + today + "|reward|playtime";
		if (store.dailyUsed(key) >= config.playtimeMaxPerDay()) {
			return;
		}
		BigDecimal amount = config.playtimeAmount();
		if (amount.signum() <= 0) {
			return;
		}
		if (!ledger.deposit(player.getUniqueId(), amount, "REWARD_PLAYTIME", today)) {
			return;
		}
		store.addDailyUse(key, 1);
		player.sendMessage("在线奖励：游戏 " + config.playtimeMinutes() + " 分钟，获得 "
			+ ledger.format(amount) + "（今日剩余 "
			+ Math.max(0, config.playtimeMaxPerDay() - store.dailyUsed(key)) + " 次）。");
	}
}
