package com.xiyuan.plugmc.economy.internal;

import com.xiyuan.plugmc.economy.api.EconomyAuditService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EconomyAuditServiceImpl implements EconomyAuditService {
	private final LedgerService ledger;
	private final EconomyStore store;
	private final EconomyConfig config;

	public EconomyAuditServiceImpl(LedgerService ledger, EconomyStore store, EconomyConfig config) {
		this.ledger = ledger;
		this.store = store;
		this.config = config;
	}

	@Override
	public List<String> statsLines() {
		Map<String, BigDecimal> balances = ledger.balances();
		long players = balances.keySet().stream().filter(key -> key.startsWith("player:")).count();
		BigDecimal escrow = ledger.accountBalance(LedgerService.ESCROW_MARKET)
			.add(ledger.accountBalance(LedgerService.ESCROW_AUCTION))
			.add(ledger.accountBalance(LedgerService.ESCROW_ORDER))
			.add(ledger.accountBalance(LedgerService.ESCROW_TRADE));
		List<String> lines = new ArrayList<>();
		lines.add("玩家账户：" + players);
		lines.add("玩家余额合计：" + ledger.format(ledger.playerTotal()));
		lines.add("铸币账户：" + ledger.accountBalance(LedgerService.MINT).toPlainString());
		lines.add("税收账户：" + ledger.format(ledger.accountBalance(LedgerService.TAX)));
		lines.add("财政账户：" + ledger.format(ledger.accountBalance(LedgerService.TREASURY)));
		lines.add("托管合计：" + ledger.format(escrow));
		lines.add("事务序号：" + store.lastSeq());
		return List.copyOf(lines);
	}

	@Override
	public List<Check> doctorChecks() {
		List<Check> checks = new ArrayList<>();
		BigDecimal total = ledger.total();
		checks.add(new Check("账本合计为 0", total.signum() == 0, total.toPlainString()));

		boolean negativePlayer = false;
		boolean negativeEscrow = false;
		for (Map.Entry<String, BigDecimal> entry : ledger.balances().entrySet()) {
			if (entry.getKey().startsWith("player:") && entry.getValue().signum() < 0) {
				negativePlayer = true;
			}
			if (entry.getKey().startsWith("escrow:") && entry.getValue().signum() < 0) {
				negativeEscrow = true;
			}
		}
		checks.add(new Check("玩家账户无负数", !negativePlayer, negativePlayer ? "存在负数玩家账户" : "正常"));
		checks.add(new Check("托管账户无负数", !negativeEscrow, negativeEscrow ? "存在负数托管账户" : "正常"));

		BigDecimal auctionExpected = EscrowAudit.expectedAuctionEscrow(store.auctions().values());
		BigDecimal auctionActual = ledger.accountBalance(LedgerService.ESCROW_AUCTION);
		checks.add(new Check(
			"拍卖托管与挂单一致",
			auctionExpected.compareTo(auctionActual) == 0,
			"记录 " + auctionExpected.toPlainString() + " / 账户 " + auctionActual.toPlainString()
		));

		BigDecimal orderExpected = EscrowAudit.expectedOrderEscrow(store.orders().values());
		BigDecimal orderActual = ledger.accountBalance(LedgerService.ESCROW_ORDER);
		checks.add(new Check(
			"收购托管与挂单一致",
			orderExpected.compareTo(orderActual) == 0,
			"记录 " + orderExpected.toPlainString() + " / 账户 " + orderActual.toPlainString()
		));

		BigDecimal market = ledger.accountBalance(LedgerService.ESCROW_MARKET);
		checks.add(new Check("市场托管为 0", market.signum() == 0, market.toPlainString()));
		return List.copyOf(checks);
	}

	@Override
	public List<String> reminders() {
		long now = System.currentTimeMillis();
		List<String> lines = new ArrayList<>();
		int expiredAuctions = EscrowAudit.expiredAuctions(store.auctions().values(), now);
		if (expiredAuctions > 0) {
			lines.add(expiredAuctions + " 个拍卖已到期待结算（邮箱满时会重试）");
		}
		int expiredOrders = EscrowAudit.expiredOrders(store.orders().values(), now);
		if (expiredOrders > 0) {
			lines.add(expiredOrders + " 个收购订单已到期待退款");
		}
		return List.copyOf(lines);
	}

	@Override
	public List<Check> selfTestChecks() {
		List<Check> checks = new ArrayList<>();
		for (LedgerSelfTest.Check check : LedgerSelfTest.run()) {
			checks.add(new Check(check.name(), check.passed(), check.detail()));
		}
		return List.copyOf(checks);
	}

	@Override
	public String dailyStatus(UUID playerId) {
		String today = java.time.LocalDate.now().toString();
		if (today.equals(store.lastDailyDate(playerId))) {
			return "今日已签到（连续 " + store.dailyStreak(playerId) + " 天）";
		}
		return "今日未签到";
	}

	@Override
	public boolean isFrozen(UUID playerId) {
		return store.isFrozen(playerId);
	}

	@Override
	public BigDecimal treasuryBalance() {
		return ledger.accountBalance(LedgerService.TREASURY);
	}

	public EconomyConfig config() {
		return config;
	}
}
