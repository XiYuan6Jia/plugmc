package com.xiyuan.plugmc.servermenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class MenuText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private MenuText() {
	}

	static Component component(String text) {
		return LEGACY.deserialize(text);
	}
}
