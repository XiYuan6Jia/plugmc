package com.xiyuan.plugmc.minigames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GamesMathTest {
	@Test
	void wheelWeightsSumToHundred() {
		int total = WheelGame.sectors().stream().mapToInt(WheelGame.Sector::weight).sum();
		assertEquals(100, total);
	}

	@Test
	void wheelExpectedValueIsBelowOne() {
		double expected = WheelGame.sectors().stream()
			.mapToDouble(sector -> sector.multiplier().doubleValue() * sector.weight() / 100.0)
			.sum();
		assertTrue(expected > 0.85 && expected < 1.0, "转盘返还率应在 85%~100% 之间，实际 " + expected);
	}

	@Test
	void wheelPayoutUsesMultiplier() {
		WheelGame.Sector sector = WheelGame.sectors().stream()
			.filter(item -> item.multiplier().compareTo(new BigDecimal("2")) == 0)
			.findFirst()
			.orElseThrow();
		assertEquals(new BigDecimal("20.00"), WheelGame.payout(10, sector));
	}

	@Test
	void slotsTriplePaysSymbolMultiplier() {
		int seven = -1;
		for (int index = 0; index < SlotsGame.symbols().size(); index++) {
			if (SlotsGame.symbol(index).triple().compareTo(new BigDecimal("30")) == 0) {
				seven = index;
			}
		}
		assertTrue(seven >= 0, "应存在 30 倍的符号");
		assertEquals(new BigDecimal("300.00"), SlotsGame.payout(10, new int[]{seven, seven, seven}));
	}

	@Test
	void slotsPairPaysOnePointOne() {
		int[] reels = {0, 0, 1};
		assertEquals(new BigDecimal("11.00"), SlotsGame.payout(10, reels));
	}

	@Test
	void slotsNoMatchPaysNothing() {
		assertEquals(BigDecimal.ZERO, SlotsGame.payout(10, new int[]{0, 1, 2}));
	}

	@Test
	void slotsExpectedValueIsReasonable() {
		var symbols = SlotsGame.symbols();
		double triple = 0;
		double pair = 0;
		double totalWeight = symbols.stream().mapToInt(SlotsGame.Symbol::weight).sum();
		for (var symbol : symbols) {
			double p = symbol.weight() / totalWeight;
			triple += Math.pow(p, 3) * symbol.triple().doubleValue();
			pair += 3 * Math.pow(p, 2) * (1 - p);
		}
		double expected = triple + pair * 1.1;
		assertTrue(expected > 0.85 && expected < 1.0, "老虎机返还率应在 85%~100% 之间，实际 " + expected);
	}
}
