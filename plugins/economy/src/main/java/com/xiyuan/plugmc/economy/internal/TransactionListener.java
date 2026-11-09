package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账本提交回调：由插件层桥接成 Bukkit 事件，测试环境无需依赖服务器实例。
 */
public interface TransactionListener {
	void onCommitted(String reason, String ref, long sequence, List<JournalRecord.Entry> entries);

	static TransactionListener noop() {
		return (reason, ref, sequence, entries) -> {
		};
	}

	static BigDecimal sumOf(List<JournalRecord.Entry> entries) {
		BigDecimal sum = BigDecimal.ZERO;
		for (JournalRecord.Entry entry : entries) {
			sum = sum.add(entry.delta());
		}
		return sum;
	}
}
