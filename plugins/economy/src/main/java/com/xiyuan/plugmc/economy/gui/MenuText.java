package com.xiyuan.plugmc.economy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

public final class MenuText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private MenuText() {
	}

	public static Component component(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}

	public static List<Component> components(List<String> lines) {
		return lines.stream().map(MenuText::component).toList();
	}
}
