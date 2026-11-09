package com.xiyuan.plugmc.economy.reward;

import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;

/**
 * 奖励配置：每日签到与在线奖励。奖励属于货币投放，因此全部带日上限。
 */
public record RewardConfig(
	boolean enabled,
	boolean dailyEnabled,
	BigDecimal dailyBase,
	BigDecimal streakBonus,
	int streakMaxDays,
	boolean playtimeEnabled,
	int playtimeMinutes,
	BigDecimal playtimeAmount,
	int playtimeMaxPerDay
) {
	public RewardConfig {
		if (dailyBase == null || dailyBase.signum() < 0) {
			throw new IllegalArgumentException("rewards.daily.base 必须是非负金额");
		}
		if (streakBonus == null || streakBonus.signum() < 0) {
			throw new IllegalArgumentException("rewards.daily.streak-bonus 必须是非负金额");
		}
		if (playtimeAmount == null || playtimeAmount.signum() < 0) {
			throw new IllegalArgumentException("rewards.playtime.amount 必须是非负金额");
		}
		streakMaxDays = Math.max(1, Math.min(365, streakMaxDays));
		playtimeMinutes = Math.max(1, Math.min(600, playtimeMinutes));
		playtimeMaxPerDay = Math.max(0, Math.min(100, playtimeMaxPerDay));
	}

	public static RewardConfig load(FileConfiguration config, int decimalPlaces) {
		return new RewardConfig(
			config.getBoolean("rewards.enabled", true),
			config.getBoolean("rewards.daily.enabled", true),
			Money.parse(config.getString("rewards.daily.base", "100.00"), decimalPlaces, true),
			Money.parse(config.getString("rewards.daily.streak-bonus", "20.00"), decimalPlaces, true),
			config.getInt("rewards.daily.streak-max-days", 7),
			config.getBoolean("rewards.playtime.enabled", true),
			config.getInt("rewards.playtime.minutes", 10),
			Money.parse(config.getString("rewards.playtime.amount", "20.00"), decimalPlaces, true),
			config.getInt("rewards.playtime.max-per-day", 6)
		);
	}

	/**
	 * 连签第 N 天（从 1 开始）的签到奖励，最后一档封顶。
	 */
	public BigDecimal dailyAmount(int streak, int decimalPlaces) {
		int capped = Math.min(Math.max(1, streak), streakMaxDays);
		BigDecimal amount = dailyBase.add(streakBonus.multiply(BigDecimal.valueOf(capped - 1L)));
		return Money.ceil(amount, decimalPlaces);
	}

	public BigDecimal playtimeDailyCap(int decimalPlaces) {
		return Money.ceil(playtimeAmount.multiply(BigDecimal.valueOf(playtimeMaxPerDay)), decimalPlaces);
	}
}
