package com.xiyuan.plugmc.economy.api;

import java.math.BigDecimal;
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
}
