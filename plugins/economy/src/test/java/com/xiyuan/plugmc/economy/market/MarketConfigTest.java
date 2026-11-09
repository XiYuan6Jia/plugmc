package com.xiyuan.plugmc.economy.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketConfigTest {
	@Test
	void listingFeeUsesMinimumAndRate() {
		MarketConfig config = new MarketConfig(
			true,
			8,
			168,
			0.01,
			new BigDecimal("5.00"),
			0.03
		);
		assertEquals(new BigDecimal("5.00"), config.listingFee(new BigDecimal("100.00"), 2));
		assertEquals(new BigDecimal("10.00"), config.listingFee(new BigDecimal("1000.00"), 2));
	}

	@Test
	void taxRoundsUpToCurrencyScale() {
		MarketConfig config = new MarketConfig(
			true,
			8,
			168,
			0.01,
			new BigDecimal("5.00"),
			0.03
		);
		assertEquals(new BigDecimal("3.00"), config.tax(new BigDecimal("100.00"), 2));
		assertEquals(new BigDecimal("0.01"), config.tax(new BigDecimal("0.01"), 2));
	}
}
