package com.xiyuan.plugmc.train;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 统一的文本构造：配置与命令里的颜色码用 &amp; 写法。
 */
public final class TrainText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private TrainText() {
	}

	public static Component component(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}

	/** 去掉颜色码的纯文本，写日志时用（日志里不该带 § 前缀）。 */
	public static String plain(Component component) {
		return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
	}
}
