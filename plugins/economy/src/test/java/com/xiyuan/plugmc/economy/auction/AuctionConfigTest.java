package com.xiyuan.plugmc.economy.auction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionConfigTest {
	private static AuctionConfig config() {
		return new AuctionConfig(
			true,
			5,
			48,
			new BigDecimal("1.00"),
			0.03,
			0.05,
			60,
			5,
			0.02,
			new BigDecimal("10.00")
		);
	}

	@Test
	void taxRoundsUpToCurrencyScale() {
		AuctionConfig config = config();
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
		assertEquals(
			600,
			new AuctionConfig(true, 5, 48, BigDecimal.ONE, 0.03, 0.05, 9999, 99, 0.02, BigDecimal.TEN)
				.antiSnipeSeconds()
		);
	}

	@Test
	void rejectsNonPositiveMinIncrement() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new AuctionConfig(true, 5, 48, BigDecimal.ZERO, 0.03)
		);
	}

	@Test
	void minIncrementGrowsWithPrice() {
		AuctionConfig config = config();
		assertEquals(new BigDecimal("1.00"), config.minIncrementFor(BigDecimal.ZERO, 2));
		assertEquals(new BigDecimal("1.00"), config.minIncrementFor(new BigDecimal("10.00"), 2));
		assertEquals(new BigDecimal("5.00"), config.minIncrementFor(new BigDecimal("100.00"), 2));
		assertEquals(new BigDecimal("25.00"), config.minIncrementFor(new BigDecimal("500.00"), 2));
	}

	@Test
	void listingFeeUsesMinimumAndRate() {
		AuctionConfig config = config();
		assertEquals(new BigDecimal("10.00"), config.listingFee(new BigDecimal("100.00"), 2));
		assertEquals(new BigDecimal("20.00"), config.listingFee(new BigDecimal("1000.00"), 2));
		assertEquals(new BigDecimal("0.00"), config.listingFee(new BigDecimal("0.00"), 2));
	}

	@Test
	void antiSnipeExtensionIsBounded() {
		AuctionConfig config = config();
		long now = 1_000_000L;
		long soon = now + 30_000L;
		long later = now + 5 * 60_000L;

		assertTrue(config.shouldExtend(now, soon, 0));
		assertFalse(config.shouldExtend(now, later, 0));
		assertFalse(config.shouldExtend(now, soon, config.maxExtensions()));
		assertFalse(config.shouldExtend(now, now - 1, 0));
		assertEquals(now + 60_000L, config.extendedExpiry(now));
	}
}
