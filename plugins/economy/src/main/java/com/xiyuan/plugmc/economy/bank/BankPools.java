package com.xiyuan.plugmc.economy.bank;

import java.util.Locale;
import java.util.Map;

/**
 * 建材银行的分类型额度池：每个分类（木材/石材/玻璃…）单独记账，
 * 只能在自己分类内兑换；旧版本遗留的总额度单独记为 {@link #LEGACY}，可继续跨类使用直到用完。
 */
public final class BankPools {
	public static final String LEGACY = "legacy";

	private BankPools() {
	}

	public static String normalize(String category) {
		return category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
	}

	public static long credit(Map<String, Long> pools, String category) {
		Long value = pools.get(normalize(category));
		return value == null || value < 0 ? 0L : value;
	}

	public static long legacy(Map<String, Long> pools) {
		return credit(pools, LEGACY);
	}

	public static long total(Map<String, Long> pools) {
		long total = 0;
		for (Long value : pools.values()) {
			if (value != null && value > 0) {
				total += value;
			}
		}
		return total;
	}

	/**
	 * 可用额度 = 本分类额度 + 历史额度（历史额度是旧版本单池数据的迁移结果）。
	 */
	public static long available(Map<String, Long> pools, String category) {
		return credit(pools, category) + legacy(pools);
	}

	public static void add(Map<String, Long> pools, String category, long points) {
		if (points <= 0) {
			return;
		}
		String key = normalize(category);
		pools.merge(key, points, Long::sum);
	}

	/**
	 * 先扣本分类额度，再扣历史额度；返回实际扣除的数量，余额不会为负。
	 */
	public static long spend(Map<String, Long> pools, String category, long requested) {
		long remaining = Math.max(0L, requested);
		if (remaining <= 0) {
			return 0;
		}
		long spent = 0;
		String key = normalize(category);
		long own = credit(pools, key);
		long fromOwn = Math.min(own, remaining);
		if (fromOwn > 0) {
			pools.put(key, own - fromOwn);
			spent += fromOwn;
			remaining -= fromOwn;
		}
		long legacy = legacy(pools);
		long fromLegacy = Math.min(legacy, remaining);
		if (fromLegacy > 0) {
			pools.put(LEGACY, legacy - fromLegacy);
			spent += fromLegacy;
		}
		return spent;
	}

	/**
	 * 退款时把额度原路退回对应分类/历史池。
	 */
	public static void refund(Map<String, Long> pools, String category, long own, long legacy) {
		add(pools, category, own);
		add(pools, LEGACY, legacy);
	}
}
