package com.xiyuan.plugmc.economy.auction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuctionConfigTest {
	@Test
	void taxRoundsUpToCurrencyScale() {
		AuctionConfig config = new AuctionConfig(true, 5, 48, new BigDecimal("1.00"), 0.03);
		assertEquals(new BigDecimal("3.00"), config.tax(new BigDecimal("100.00"), 2));
		assertEquals(new BigDecimal("0.01"), config.tax(new BigDecimal("0.01"), 2));
		assertEquals(new BigDecimal("0.00"), config.tax(new BigDecimal("0.00"), 2));
	}

	@Test
	void clampsDurationSlotsAndTaxRate() {
		AuctionConfig config = new AuctionConfig(true, 999, 0, new BigDecimal("1.00"), 5.0);
		assertEquals(100, config.slots());
		assertEquals(1, config.durationHours());
		assertEquals(1.0, config.taxRate());
	}

	@Test
	void rejectsNonPositiveMinIncrement() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new AuctionConfig(true, 5, 48, BigDecimal.ZERO, 0.03)
		);
	}
}
