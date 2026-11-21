package com.xiyuan.plugmc.lowhp;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowHpConfigTest {
	@Test
	void clampsUnsafeValues() {
		LowHpConfig config = new LowHpConfig(
			true, 0.0, 5.0, -3, -1.0, 9.0f, true, true,
			Set.of(Alert.A), "", "", 0, null, null, 0, false, "x"
		);
		assertEquals(0.05, config.thresholdPercent(), 1e-6);
		assertEquals(1.0, config.healPercent(), 1e-6);
		assertEquals(0, config.cooldownSeconds());
		assertEquals(0.1, config.volume(), 1e-6);
		assertEquals(2.0f, config.pitch(), 1e-6);
		assertEquals(1, config.port());
		assertEquals(java.util.List.of(97, 0), config.minFormat());
		assertEquals(java.util.List.of(97, 99), config.maxFormat());
		assertEquals(1, config.joinDelayTicks());
	}

	@Test
	void healLineNeverBelowThreshold() {
		LowHpConfig config = new LowHpConfig(
			true, 0.4, 0.2, 60, 1.0, 1.0f, true, true,
			Set.of(Alert.A, Alert.B), "", "", 20103,
			java.util.List.of(96, 0), java.util.List.of(98, 5), 60, false, "x"
		);
		assertTrue(config.healPercent() >= config.thresholdPercent());
		assertEquals(2, config.alertList().size());
		assertEquals(java.util.List.of(96, 0), config.minFormat());
		assertEquals(java.util.List.of(98, 5), config.maxFormat());
	}
}
