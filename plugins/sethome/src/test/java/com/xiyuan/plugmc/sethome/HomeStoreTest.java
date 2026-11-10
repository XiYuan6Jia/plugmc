package com.xiyuan.plugmc.sethome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeStoreTest {
	@Test
	void blankNameFallsBackToHome() {
		assertEquals("home", HomeStore.normalizeName(null));
		assertEquals("home", HomeStore.normalizeName("   "));
	}

	@Test
	void nameIsTrimmedAndLowercased() {
		assertEquals("base", HomeStore.normalizeName(" Base "));
	}

	@Test
	void rejectsInvalidNames() {
		assertFalse(HomeStore.validName(""));
		assertFalse(HomeStore.validName("a b"));
		assertFalse(HomeStore.validName("a".repeat(17)));
	}

	@Test
	void acceptsChineseAndSymbols() {
		assertTrue(HomeStore.validName("家"));
		assertTrue(HomeStore.validName("my-home_1"));
	}
}
