package com.xiyuan.plugmc.minigames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

/** & 颜色码与便捷组件构造。 */
public final class Text {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private Text() {
	}

	public static Component of(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}

	public static List<Component> list(List<String> lines) {
		return lines.stream().map(Text::of).toList();
	}
}
