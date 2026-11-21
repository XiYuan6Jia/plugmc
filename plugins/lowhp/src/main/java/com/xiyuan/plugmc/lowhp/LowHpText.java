package com.xiyuan.plugmc.lowhp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class LowHpText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private LowHpText() {
	}

	public static Component component(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}

	public static String seconds(float value) {
		int total = Math.max(0, Math.round(value));
		return String.format("%d:%02d", total / 60, total % 60);
	}
}
