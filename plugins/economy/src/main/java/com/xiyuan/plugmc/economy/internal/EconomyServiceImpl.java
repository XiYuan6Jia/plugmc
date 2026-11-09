package com.xiyuan.plugmc.economy.internal;

import com.xiyuan.plugmc.economy.api.EconomyService;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 对外的经济门面。保持原有 API 语义，内部委托给复式记账账本。
 */
public final class EconomyServiceImpl implements EconomyService {
	private final LedgerService ledger;
	private final EconomyConfig config;

	public EconomyServiceImpl(LedgerService ledger, EconomyConfig config) {
		this.ledger = Objects.requireNonNull(ledger, "ledger");
		this.config = Objects.requireNonNull(config, "config");
	}

	public LedgerService ledger() {
		return ledger;
	}

	@Override
	public BigDecimal getBalance(UUID playerId) {
		return ledger.balance(playerId);
	}

	@Override
	public void createAccount(UUID playerId) {
		ledger.createAccount(playerId);
	}

	@Override
	public boolean deposit(UUID playerId, BigDecimal amount) {
		return ledger.deposit(playerId, amount, "API_DEPOSIT", null);
	}

	@Override
	public boolean withdraw(UUID playerId, BigDecimal amount) {
		return ledger.withdraw(playerId, amount, "API_WITHDRAW", null);
	}

	@Override
	public boolean transfer(UUID fromPlayerId, UUID toPlayerId, BigDecimal amount) {
		return ledger.transfer(fromPlayerId, toPlayerId, amount, "API_TRANSFER", null);
	}

	@Override
	public boolean transfer(UUID fromPlayerId, UUID toPlayerId, BigDecimal amount, String reason) {
		return ledger.transfer(fromPlayerId, toPlayerId, amount, reason, null);
	}

	@Override
	public void setBalance(UUID playerId, BigDecimal amount) {
		ledger.setBalance(playerId, amount);
	}

	@Override
	public String format(BigDecimal amount) {
		return ledger.format(amount);
	}

	@Override
	public String getCurrencyName() {
		return config.currencyName();
	}

	@Override
	public int getDecimalPlaces() {
		return config.decimalPlaces();
	}
}
