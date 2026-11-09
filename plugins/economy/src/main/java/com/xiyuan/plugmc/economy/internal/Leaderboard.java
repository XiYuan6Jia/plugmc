package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 余额排行：按余额降序，余额相同按 UUID 保证顺序稳定。
 */
public final class Leaderboard {
	private Leaderboard() {
	}

	public static List<Map.Entry<UUID, BigDecimal>> top(Map<UUID, BigDecimal> balances, int limit) {
		if (limit <= 0 || balances == null || balances.isEmpty()) {
			return List.of();
		}
		List<Map.Entry<UUID, BigDecimal>> entries = new ArrayList<>(balances.entrySet());
		entries.sort(Comparator
			.comparing((Map.Entry<UUID, BigDecimal> entry) -> entry.getValue()).reversed()
			.thenComparing(entry -> entry.getKey().toString()));
		return List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
	}
}
