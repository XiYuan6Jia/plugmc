package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 经济全链路演练：不接触真实数据，用内存账本走一遍「铸币 → 出价 → 超价退款 → 结算抽税」，
 * 校验每一步余额与托管金额，便于在线上用 /eco selftest 验证记账逻辑。
 */
public final class LedgerSelfTest {
	private static final double TAX_RATE = 0.03;

	private LedgerSelfTest() {
	}

	public record Check(String name, boolean passed, String detail) {
	}

	public static List<Check> run() {
		EconomyConfig config = new EconomyConfig(
			"金币",
			"$",
			2,
			new BigDecimal("100.00"),
			300,
			14
		);
		InMemoryLedgerStore store = new InMemoryLedgerStore();
		LedgerService ledger = new LedgerService(store, config);
		UUID bidderA = UUID.randomUUID();
		UUID bidderB = UUID.randomUUID();
		UUID seller = UUID.randomUUID();

		List<Check> checks = new ArrayList<>();
		ledger.createAccount(bidderA);
		ledger.createAccount(bidderB);
		ledger.createAccount(seller);
		checks.add(check("初始铸币后账本平衡", ledger.total().signum() == 0, ledger.total().toPlainString()));
		checks.add(check(
			"初始余额 100.00",
			ledger.balance(bidderA).compareTo(new BigDecimal("100.00")) == 0,
			ledger.balance(bidderA).toPlainString()
		));

		ledger.transferAccount(
			LedgerService.playerKey(bidderA),
			LedgerService.ESCROW_AUCTION,
			new BigDecimal("10.00"),
			"AUCTION_BID",
			"selftest-1"
		);
		checks.add(check(
			"A 出价 10.00 后托管 10.00",
			ledger.accountBalance(LedgerService.ESCROW_AUCTION).compareTo(new BigDecimal("10.00")) == 0,
			ledger.accountBalance(LedgerService.ESCROW_AUCTION).toPlainString()
		));

		ledger.transferAccount(
			LedgerService.ESCROW_AUCTION,
			LedgerService.playerKey(bidderA),
			new BigDecimal("10.00"),
			"AUCTION_REFUND",
			"selftest-1"
		);
		ledger.transferAccount(
			LedgerService.playerKey(bidderB),
			LedgerService.ESCROW_AUCTION,
			new BigDecimal("20.00"),
			"AUCTION_BID",
			"selftest-1"
		);
		checks.add(check(
			"被超价后 A 全额退款",
			ledger.balance(bidderA).compareTo(new BigDecimal("100.00")) == 0,
			ledger.balance(bidderA).toPlainString()
		));
		checks.add(check(
			"托管金额为最高出价 20.00",
			ledger.accountBalance(LedgerService.ESCROW_AUCTION).compareTo(new BigDecimal("20.00")) == 0,
			ledger.accountBalance(LedgerService.ESCROW_AUCTION).toPlainString()
		));

		Settlement settlement = Settlement.of(new BigDecimal("20.00"), TAX_RATE, 2);
		boolean settled = ledger.commitEntries(
			List.of(
				new JournalRecord.Entry(LedgerService.ESCROW_AUCTION, settlement.gross().negate()),
				new JournalRecord.Entry(LedgerService.playerKey(seller), settlement.net()),
				new JournalRecord.Entry(LedgerService.TAX, settlement.tax())
			),
			List.of(),
			"AUCTION_SETTLE",
			"selftest-1"
		);
		checks.add(check("结算事务提交成功", settled, "gross=" + settlement.gross().toPlainString()));
		checks.add(check(
			"卖家净得 19.40",
			ledger.balance(seller).compareTo(new BigDecimal("119.40")) == 0,
			ledger.balance(seller).toPlainString()
		));
		checks.add(check(
			"税费 0.60",
			ledger.accountBalance(LedgerService.TAX).compareTo(new BigDecimal("0.60")) == 0,
			ledger.accountBalance(LedgerService.TAX).toPlainString()
		));
		checks.add(check(
			"结算后托管归零",
			ledger.accountBalance(LedgerService.ESCROW_AUCTION).signum() == 0,
			ledger.accountBalance(LedgerService.ESCROW_AUCTION).toPlainString()
		));
		checks.add(check(
			"结算后账本仍平衡",
			ledger.total().signum() == 0,
			ledger.total().toPlainString()
		));

		boolean insufficient = ledger.withdraw(bidderB, new BigDecimal("10000.00"), "SELFTEST", null);
		checks.add(check("超额扣款被拒绝", !insufficient, Boolean.toString(insufficient)));
		checks.add(check(
			"拒绝后余额未变",
			ledger.balance(bidderB).compareTo(new BigDecimal("80.00")) == 0,
			ledger.balance(bidderB).toPlainString()
		));
		return List.copyOf(checks);
	}

	private static Check check(String name, boolean passed, String detail) {
		return new Check(name, passed, detail);
	}
}
