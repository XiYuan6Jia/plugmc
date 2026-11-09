package com.xiyuan.plugmc.economy.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailConfigTest {
	@Test
	void expiryUsesConfiguredDays() {
		MailConfig config = new MailConfig(50, 30);
		long now = 1_000_000L;
		assertEquals(now + 30L * 24 * 60 * 60 * 1000, config.expiresAt(now));
		assertTrue(config.expiresAt(now) > now);
	}

	@Test
	void capacityAndDaysAreClamped() {
		MailConfig config = new MailConfig(0, 0);
		assertEquals(1, config.capacity());
		assertEquals(1, config.expireDays());
	}
}
