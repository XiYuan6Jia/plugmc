package com.xiyuan.plugmc.economy.internal;

import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 经济插件的启动配置。只保留 P0 需要的字段，后续模块再增量扩展。
 */
public record EconomyConfig(
	String currencyName,
	String currencySymbol,
	int decimalPlaces,
	BigDecimal startingBalance,
	int snapshotIntervalSeconds,
	int backupCount
) {
	public EconomyConfig {
		currencyName = Objects.requireNonNullElse(currencyName, "金币");
		currencySymbol = Objects.requireNonNullElse(currencySymbol, "$");
		if (decimalPlaces < 0 || decimalPlaces > 6) {
			throw new IllegalArgumentException("currency.decimal-places 必须在 0 到 6 之间");
		}
		startingBalance = Money.nonNegative(startingBalance, decimalPlaces);
		snapshotIntervalSeconds = clamp(snapshotIntervalSeconds, 10, 3600);
		backupCount = clamp(backupCount, 1, 100);
	}

	public static EconomyConfig load(FileConfiguration config) {
		int decimalPlaces = config.getInt("currency.decimal-places", 2);
		if (decimalPlaces < 0 || decimalPlaces > 6) {
			throw new IllegalStateException("currency.decimal-places 必须在 0 到 6 之间");
		}
		BigDecimal startingBalance = Money.parse(
			config.getString("starting-balance", "100.00"),
			decimalPlaces,
			true
		);
		return new EconomyConfig(
			config.getString("currency.name", "金币"),
			config.getString("currency.symbol", "$"),
			decimalPlaces,
			startingBalance,
			config.getInt("storage.snapshot-interval-seconds", 300),
			config.getInt("storage.backup-count", 14)
		);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
