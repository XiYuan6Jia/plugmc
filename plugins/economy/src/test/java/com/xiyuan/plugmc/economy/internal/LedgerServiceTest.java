package com.xiyuan.plugmc.economy.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerServiceTest {
	private static final EconomyConfig CONFIG = new EconomyConfig(
		"金币",
		"$",
		2,
		new BigDecimal("100.00"),
		300,
		14
	);

	@Test
	void startingBalanceIsMintedOnce() {
		FakeStore store = new FakeStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();

		assertEquals(new BigDecimal("100.00"), ledger.balance(player));
		assertFalse(store.hasAccount(LedgerService.playerKey(player)));

		ledger.createAccount(player);
		assertEquals(new BigDecimal("100.00"), ledger.balance(player));
		assertEquals(new BigDecimal("-100.00"), store.balance(LedgerService.MINT));
		assertEquals(0, ledger.total().signum());

		ledger.createAccount(player);
		assertEquals(new BigDecimal("-100.00"), store.balance(LedgerService.MINT));
	}

	@Test
	void depositAndWithdrawKeepLedgerBalanced() {
		FakeStore store = new FakeStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();

		assertTrue(ledger.deposit(player, new BigDecimal("50.00"), "TEST", null));
		assertEquals(new BigDecimal("150.00"), ledger.balance(player));
		assertEquals(new BigDecimal("-150.00"), store.balance(LedgerService.MINT));
		assertEquals(0, ledger.total().signum());

		assertTrue(ledger.withdraw(player, new BigDecimal("30.00"), "TEST", null));
		assertEquals(new BigDecimal("120.00"), ledger.balance(player));
		assertEquals(new BigDecimal("-120.00"), store.balance(LedgerService.MINT));
		assertEquals(0, ledger.total().signum());
	}

	@Test
	void withdrawFailsWithoutCreatingAccountWhenStartingBalanceIsTooLow() {
		FakeStore store = new FakeStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();

		assertFalse(ledger.withdraw(player, new BigDecimal("100.01"), "TEST", null));
		assertFalse(store.hasAccount(LedgerService.playerKey(player)));
	}

	@Test
	void transferMintsStartingBalancesForUnknownPlayers() {
		FakeStore store = new FakeStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID sender = UUID.randomUUID();
		UUID recipient = UUID.randomUUID();

		assertTrue(ledger.transfer(sender, recipient, new BigDecimal("100.00"), "TEST", null));
		assertEquals(new BigDecimal("0.00"), ledger.balance(sender));
		assertEquals(new BigDecimal("200.00"), ledger.balance(recipient));
		assertEquals(0, ledger.total().signum());
	}

	@Test
	void transferToSelfIsRejected() {
		FakeStore store = new FakeStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();

		assertFalse(ledger.transfer(player, player, new BigDecimal("1.00"), "TEST", null));
	}

	@Test
	void setBalanceReplacesMissingAccountWithoutAddingStartingBalance() {
		FakeStore store = new FakeStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();

		ledger.setBalance(player, new BigDecimal("50.00"));
		assertEquals(new BigDecimal("50.00"), ledger.balance(player));
		assertEquals(new BigDecimal("-50.00"), store.balance(LedgerService.MINT));
		assertEquals(0, ledger.total().signum());

		ledger.setBalance(player, new BigDecimal("10.00"));
		assertEquals(new BigDecimal("10.00"), ledger.balance(player));
		assertEquals(new BigDecimal("-10.00"), store.balance(LedgerService.MINT));
		assertEquals(0, ledger.total().signum());
	}

	private static final class FakeStore implements LedgerStore {
		private final Map<String, BigDecimal> accounts = new HashMap<>();
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
}
