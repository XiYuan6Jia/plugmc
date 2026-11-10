package com.xiyuan.plugmc.tpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class TpaText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private TpaText() {
	}

	static Component of(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}
}
