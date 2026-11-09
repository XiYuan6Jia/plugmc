package com.xiyuan.plugmc.economy.auction;

import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;

public record AuctionConfig(
	boolean enabled,
	int slots,
	int durationHours,
	BigDecimal minIncrement,
	double taxRate
) {
	public AuctionConfig {
		slots = Math.max(0, Math.min(100, slots));
		durationHours = Math.max(1, Math.min(720, durationHours));
		taxRate = Math.max(0.0, Math.min(1.0, taxRate));
		if (minIncrement == null || minIncrement.signum() <= 0) {
			throw new IllegalArgumentException("auction.min-increment 必须是正金额");
		}
	}

	public static AuctionConfig load(FileConfiguration config, int decimalPlaces) {
		return new AuctionConfig(
			config.getBoolean("auction.enabled", true),
			config.getInt("auction.slots", 5),
			config.getInt("auction.duration-hours", 48),
			Money.parse(config.getString("auction.min-increment", "1.00"), decimalPlaces, false),
			config.getDouble("auction.tax-rate", 0.03)
		);
	}

	public BigDecimal tax(BigDecimal total, int decimalPlaces) {
		return Money.ceil(total.multiply(BigDecimal.valueOf(taxRate)), decimalPlaces);
	}
}
