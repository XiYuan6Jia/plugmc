package com.xiyuan.plugmc.sethome;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class HomeText {
	static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private HomeText() {
	}

	static Component of(String text) {
		return LEGACY.deserialize(text);
	}
}
