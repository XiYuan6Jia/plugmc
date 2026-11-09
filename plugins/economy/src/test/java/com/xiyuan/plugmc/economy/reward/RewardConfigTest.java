package com.xiyuan.plugmc.economy.reward;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RewardConfigTest {
	private static RewardConfig config() {
		return new RewardConfig(
			true,
			true,
			new BigDecimal("100.00"),
			new BigDecimal("20.00"),
			7,
			true,
			10,
			new BigDecimal("20.00"),
			6
		);
	}

	@Test
	void dailyAmountGrowsWithStreakAndCaps() {
		RewardConfig config = config();
		assertEquals(new BigDecimal("100.00"), config.dailyAmount(1, 2));
		assertEquals(new BigDecimal("120.00"), config.dailyAmount(2, 2));
		assertEquals(new BigDecimal("220.00"), config.dailyAmount(7, 2));
		assertEquals(new BigDecimal("220.00"), config.dailyAmount(30, 2));
	}

	@Test
	void streakBelowOneCountsAsFirstDay() {
		RewardConfig config = config();
		assertEquals(new BigDecimal("100.00"), config.dailyAmount(0, 2));
	}

	@Test
	void playtimeDailyCapMultipliesAmount() {
		RewardConfig config = config();
		assertEquals(new BigDecimal("120.00"), config.playtimeDailyCap(2));
	}

	@Test
	void clampsExtremes() {
		RewardConfig config = new RewardConfig(
			true,
			true,
			BigDecimal.TEN,
			BigDecimal.ZERO,
			0,
			true,
			0,
			BigDecimal.TEN,
			-3
		);
		assertEquals(1, config.streakMaxDays());
		assertEquals(1, config.playtimeMinutes());
		assertEquals(0, config.playtimeMaxPerDay());
	}

	@Test
	void rejectsNegativeAmounts() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new RewardConfig(true, true, new BigDecimal("-1"), BigDecimal.ZERO, 7, true, 10, BigDecimal.TEN, 1)
		);
	}
}
