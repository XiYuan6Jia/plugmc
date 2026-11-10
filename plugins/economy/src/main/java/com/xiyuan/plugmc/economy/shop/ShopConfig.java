package com.xiyuan.plugmc.economy.shop;

import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;

/**
 * 系统商店配置。限额 0 表示不限制；交易税按成交总额百分比收取。
 */
public record ShopConfig(
	boolean enabled,
	int dailyBuyLimit,
	int dailySellLimit,
	boolean allowCustomItems,
	double taxRate
) {
	public ShopConfig {
		dailyBuyLimit = Math.max(0, dailyBuyLimit);
		dailySellLimit = Math.max(0, dailySellLimit);
		taxRate = clampRate(taxRate);
	}

	public static ShopConfig load(FileConfiguration config) {
		return new ShopConfig(
			config.getBoolean("shop.enabled", true),
			config.getInt("shop.daily-buy-limit", 256),
			config.getInt("shop.daily-sell-limit", 256),
			config.getBoolean("shop.allow-custom-items", false),
			config.getDouble("shop.tax-rate", 0.03)
		);
	}

	public int effectiveBuyLimit(int itemLimit) {
		return itemLimit > 0 ? itemLimit : dailyBuyLimit;
	}

	public int effectiveSellLimit(int itemLimit) {
		return itemLimit > 0 ? itemLimit : dailySellLimit;
	}

	public BigDecimal tax(BigDecimal total, int decimalPlaces) {
		return Money.ceil(total.multiply(BigDecimal.valueOf(taxRate)), decimalPlaces);
	}

	public int taxPercent() {
		return (int) Math.round(taxRate * 100);
	}

	private static double clampRate(double rate) {
		return Math.max(0.0, Math.min(1.0, rate));
	}
}
