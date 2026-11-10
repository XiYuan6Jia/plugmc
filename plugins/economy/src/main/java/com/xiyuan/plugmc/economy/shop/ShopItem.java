package com.xiyuan.plugmc.economy.shop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.List;

public record ShopItem(
	String id,
	String category,
	Material material,
	BigDecimal buyPrice,
	BigDecimal sellPrice,
	int dailyBuyLimit,
	int dailySellLimit,
	List<String> aliases,
	boolean exactMatch,
	boolean sellable,
	ItemStack template
) {
	public ShopItem {
		aliases = List.copyOf(aliases);
		template = template == null ? null : template.clone();
	}

	public ItemStack createStack(int amount) {
		ItemStack stack = template == null ? new ItemStack(material) : template.clone();
		stack.setAmount(Math.min(amount, Math.max(1, material.getMaxStackSize())));
		return stack;
	}

	public boolean matches(ItemStack stack, boolean allowCustomItems) {
		if (stack == null || stack.getType() != material) {
			return false;
		}
		if (exactMatch) {
			return template != null && stack.isSimilar(template);
		}
		return allowCustomItems || !hasCustomMeta(stack);
	}

	private static boolean hasCustomMeta(ItemStack stack) {
		ItemMeta meta = stack.getItemMeta();
		return meta != null && (meta.hasDisplayName() || meta.hasLore() || !meta.getEnchants().isEmpty());
	}
}
