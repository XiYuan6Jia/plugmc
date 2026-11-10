package com.xiyuan.plugmc.economy.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerFreezeTest {
	private static final EconomyConfig CONFIG = new EconomyConfig(
		"金币",
		"$",
		2,
		new BigDecimal("100.00"),
		300,
		14,
		0.03
	);

	@Test
	void frozenPlayerCannotSpend() {
		FrozenStore store = new FrozenStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();
		ledger.createAccount(player);
		store.frozen.add(LedgerService.playerKey(player));

		assertFalse(ledger.withdraw(player, new BigDecimal("10.00"), "TEST", null));
		assertFalse(ledger.transfer(player, UUID.randomUUID(), new BigDecimal("10.00"), "TEST", null));
		assertEquals(new BigDecimal("100.00"), ledger.balance(player));
	}

	@Test
	void frozenPlayerCanStillReceive() {
		FrozenStore store = new FrozenStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();
		ledger.createAccount(player);
		store.frozen.add(LedgerService.playerKey(player));

		assertTrue(ledger.deposit(player, new BigDecimal("15.00"), "TEST", null));
		assertEquals(new BigDecimal("115.00"), ledger.balance(player));
	}

	@Test
	void frozenPlayerCannotJoinEscrowPayment() {
		FrozenStore store = new FrozenStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();
		ledger.createAccount(player);
		store.frozen.add(LedgerService.playerKey(player));

		assertFalse(ledger.commitEntries(
			java.util.List.of(
				new JournalRecord.Entry(LedgerService.playerKey(player), new BigDecimal("-5.00")),
				new JournalRecord.Entry(LedgerService.ESCROW_MARKET, new BigDecimal("5.00"))
			),
			java.util.List.of(),
			"TEST",
			null
		));
		assertEquals(new BigDecimal("100.00"), ledger.balance(player));
	}

	@Test
	void adminForcedWithdrawBypassesFreeze() {
		FrozenStore store = new FrozenStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();
		ledger.createAccount(player);
		store.frozen.add(LedgerService.playerKey(player));

		assertTrue(ledger.withdraw(player, new BigDecimal("10.00"), "ADMIN_TAKE", "console", true));
		assertEquals(new BigDecimal("90.00"), ledger.balance(player));
	}

	@Test
	void setBalanceStillWorksOnFrozenAccount() {
		FrozenStore store = new FrozenStore();
		LedgerService ledger = new LedgerService(store, CONFIG);
		UUID player = UUID.randomUUID();
		ledger.createAccount(player);
		store.frozen.add(LedgerService.playerKey(player));

		ledger.setBalance(player, new BigDecimal("20.00"), "ADMIN_SET", "console");
		assertEquals(new BigDecimal("20.00"), ledger.balance(player));
	}

	private static final class FrozenStore implements LedgerStore {
		private final Map<String, BigDecimal> accounts = new HashMap<>();
		private final Set<String> frozen = new HashSet<>();
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

		@Override
		public boolean isAccountFrozen(String account) {
			return frozen.contains(account);
		}
	}
}
