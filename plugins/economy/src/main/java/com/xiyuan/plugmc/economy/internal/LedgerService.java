package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 复式记账账本。所有余额变更都必须经过这里，系统账户与托管账户使用固定键。
 */
public final class LedgerService {
	public static final String MINT = "system:MINT";
	public static final String TAX = "system:TAX";
	public static final String TREASURY = "system:TREASURY";
	public static final String ESCROW_MARKET = "escrow:MARKET";
	public static final String ESCROW_AUCTION = "escrow:AUCTION";
	public static final String ESCROW_ORDER = "escrow:ORDER";
	public static final String ESCROW_TRADE = "escrow:TRADE";

	private static final List<String> SYSTEM_ACCOUNTS = List.of(
		MINT, TAX, TREASURY, ESCROW_MARKET, ESCROW_AUCTION, ESCROW_ORDER, ESCROW_TRADE
	);

	private final LedgerStore store;
	private final EconomyConfig config;
	private final Object lock = new Object();
	private volatile TransactionListener transactionListener = TransactionListener.noop();

	public LedgerService(LedgerStore store, EconomyConfig config) {
		this.store = Objects.requireNonNull(store, "store");
		this.config = Objects.requireNonNull(config, "config");
		synchronized (lock) {
			ensureSystemAccounts();
		}
	}

	public void setTransactionListener(TransactionListener listener) {
		this.transactionListener = listener == null ? TransactionListener.noop() : listener;
	}

	public EconomyConfig config() {
		return config;
	}

	public int decimalPlaces() {
		return config.decimalPlaces();
	}

	public String currencyName() {
		return config.currencyName();
	}

	public BigDecimal balance(UUID playerId) {
		String key = playerKey(playerId);
		synchronized (lock) {
			BigDecimal value = store.balance(key);
			return value == null ? config.startingBalance() : value;
		}
	}

	public BigDecimal accountBalance(String account) {
		synchronized (lock) {
			BigDecimal value = store.balance(account);
			return value == null ? Money.zero(config.decimalPlaces()) : value;
		}
	}

	public Map<String, BigDecimal> balances() {
		synchronized (lock) {
			return store.balances();
		}
	}

	public BigDecimal playerTotal() {
		return sum(balances(), true);
	}

	public BigDecimal systemTotal() {
		return sum(balances(), false);
	}

	public BigDecimal total() {
		return sum(balances(), null);
	}

	public void createAccount(UUID playerId) {
		synchronized (lock) {
			ensureAccount(playerId);
		}
	}

	public boolean deposit(UUID playerId, BigDecimal amount, String reason, String ref) {
		BigDecimal normalized = Money.positive(amount, config.decimalPlaces());
		synchronized (lock) {
			ensureAccount(playerId);
			return transferInternal(MINT, playerKey(playerId), normalized, reason, ref);
		}
	}

	public boolean withdraw(UUID playerId, BigDecimal amount, String reason, String ref) {
		return withdraw(playerId, amount, reason, ref, false);
	}

	/**
	 * 管理员扣款可绕过冻结；普通业务（{@code forced=false}）在账户冻结时直接失败。
	 */
	public boolean withdraw(UUID playerId, BigDecimal amount, String reason, String ref, boolean forced) {
		BigDecimal normalized = Money.positive(amount, config.decimalPlaces());
		synchronized (lock) {
			String key = playerKey(playerId);
			if (!forced && store.isAccountFrozen(key)) {
				return false;
			}
			BigDecimal current = store.balance(key);
			if (current == null) {
				if (config.startingBalance().compareTo(normalized) < 0) {
					return false;
				}
				ensureAccount(playerId);
			}
			return transferInternal(key, MINT, normalized, reason, ref);
		}
	}

	public boolean isFrozen(UUID playerId) {
		return store.isAccountFrozen(playerKey(playerId));
	}

	/**
	 * 冻结/解冻玩家账户，只限制支出；入账与管理员操作不受影响。
	 */
	public void setFrozen(UUID playerId, boolean frozen, String actor) {
		String key = playerKey(playerId);
		commitState(
			List.of(new JournalRecord.StateOp(
				"PLAYER_FROZEN",
				playerId.toString(),
				frozen ? "1" : "0"
			)),
			frozen ? "ACCOUNT_FREEZE" : "ACCOUNT_UNFREEZE",
			actor
		);
	}

