package com.xiyuan.plugmc.economy.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementTest {
	@Test
	void netPlusTaxAlwaysEqualsGross() {
		BigDecimal[] grosses = {
			new BigDecimal("0.00"),
			new BigDecimal("0.01"),
			new BigDecimal("1.00"),
			new BigDecimal("99.99"),
			new BigDecimal("1234.56")
		};
		double[] rates = {0.0, 0.03, 0.05, 0.5, 1.0};
		for (BigDecimal gross : grosses) {
			for (double rate : rates) {
				Settlement settlement = Settlement.of(gross, rate, 2);
				assertEquals(
					0,
					settlement.net().add(settlement.tax()).compareTo(settlement.gross()),
					"净得 + 税 必须等于成交额：" + gross + " @" + rate
				);
			}
		}
	}

	@Test
	void roundsTaxUpAndKeepsNetNonNegative() {
		Settlement settlement = Settlement.of(new BigDecimal("0.01"), 0.03, 2);
		assertEquals("0.01", settlement.tax().toPlainString());
		assertEquals("0.00", settlement.net().toPlainString());

		Settlement full = Settlement.of(new BigDecimal("10.00"), 1.0, 2);
		assertEquals("10.00", full.tax().toPlainString());
		assertEquals("0.00", full.net().toPlainString());
	}

	@Test
	void clampsOutOfRangeTaxRate() {
		Settlement negative = Settlement.of(new BigDecimal("10.00"), -1.0, 2);
		assertEquals("0.00", negative.tax().toPlainString());
		assertEquals("10.00", negative.net().toPlainString());

		Settlement tooHigh = Settlement.of(new BigDecimal("10.00"), 5.0, 2);
		assertEquals("10.00", tooHigh.tax().toPlainString());
	}
}
