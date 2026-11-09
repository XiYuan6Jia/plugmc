package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内存账本存储：仅用于 /eco selftest 等演练场景，不落盘、不影响真实数据。
 */
public final class InMemoryLedgerStore implements LedgerStore {
	private final Map<String, BigDecimal> accounts = new LinkedHashMap<>();
	private long seq;

	@Override
	public boolean hasAccount(String account) {
		return accounts.containsKey(account);
	}

	@Override
	public BigDecimal balance(String account) {
		return accounts.get(account);
	}

	@Override
	public void createAccount(String account) {
		accounts.putIfAbsent(account, new BigDecimal("0.00"));
	}

	@Override
	public long nextSeq() {
		return ++seq;
	}

	@Override
	public void commit(JournalRecord record) {
		for (JournalRecord.Entry entry : record.entries()) {
			accounts.merge(entry.account(), entry.delta(), BigDecimal::add);
		}
	}

	@Override
	public Map<String, BigDecimal> balances() {
		return Map.copyOf(accounts);
	}
}
