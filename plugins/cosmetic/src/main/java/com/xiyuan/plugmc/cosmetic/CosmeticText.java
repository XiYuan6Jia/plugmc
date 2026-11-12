package com.xiyuan.plugmc.cosmetic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 颜色文本工具：配置里统一用 {@code &} 开头的老式颜色码。
 */
final class CosmeticText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private CosmeticText() {
	}

	static Component component(String text) {
		if (text == null) {
			return Component.empty();
		}
		return LEGACY.deserialize(text);
	}
}
