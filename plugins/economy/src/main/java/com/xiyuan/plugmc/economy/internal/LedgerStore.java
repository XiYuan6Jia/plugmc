package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 账本对存储的最小依赖，便于对账本逻辑做单元测试。
 */
public interface LedgerStore {
	boolean hasAccount(String account);

	BigDecimal balance(String account);

	void createAccount(String account);

	long nextSeq();

	void commit(JournalRecord record);

	Map<String, BigDecimal> balances();
}
