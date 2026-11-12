package com.xiyuan.plugmc.cosmetic;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * 装饰槽里物品的落盘编解码。
 *
 * <p>必须保证「取下后原样可用」：只做字节序列化的往返，绝不 setType / 改 amount / 清 meta。
 * 编码后立刻解码回环比对，任何数据组件丢失都会在放进去的那一刻被发现，而不是等到取下时才炸。</p>
 */
final class CosmeticItems {
	private static final int MAX_ENCODED_BYTES = 128 * 1024;

	private CosmeticItems() {
	}

	static String encode(ItemStack stack) {
		if (stack == null || stack.getType().isAir()) {
			throw new IllegalArgumentException("物品不能为空");
		}
		byte[] bytes = stack.serializeAsBytes();
		if (bytes.length > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("物品数据过大，无法作为装饰保存");
		}
		String data = Base64.getEncoder().encodeToString(bytes);
		ItemStack back = decode(data);
		if (!back.isSimilar(stack)) {
			throw new IllegalArgumentException("物品数据无法完整保留（NBT/组件校验失败）");
		}
		return data;
	}

	static ItemStack decode(String data) {
		if (data == null || data.isBlank()) {
			throw new IllegalArgumentException("物品数据为空");
		}
		try {
			return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("物品数据无法解析", exception);
		}
	}

	/**
	 * 渲染用副本：装饰实体拿 clone，槽内保存的原始串不动。
	 */
	static ItemStack renderCopy(ItemStack stack) {
		return stack == null ? null : stack.clone();
	}
}
