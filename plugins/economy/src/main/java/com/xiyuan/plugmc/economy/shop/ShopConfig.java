package com.xiyuan.plugmc.economy.shop;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 系统商店配置。0 表示不限制。
 */
public record ShopConfig(
	boolean enabled,
	int dailyBuyLimit,
	int dailySellLimit,
	boolean allowCustomItems
) {
	public ShopConfig {
		dailyBuyLimit = Math.max(0, dailyBuyLimit);
		dailySellLimit = Math.max(0, dailySellLimit);
	}

	public static ShopConfig load(FileConfiguration config) {
		return new ShopConfig(
			config.getBoolean("shop.enabled", true),
			config.getInt("shop.daily-buy-limit", 256),
			config.getInt("shop.daily-sell-limit", 256),
			config.getBoolean("shop.allow-custom-items", false)
		);
	}

	public int effectiveBuyLimit(int itemLimit) {
		return itemLimit > 0 ? itemLimit : dailyBuyLimit;
	}

	public int effectiveSellLimit(int itemLimit) {
		return itemLimit > 0 ? itemLimit : dailySellLimit;
	}
}
