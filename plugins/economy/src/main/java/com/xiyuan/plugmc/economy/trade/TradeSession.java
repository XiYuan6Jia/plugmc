package com.xiyuan.plugmc.economy.trade;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 双人交易窗口：0-17 为自己可放置的物品，18-35 展示对方物品，45 取消，49 确认。
 */
public final class TradeSession implements InventoryHolder {
	public static final int OWN_START = 0;
	public static final int OWN_END = 18;
	public static final int OTHER_START = 18;
	public static final int OTHER_END = 36;
	public static final int CANCEL_SLOT = 45;
	public static final int CONFIRM_SLOT = 49;
	public static final int STATUS_SLOT = 53;

	private final UUID initiator;
	private final UUID partner;
	private final String initiatorName;
	private final String partnerName;
	private final Inventory initiatorInventory;
	private final Inventory partnerInventory;
	private boolean initiatorConfirmed;
	private boolean partnerConfirmed;
	private boolean finished;

	public TradeSession(Player initiator, Player partner) {
		this.initiator = initiator.getUniqueId();
		this.partner = partner.getUniqueId();
		this.initiatorName = initiator.getName();
		this.partnerName = partner.getName();
		this.initiatorInventory = createInventory("&8交易 · " + partnerName);
		this.partnerInventory = createInventory("&8交易 · " + initiatorName);
	}

	private Inventory createInventory(String title) {
		return Bukkit.createInventory(this, 54, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
			.legacyAmpersand().deserialize(title));
	}

	@Override
	public Inventory getInventory() {
		return initiatorInventory;
	}

	public Inventory inventoryOf(Player player) {
		return isInitiator(player) ? initiatorInventory : partnerInventory;
	}

	public Inventory otherInventoryOf(Player player) {
		return isInitiator(player) ? partnerInventory : initiatorInventory;
	}

	public boolean isInitiator(Player player) {
		return initiator.equals(player.getUniqueId());
	}

	public boolean involves(Player player) {
		return initiator.equals(player.getUniqueId()) || partner.equals(player.getUniqueId());
	}

	public UUID initiator() {
		return initiator;
	}

	public UUID partner() {
		return partner;
	}

	public boolean confirmed(Player player) {
		return isInitiator(player) ? initiatorConfirmed : partnerConfirmed;
	}

	public boolean bothConfirmed() {
		return initiatorConfirmed && partnerConfirmed;
	}

	public boolean finished() {
		return finished;
	}

	public void finish() {
		this.finished = true;
	}

	public void resetConfirmations() {
		initiatorConfirmed = false;
		partnerConfirmed = false;
	}

	public void toggleConfirm(Player player) {
		if (isInitiator(player)) {
			initiatorConfirmed = !initiatorConfirmed;
		} else {
			partnerConfirmed = !partnerConfirmed;
		}
	}

	public List<ItemStack> ownItems(Player player) {
		List<ItemStack> items = new ArrayList<>();
		Inventory inventory = inventoryOf(player);
		for (int slot = OWN_START; slot < OWN_END; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack != null && !stack.getType().isAir()) {
				items.add(stack.clone());
			}
		}
		return items;
	}

	public void clearOwn(Player player) {
		Inventory inventory = inventoryOf(player);
		for (int slot = OWN_START; slot < OWN_END; slot++) {
			inventory.setItem(slot, null);
		}
	}

	public void syncDisplay(Player owner) {
		Inventory own = inventoryOf(owner);
		Inventory other = otherInventoryOf(owner);
		for (int index = 0; index < OWN_END - OWN_START; index++) {
			ItemStack stack = own.getItem(OWN_START + index);
			other.setItem(OTHER_START + index, stack == null ? null : stack.clone());
		}
	}

	public void render() {
		renderOne(initiatorInventory, initiatorConfirmed, partnerConfirmed);
		renderOne(partnerInventory, partnerConfirmed, initiatorConfirmed);
	}

	private void renderOne(Inventory inventory, boolean ownConfirmed, boolean otherConfirmed) {
		ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
		for (int slot = OTHER_END; slot < 45; slot++) {
			inventory.setItem(slot, filler.clone());
		}
		inventory.setItem(CANCEL_SLOT, item(Material.RED_STAINED_GLASS_PANE, "&c取消交易", List.of(
			"&7双方物品会退回各自背包"
		)));
		inventory.setItem(CONFIRM_SLOT, item(
			ownConfirmed ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
			ownConfirmed ? "&e已确认，点击取消确认" : "&a确认交易",
			List.of("&7双方都确认后立即交换物品")
		));
		inventory.setItem(STATUS_SLOT, item(Material.PAPER, "&f交易状态", List.of(
			"&7你：" + (ownConfirmed ? "&a已确认" : "&c未确认"),
			"&7对方：" + (otherConfirmed ? "&a已确认" : "&c未确认")
		)));
	}

	private ItemStack item(Material material, String name, List<String> lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
				.legacyAmpersand().deserialize(name));
			meta.lore(lore.stream()
				.map(line -> net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
					.legacyAmpersand().deserialize(line))
				.toList());
			stack.setItemMeta(meta);
		}
		return stack;
	}
}
