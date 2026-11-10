package com.xiyuan.plugmc.economy.shop;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopConfigTest {
	@Test
	void itemLimitOverridesGlobalLimit() {
		ShopConfig config = new ShopConfig(true, 256, 128, false, 0.03);
		assertEquals(256, config.effectiveBuyLimit(0));
		assertEquals(64, config.effectiveBuyLimit(64));
		assertEquals(128, config.effectiveSellLimit(0));
		assertEquals(16, config.effectiveSellLimit(16));
	}

	@Test
	void negativeLimitsAreClamped() {
		ShopConfig config = new ShopConfig(true, -1, -2, false, 0.03);
		assertEquals(0, config.dailyBuyLimit());
		assertEquals(0, config.dailySellLimit());
		assertEquals(0, config.effectiveBuyLimit(0));
	}

	@Test
	void taxIsPercentOfTotalRoundedUp() {
		ShopConfig config = new ShopConfig(true, 256, 128, false, 0.03);
		assertEquals(new BigDecimal("3.00"), config.tax(new BigDecimal("100.00"), 2));
		assertEquals(new BigDecimal("0.06"), config.tax(new BigDecimal("2.00"), 2));
		assertEquals(3, config.taxPercent());
	}
}
