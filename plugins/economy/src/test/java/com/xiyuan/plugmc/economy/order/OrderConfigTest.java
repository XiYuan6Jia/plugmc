package com.xiyuan.plugmc.economy.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderConfigTest {
	@Test
	void listingFeeUsesMinimumAndRate() {
		OrderConfig config = new OrderConfig(true, 8, 168, 0.01, new BigDecimal("2.00"), 0.03);
		assertEquals(new BigDecimal("2.00"), config.listingFee(new BigDecimal("100.00"), 2));
		assertEquals(new BigDecimal("10.00"), config.listingFee(new BigDecimal("1000.00"), 2));
	}

	@Test
	void taxRoundsUpToCurrencyScale() {
		OrderConfig config = new OrderConfig(true, 8, 168, 0.01, new BigDecimal("2.00"), 0.03);
		assertEquals(new BigDecimal("3.00"), config.tax(new BigDecimal("100.00"), 2));
		assertEquals(new BigDecimal("0.01"), config.tax(new BigDecimal("0.01"), 2));
	}

	@Test
	void rejectsNegativeFeeMinimum() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new OrderConfig(true, 8, 168, 0.01, new BigDecimal("-1.00"), 0.03)
		);
	}
}