	public boolean transfer(UUID fromPlayerId, UUID toPlayerId, BigDecimal amount, String reason, String ref) {
		BigDecimal normalized = Money.positive(amount, config.decimalPlaces());
		if (requirePlayerId(fromPlayerId).equals(requirePlayerId(toPlayerId))) {
			return false;
		}
		synchronized (lock) {
			String fromKey = playerKey(fromPlayerId);
			if (store.isAccountFrozen(fromKey)) {
				return false;
			}
			BigDecimal fromBalance = store.balance(fromKey);
			if (fromBalance == null) {
				if (config.startingBalance().compareTo(normalized) < 0) {
					return false;
				}
				ensureAccount(fromPlayerId);
			}
			ensureAccount(toPlayerId);
			return transferInternal(fromKey, playerKey(toPlayerId), normalized, reason, ref);
		}
	}

	/**
	 * 带交易税的转账：转出方扣 amount，接收方得到 amount - tax，税进 TAX 账户。
	 */
	public boolean transferWithTax(
		UUID fromPlayerId,
		UUID toPlayerId,
		BigDecimal amount,
		BigDecimal tax,
		String reason,
		String ref
	) {
		BigDecimal normalized = Money.positive(amount, config.decimalPlaces());
		BigDecimal normalizedTax = Money.nonNegative(tax, config.decimalPlaces());
		if (normalizedTax.compareTo(normalized) > 0) {
			normalizedTax = normalized;
		}
		if (requirePlayerId(fromPlayerId).equals(requirePlayerId(toPlayerId))) {
			return false;
		}
		synchronized (lock) {
			String fromKey = playerKey(fromPlayerId);
			if (store.isAccountFrozen(fromKey)) {
				return false;
			}
			BigDecimal fromBalance = store.balance(fromKey);
			if (fromBalance == null) {
				if (config.startingBalance().compareTo(normalized) < 0) {
					return false;
				}
				ensureAccount(fromPlayerId);
			}
			ensureAccount(toPlayerId);
			List<JournalRecord.Entry> entries = new ArrayList<>();
			entries.add(new JournalRecord.Entry(fromKey, normalized.negate()));
			entries.add(new JournalRecord.Entry(
				playerKey(toPlayerId),
				normalized.subtract(normalizedTax)
			));
			if (normalizedTax.signum() > 0) {
				entries.add(new JournalRecord.Entry(TAX, normalizedTax));
			}
			return commitEntries(entries, List.of(), reason, ref);
		}
	}

	/**
	 * 模块内部使用：在任意两个已存在的账户之间转账；目标账户缺失时创建为零余额。
	 */
	public boolean transferAccount(String fromAccount, String toAccount, BigDecimal amount, String reason, String ref) {
		return transferAccount(fromAccount, toAccount, amount, reason, ref, List.of());
	}

	public boolean transferAccount(
		String fromAccount,
		String toAccount,
		BigDecimal amount,
		String reason,
		String ref,
		List<JournalRecord.StateOp> stateOps
	) {
		BigDecimal normalized = Money.positive(amount, config.decimalPlaces());
		return commitEntries(
			List.of(
				new JournalRecord.Entry(fromAccount, normalized.negate()),
				new JournalRecord.Entry(toAccount, normalized)
			),
			stateOps,
			reason,
			ref
		);
	}

	/**
	 * 提交一笔可能包含多个账户分录和状态变更的原子事务。
	 */
	public boolean commitEntries(
		List<JournalRecord.Entry> entries,
		List<JournalRecord.StateOp> stateOps,
		String reason,
		String ref
	) {
		List<JournalRecord.Entry> normalizedEntries = entries == null ? List.of() : List.copyOf(entries);
		List<JournalRecord.StateOp> normalizedOps = stateOps == null ? List.of() : List.copyOf(stateOps);
		if (normalizedEntries.isEmpty() && normalizedOps.isEmpty()) {
			return false;
		}

		synchronized (lock) {
			Map<String, BigDecimal> totals = new LinkedHashMap<>();
			for (JournalRecord.Entry entry : normalizedEntries) {
				if (entry == null || entry.account() == null || entry.account().isBlank() || entry.delta() == null) {
					throw new IllegalArgumentException("账本分录无效");
				}
				BigDecimal delta = Money.scale(entry.delta(), config.decimalPlaces());
				totals.merge(entry.account(), delta, BigDecimal::add);
			}

			BigDecimal sum = Money.zero(config.decimalPlaces());
			for (BigDecimal total : totals.values()) {
				sum = sum.add(total);
			}
			if (sum.signum() != 0) {
				throw new IllegalArgumentException("账本分录合计必须为 0");
			}

			for (Map.Entry<String, BigDecimal> total : totals.entrySet()) {
				if (total.getValue().signum() < 0 && store.isAccountFrozen(total.getKey())) {
					return false;
				}
			}

			for (Map.Entry<String, BigDecimal> total : totals.entrySet()) {
				String account = total.getKey();
				if (MINT.equals(account)) {
					continue;
				}
				if (!store.hasAccount(account)) {
					store.createAccount(account);
				}
				BigDecimal current = store.balance(account);
				if (current == null) {
					current = Money.zero(config.decimalPlaces());
				}
				if (current.add(total.getValue()).signum() < 0) {
					return false;
				}
			}

			JournalRecord record = new JournalRecord(
				store.nextSeq(),
				UUID.randomUUID().toString(),
				System.currentTimeMillis(),
				reason == null ? "TRANSACTION" : reason,
				ref,
				normalizedEntries,
				normalizedOps
			);
			store.commit(record);
			transactionListener.onCommitted(
				record.reason(),
				record.ref(),
				record.seq(),
				normalizedEntries
			);
			return true;
		}
	}

