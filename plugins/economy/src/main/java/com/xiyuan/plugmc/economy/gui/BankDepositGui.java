package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.bank.BankService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 建材银行批量存入页：把建材放进上方格子，一键全部存入。
 */
public final class BankDepositGui {
	private static final int INPUT_SLOTS = 27;

	private final BankService bank;
	private final BankGui mainGui;

	public BankDepositGui(BankService bank, BankGui mainGui) {
		this.bank = bank;
		this.mainGui = mainGui;
	}

	public void open(Player player) {
		if (!bank.enabled()) {
			player.sendMessage("建材银行未开启。");
			return;
		}
		SellMenuHolder holder = new SellMenuHolder(6, "&8建材银行 · 批量存入", INPUT_SLOTS);
		holder.fill(filler());
		holder.set(31, info(player), null);
		holder.set(38, button(Material.ARROW, "&e返回建材银行", List.of()), this::back);
		holder.set(40, button(Material.LIME_STAINED_GLASS_PANE, "&a一键全部存入", List.of(
			"&7存入上方所有可存的建材",
			"&7不支持的物品会留在格子里",
			"&8关闭界面时剩余物品退回背包"
		)), p -> deposit(p, holder));
		holder.set(42, button(Material.BARRIER, "&c关闭", List.of(
			"&7关闭时格子里的物品会退回背包"
		)), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	private void back(Player player) {
		player.closeInventory();
		mainGui.openMain(player);
	}

	private void deposit(Player player, SellMenuHolder holder) {
		List<ItemStack> offered = holder.drainInput();
		if (offered.isEmpty()) {
			player.sendMessage("请先把要存入的建材放进上方格子。");
			return;
		}
		List<ItemStack> leftovers = new ArrayList<>();
		bank.depositBatch(player, offered, leftovers);
		int slot = 0;
		for (ItemStack leftover : leftovers) {
			while (slot < INPUT_SLOTS && holder.getInventory().getItem(slot) != null) {
				slot++;
			}
			if (slot >= INPUT_SLOTS) {
				returnToPlayer(player, leftover);
				continue;
			}
			holder.getInventory().setItem(slot, leftover);
		}
		holder.set(31, info(player), null);
		player.sendMessage("可继续放入物品，或关闭界面取回剩余物品。");
	}

	private static void returnToPlayer(Player player, ItemStack stack) {
		var leftovers = player.getInventory().addItem(stack.clone());
		for (ItemStack leftover : leftovers.values()) {
			player.getWorld().dropItemNaturally(player.getLocation(), leftover);
		}
	}

	private ItemStack info(Player player) {
		return button(Material.LANTERN, "&e批量存入", List.of(
			"&7把建材放入上方 " + INPUT_SLOTS + " 格",
			"&7点击下方「一键全部存入」",
			"&7只收商店目录内的干净建材",
			"&7当前额度：&e" + bank.format(bank.creditOf(player.getUniqueId())),
			"&7不支持的物品会留在格子里"
		));
	}

	private ItemStack button(Material material, String name, List<String> lore) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component(name));
			if (!lore.isEmpty()) {
				meta.lore(MenuText.components(lore));
			}
			item.setItemMeta(meta);
		}
		return item;
	}

	private ItemStack filler() {
		return button(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
	}
}
