package com.xiyuan.plugmc.economy.market;

import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;

public record MarketConfig(
	boolean enabled,
	int slots,
	int durationHours,
	double listingFeeRate,
	BigDecimal listingFeeMin,
	double taxRate
) {
	public MarketConfig {
		slots = Math.max(0, Math.min(100, slots));
		durationHours = Math.max(1, Math.min(720, durationHours));
		listingFeeRate = clampRate(listingFeeRate);
		taxRate = clampRate(taxRate);
		if (listingFeeMin == null || listingFeeMin.signum() < 0) {
			throw new IllegalArgumentException("market.listing-fee-min 必须是非负金额");
		}
	}

	public static MarketConfig load(FileConfiguration config, int decimalPlaces) {
		BigDecimal feeMin = Money.parse(
			config.getString("market.listing-fee-min", "5.00"),
			decimalPlaces,
			true
		);
		return new MarketConfig(
			config.getBoolean("market.enabled", true),
			config.getInt("market.slots", 8),
			config.getInt("market.duration-hours", 168),
			config.getDouble("market.listing-fee-rate", 0.01),
			feeMin,
			config.getDouble("market.tax-rate", 0.03)
		);
	}

	public BigDecimal listingFee(BigDecimal total, int decimalPlaces) {
		BigDecimal rateFee = total.multiply(BigDecimal.valueOf(listingFeeRate));
		BigDecimal fee = rateFee.compareTo(listingFeeMin) < 0 ? listingFeeMin : rateFee;
		return Money.ceil(fee, decimalPlaces);
	}

	public BigDecimal tax(BigDecimal total, int decimalPlaces) {
		return Money.ceil(total.multiply(BigDecimal.valueOf(taxRate)), decimalPlaces);
	}

	private static double clampRate(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}
