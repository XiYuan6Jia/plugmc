package com.xiyuan.plugmc.economy.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardTest {
	private static UUID id(String suffix) {
		return UUID.fromString("00000000-0000-0000-0000-0000000000" + suffix);
	}

	@Test
	void sortsByBalanceDescending() {
		Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
		balances.put(id("01"), new BigDecimal("10.00"));
		balances.put(id("02"), new BigDecimal("99.00"));
		balances.put(id("03"), new BigDecimal("50.00"));

		List<Map.Entry<UUID, BigDecimal>> top = Leaderboard.top(balances, 3);
		assertEquals(new BigDecimal("99.00"), top.get(0).getValue());
		assertEquals(new BigDecimal("50.00"), top.get(1).getValue());
		assertEquals(new BigDecimal("10.00"), top.get(2).getValue());
	}

	@Test
	void tiesAreOrderedStablyByUuid() {
		Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
		balances.put(id("03"), new BigDecimal("5.00"));
		balances.put(id("01"), new BigDecimal("5.00"));
		balances.put(id("02"), new BigDecimal("5.00"));

		List<Map.Entry<UUID, BigDecimal>> top = Leaderboard.top(balances, 3);
		assertEquals(id("01"), top.get(0).getKey());
		assertEquals(id("02"), top.get(1).getKey());
		assertEquals(id("03"), top.get(2).getKey());
	}

	@Test
	void honoursLimitAndHandlesEmptyInput() {
		Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
		balances.put(id("01"), new BigDecimal("1.00"));
		balances.put(id("02"), new BigDecimal("2.00"));
		assertEquals(1, Leaderboard.top(balances, 1).size());
		assertTrue(Leaderboard.top(Map.of(), 5).isEmpty());
		assertTrue(Leaderboard.top(balances, 0).isEmpty());
	}
}