	public boolean commitState(List<JournalRecord.StateOp> stateOps, String reason, String ref) {
		if (stateOps == null || stateOps.isEmpty()) {
			return false;
		}
		synchronized (lock) {
			JournalRecord record = new JournalRecord(
				store.nextSeq(),
				UUID.randomUUID().toString(),
				System.currentTimeMillis(),
				reason == null ? "STATE" : reason,
				ref,
				List.of(),
				stateOps
			);
			store.commit(record);
			return true;
		}
	}

	public void setBalance(UUID playerId, BigDecimal amount) {
		setBalance(playerId, amount, "SET_BALANCE", null);
	}

	public void setBalance(UUID playerId, BigDecimal amount, String reason, String ref) {
		BigDecimal normalized = Money.nonNegative(amount, config.decimalPlaces());
		synchronized (lock) {
			String key = playerKey(playerId);
			BigDecimal current = store.balance(key);
			if (current == null) {
				store.createAccount(key);
				current = store.balance(key);
			}
			int comparison = normalized.compareTo(current);
			if (comparison > 0) {
				transferInternal(MINT, key, normalized.subtract(current), reason, ref);
			} else if (comparison < 0) {
				transferInternal(key, MINT, current.subtract(normalized), reason, ref);
			}
		}
	}

	public String format(BigDecimal amount) {
		return Money.format(amount, config.currencySymbol(), config.decimalPlaces());
	}

	public static String playerKey(UUID playerId) {
		return "player:" + requirePlayerId(playerId);
	}

	private void ensureAccount(UUID playerId) {
		String key = playerKey(playerId);
		if (store.hasAccount(key)) {
			return;
		}
		store.createAccount(key);
		BigDecimal starting = config.startingBalance();
		if (starting.signum() > 0) {
			transferInternal(MINT, key, starting, "START_BALANCE", null);
		}
	}

	private void ensureSystemAccounts() {
		for (String account : SYSTEM_ACCOUNTS) {
			if (!store.hasAccount(account)) {
				store.createAccount(account);
			}
		}
	}

	private boolean transferInternal(String fromAccount, String toAccount, BigDecimal amount, String reason, String ref) {
		BigDecimal fromBalance = store.balance(fromAccount);
		if (fromBalance == null) {
			throw new IllegalStateException("账户不存在：" + fromAccount);
		}
		// MINT 是铸币源，允许为负；其他账户不允许透支。
		if (!MINT.equals(fromAccount) && fromBalance.compareTo(amount) < 0) {
			return false;
		}

		JournalRecord record = new JournalRecord(
			store.nextSeq(),
			UUID.randomUUID().toString(),
			System.currentTimeMillis(),
			reason == null ? "TRANSFER" : reason,
			ref,
			List.of(
				new JournalRecord.Entry(fromAccount, amount.negate()),
				new JournalRecord.Entry(toAccount, amount)
			)
		);
		store.commit(record);
		transactionListener.onCommitted(record.reason(), record.ref(), record.seq(), record.entries());
		return true;
	}

	private BigDecimal sum(Map<String, BigDecimal> balances, Boolean playerAccounts) {
		BigDecimal total = Money.zero(config.decimalPlaces());
		for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
			boolean player = entry.getKey().startsWith("player:");
			if (playerAccounts != null && player != playerAccounts) {
				continue;
			}
			total = total.add(entry.getValue());
		}
		return total.setScale(config.decimalPlaces(), java.math.RoundingMode.UNNECESSARY);
	}

	private static UUID requirePlayerId(UUID playerId) {
		if (playerId == null) {
			throw new IllegalArgumentException("playerId 不能为空");
		}
		return playerId;
	}
}
