package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成交结算：一笔成交金额拆成「卖家净得 + 税费」，保证两者相加恰好等于成交额。
 */
public record Settlement(BigDecimal gross, BigDecimal tax, BigDecimal net) {
	public static Settlement of(BigDecimal gross, double taxRate, int decimalPlaces) {
		BigDecimal safeGross = Money.nonNegative(gross, decimalPlaces);
		double clampedRate = Math.max(0.0, Math.min(1.0, taxRate));
		BigDecimal tax = Money.ceil(safeGross.multiply(BigDecimal.valueOf(clampedRate)), decimalPlaces);
		BigDecimal net = safeGross.subtract(tax).setScale(decimalPlaces, RoundingMode.UNNECESSARY);
		return new Settlement(safeGross, tax, net);
	}
}
