package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额解析、精度校验与格式化。所有公开金额都使用配置中的小数位。
 */
public final class Money {
	private Money() {
	}

	public static BigDecimal parse(String raw, int decimalPlaces, boolean allowZero) {
		if (raw == null) {
			throw new IllegalArgumentException("金额不能为空");
		}
		try {
			return normalize(new BigDecimal(raw.trim()), decimalPlaces, allowZero);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("无效金额：" + raw, exception);
		}
	}

	public static BigDecimal normalize(BigDecimal amount, int decimalPlaces, boolean allowZero) {
		BigDecimal scaled = scale(amount, decimalPlaces);
		if (allowZero ? scaled.signum() < 0 : scaled.signum() <= 0) {
			throw new IllegalArgumentException("金额必须是" + (allowZero ? "非负" : "正") + "数");
		}
		return scaled;
	}

	/**
	 * 只校验小数位，不校验正负；用于读取账本/快照中的系统账户余额。
	 */
	public static BigDecimal scale(BigDecimal amount, int decimalPlaces) {
		if (amount == null) {
			throw new IllegalArgumentException("金额不能为空");
		}
		if (decimalPlaces < 0 || decimalPlaces > 6) {
			throw new IllegalArgumentException("decimal-places 必须在 0 到 6 之间");
		}
		if (amount.scale() > decimalPlaces) {
			throw new IllegalArgumentException("金额最多有 " + decimalPlaces + " 位小数：" + amount.toPlainString());
		}
		return amount.setScale(decimalPlaces, RoundingMode.UNNECESSARY);
	}

	public static BigDecimal positive(BigDecimal amount, int decimalPlaces) {
		return normalize(amount, decimalPlaces, false);
	}

	public static BigDecimal nonNegative(BigDecimal amount, int decimalPlaces) {
		return normalize(amount, decimalPlaces, true);
	}

	public static BigDecimal zero(int decimalPlaces) {
		return BigDecimal.ZERO.setScale(decimalPlaces, RoundingMode.UNNECESSARY);
	}

	public static BigDecimal ceil(BigDecimal amount, int decimalPlaces) {
		if (amount == null) {
			throw new IllegalArgumentException("金额不能为空");
		}
		return amount.setScale(decimalPlaces, RoundingMode.CEILING);
	}

	public static String format(BigDecimal amount, String symbol, int decimalPlaces) {
		BigDecimal normalized = nonNegative(amount, decimalPlaces);
		return (symbol == null ? "" : symbol) + normalized.toPlainString();
	}

	public static String plain(BigDecimal amount, int decimalPlaces) {
		return scale(amount, decimalPlaces).toPlainString();
	}
}
