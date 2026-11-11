package com.xiyuan.plugmc.musicdiscs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class MusicText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private MusicText() {
	}

	public static Component component(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}

	public static java.util.List<Component> components(java.util.List<String> lines) {
		return lines.stream().map(MusicText::component).toList();
	}

	public static String duration(float seconds) {
		int total = Math.max(0, Math.round(seconds));
		return String.format("%d:%02d", total / 60, total % 60);
	}
}
