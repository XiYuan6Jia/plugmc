package com.xiyuan.plugmc.ride;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class RideText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private RideText() {
	}

	public static Component component(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}
}
