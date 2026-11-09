package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一笔复式记账事务。先追加到 journal，再应用到内存状态。
 */
public record JournalRecord(
	long seq,
	String txId,
	long timestamp,
	String reason,
	String ref,
	List<Entry> entries,
	List<StateOp> stateOps
) {
	public JournalRecord {
		entries = entries == null ? List.of() : List.copyOf(entries);
		stateOps = stateOps == null ? List.of() : List.copyOf(stateOps);
		if (entries.isEmpty() && stateOps.isEmpty()) {
			throw new IllegalArgumentException("账本事务不能没有分录或状态变更");
		}
	}

	public JournalRecord(long seq, String txId, long timestamp, String reason, String ref, List<Entry> entries) {
		this(seq, txId, timestamp, reason, ref, entries, List.of());
	}

	public record Entry(String account, BigDecimal delta) {
	}

	/**
	 * 与资金变更同事务提交的状态操作。payload 为对应 DTO 的 JSON。
	 */
	public record StateOp(String type, String id, String payload) {
	}
}
