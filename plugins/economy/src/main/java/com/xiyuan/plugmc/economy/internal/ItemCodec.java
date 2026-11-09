package com.xiyuan.plugmc.economy.internal;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * 用 Paper 的物品字节序列化存储市场挂单/邮件中的物品。
 */
public final class ItemCodec {
	private static final int MAX_ENCODED_BYTES = 128 * 1024;

	private ItemCodec() {
	}

	public static String encode(ItemStack stack) {
		if (stack == null || stack.getType().isAir()) {
			throw new IllegalArgumentException("物品不能为空");
		}
		byte[] bytes = stack.serializeAsBytes();
		if (bytes.length > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("物品数据过大，无法托管");
		}
		return Base64.getEncoder().encodeToString(bytes);
	}

	public static ItemStack decode(String data) {
		if (data == null || data.isBlank()) {
			throw new IllegalArgumentException("物品数据为空");
		}
		try {
			return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("物品数据无法解析", exception);
		}
	}
}
