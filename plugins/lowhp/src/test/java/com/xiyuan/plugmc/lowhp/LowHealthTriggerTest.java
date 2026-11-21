package com.xiyuan.plugmc.lowhp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowHealthTriggerTest {
	@Test
	void triggersAtOrBelowThirtyPercent() {
		assertEquals(6.0, AlertService.threshold(20.0, 0.3), 1e-6);
		assertTrue(AlertService.lowHealth(6.0, 20.0, 0.3));
		assertTrue(AlertService.lowHealth(1.0, 20.0, 0.3));
		assertFalse(AlertService.lowHealth(6.1, 20.0, 0.3));
		assertFalse(AlertService.lowHealth(20.0, 20.0, 0.3));
	}

	@Test
	void worksWithCustomMaxHealth() {
		// 40 点血的玩家阈值为 12 点
		assertTrue(AlertService.lowHealth(12.0, 40.0, 0.3));
		assertFalse(AlertService.lowHealth(12.5, 40.0, 0.3));
		assertEquals(12.0, AlertService.threshold(40.0, 0.3), 1e-6);
	}

	@Test
	void deadPlayersNeverTrigger() {
		assertFalse(AlertService.lowHealth(0.0, 20.0, 0.3));
		assertFalse(AlertService.lowHealth(-5.0, 20.0, 0.3));
	}
}
