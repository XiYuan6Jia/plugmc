package com.xiyuan.plugmc.goathorn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** 消息文案：统一 & 色号转 Component。 */
final class HornText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private HornText() {
	}

	static Component component(String text) {
		return LEGACY.deserialize(text);
	}

	/** 去掉色号后的纯文本，打日志用。 */
	static String plain(Component component) {
		return component == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
	}
}
