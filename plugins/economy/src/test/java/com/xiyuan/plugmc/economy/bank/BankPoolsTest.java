package com.xiyuan.plugmc.economy.bank;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankPoolsTest {
	private static Map<String, Long> pools() {
		Map<String, Long> pools = new HashMap<>();
		pools.put("stone", 500L);
		pools.put("wood", 120L);
		return pools;
	}

	@Test
	void categoriesAreIsolated() {
		Map<String, Long> pools = pools();
		assertEquals(500L, BankPools.available(pools, "stone"));
		assertEquals(120L, BankPools.available(pools, "wood"));
		assertEquals(0L, BankPools.available(pools, "glass"));
		assertEquals(0L, BankPools.spend(pools, "glass", 100));
		assertEquals(500L, BankPools.credit(pools, "stone"));
	}

	@Test
	void spendStaysInsideCategory() {
		Map<String, Long> pools = pools();
		assertEquals(100L, BankPools.spend(pools, "wood", 100));
		assertEquals(20L, BankPools.credit(pools, "wood"));
		assertEquals(500L, BankPools.credit(pools, "stone"));
	}

	@Test
	void spendNeverExceedsBalance() {
		Map<String, Long> pools = pools();
		assertEquals(120L, BankPools.spend(pools, "wood", 1000));
		assertEquals(0L, BankPools.credit(pools, "wood"));
		assertEquals(500L, BankPools.total(pools));
	}

	@Test
	void legacyPoolIsSpendableEverywhere() {
		Map<String, Long> pools = pools();
		BankPools.add(pools, BankPools.LEGACY, 200L);
		assertEquals(320L, BankPools.available(pools, "wood"));
		assertEquals(320L, BankPools.spend(pools, "wood", 320));
		assertEquals(0L, BankPools.credit(pools, BankPools.LEGACY));
		assertEquals(0L, BankPools.credit(pools, "wood"));
	}

	@Test
	void refundRestoresPools() {
		Map<String, Long> pools = pools();
		long spent = BankPools.spend(pools, "stone", 300);
		assertEquals(300L, spent);
		BankPools.refund(pools, "stone", 200L, 100L);
		assertEquals(400L, BankPools.credit(pools, "stone"));
		assertEquals(100L, BankPools.credit(pools, BankPools.LEGACY));
	}
}
