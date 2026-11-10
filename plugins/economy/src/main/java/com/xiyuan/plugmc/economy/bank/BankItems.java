package com.xiyuan.plugmc.economy.bank;

import com.xiyuan.plugmc.economy.shop.ShopItem;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;

/**
 * 判定物品是否“干净”：只接收商店目录里的普通物品，避免通过自定义 NBT
 * 或容器内容在存入/取出之间夹带额外物品。
 */
public final class BankItems {
	private BankItems() {
	}

	public static boolean isPlain(ShopItem item, ItemStack stack) {
		if (item == null || stack == null || stack.getAmount() <= 0 || stack.getType().isAir()) {
			return false;
		}
		if (!item.matches(stack, false)) {
			return false;
		}
		ItemMeta meta = stack.getItemMeta();
		if (meta == null) {
			return true;
		}
		if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || meta.hasCustomModelData()) {
			return false;
		}
		if (!meta.getPersistentDataContainer().isEmpty()) {
			return false;
		}
		if (meta.hasAttributeModifiers() || meta.isUnbreakable()) {
			return false;
		}
		if (meta instanceof Damageable damageable && damageable.getDamage() > 0) {
			return false;
		}
		if (meta instanceof BlockDataMeta blockData && blockData.hasBlockData()) {
			return false;
		}
		if (meta instanceof BannerMeta banner && banner.numberOfPatterns() > 0) {
			return false;
		}
		if (meta instanceof SuspiciousStewMeta stew && stew.hasCustomEffects()) {
			return false;
		}
		if (meta instanceof FireworkMeta firework && !firework.getEffects().isEmpty()) {
			return false;
		}
		if (meta instanceof FireworkEffectMeta effect && effect.hasEffect()) {
			return false;
		}
		return true;
	}
}
