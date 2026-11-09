package com.xiyuan.plugmc.economy.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Synchronous, persistent economy API exposed through Bukkit's ServicesManager.
 * Transaction amounts must be positive and use no more decimal places than the configured currency.
 */
public interface EconomyService {
	BigDecimal getBalance(UUID playerId);

	void createAccount(UUID playerId);

	boolean deposit(UUID playerId, BigDecimal amount);

	boolean withdraw(UUID playerId, BigDecimal amount);

	boolean transfer(UUID fromPlayerId, UUID toPlayerId, BigDecimal amount);

	void setBalance(UUID playerId, BigDecimal amount);

	String format(BigDecimal amount);

	String getCurrencyName();

	/**
	 * 余额是否足够。默认实现基于 {@link #getBalance(UUID)}，实现类可覆盖。
	 */
	default boolean has(UUID playerId, BigDecimal amount) {
		return getBalance(playerId).compareTo(amount) >= 0;
	}

	/**
	 * 带原因的转账，便于审计。默认实现忽略原因，保持旧实现的二进制兼容。
	 */
	default boolean transfer(UUID fromPlayerId, UUID toPlayerId, BigDecimal amount, String reason) {
		return transfer(fromPlayerId, toPlayerId, amount);
	}

	/**
	 * 货币小数位。默认 2，实现类可按配置覆盖。
	 */
	default int getDecimalPlaces() {
		return 2;
	}

	/**
	 * 余额排行，按余额降序返回最多 limit 名玩家；不支持排行的实现返回空列表。
	 */
	default List<BalanceEntry> topBalances(int limit) {
		return List.of();
	}

	record BalanceEntry(UUID playerId, String name, BigDecimal balance) {
	}
}
