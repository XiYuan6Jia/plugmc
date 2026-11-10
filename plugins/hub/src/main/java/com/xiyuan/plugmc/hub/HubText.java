package com.xiyuan.plugmc.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** & 颜色码转换为 Adventure 组件。 */
public final class HubText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private HubText() {
	}

	public static Component component(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}
}
