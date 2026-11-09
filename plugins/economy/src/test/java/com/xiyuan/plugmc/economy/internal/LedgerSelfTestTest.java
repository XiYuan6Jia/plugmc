package com.xiyuan.plugmc.economy.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerSelfTestTest {
	@Test
	void allChecksPass() {
		List<LedgerSelfTest.Check> checks = LedgerSelfTest.run();
		assertTrue(checks.size() >= 10, "自检项过少：" + checks.size());
		List<String> failed = checks.stream()
			.filter(check -> !check.passed())
			.map(check -> check.name() + "=" + check.detail())
			.toList();
		assertTrue(failed.isEmpty(), "失败项：" + failed);
	}
}
