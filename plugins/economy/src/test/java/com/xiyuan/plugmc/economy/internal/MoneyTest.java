package com.xiyuan.plugmc.economy.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {
	@Test
	void parsesAndNormalizesToConfiguredScale() {
		assertEquals(new BigDecimal("100.00"), Money.parse("100", 2, false));
		assertEquals(new BigDecimal("1.20"), Money.parse("1.2", 2, false));
	}

	@Test
	void rejectsTooManyDecimalPlaces() {
		assertThrows(IllegalArgumentException.class, () -> Money.parse("1.234", 2, false));
	}

	@Test
	void rejectsNonPositiveAmountsUnlessZeroIsAllowed() {
		assertThrows(IllegalArgumentException.class, () -> Money.parse("0", 2, false));
		assertThrows(IllegalArgumentException.class, () -> Money.parse("-1", 2, true));
		assertEquals(new BigDecimal("0.00"), Money.parse("0", 2, true));
	}

	@Test
	void formatsWithCurrencySymbol() {
		assertEquals("$100.00", Money.format(new BigDecimal("100"), "$", 2));
		assertEquals("100.00", Money.format(new BigDecimal("100"), null, 2));
	}
}
