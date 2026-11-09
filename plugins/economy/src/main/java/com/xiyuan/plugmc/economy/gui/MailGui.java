package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.mail.MailEntry;
import com.xiyuan.plugmc.economy.mail.MailService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class MailGui {
	private static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());

	private final MailService service;

	public MailGui(MailService service) {
		this.service = service;
	}

	public void open(Player player) {
		open(player, 0);
	}

	private void open(Player player, int page) {
		List<MailEntry> entries = service.entries(player.getUniqueId());
		int pageSize = 45;
		int pageCount = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
		int current = Math.max(0, Math.min(page, pageCount - 1));
		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			"&8邮箱 &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		if (entries.isEmpty()) {
			holder.set(22, button(Material.CHEST, "&7邮箱是空的", List.of("&8市场/交易退回的物品会出现在这里")), null);
		} else {
			int start = current * pageSize;
			for (int index = 0; index < pageSize && start + index < entries.size(); index++) {
				MailEntry entry = entries.get(start + index);
				holder.set(index, icon(entry), p -> {
					if (service.claim(p, entry.id())) {
						open(p, current);
					}
				});
			}
		}

		if (current > 0) {
			holder.set(45, button(Material.ARROW, "&e上一页", List.of()), p -> open(p, current - 1));
		}
		holder.set(48, button(Material.HOPPER, "&a全部领取", List.of("&7领取所有能放进背包的邮件")), p -> {
			int claimed = service.claimAll(p);
			p.sendMessage("已领取 " + claimed + " 封邮件。");
			open(p, current);
		});
		holder.set(49, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		if (current + 1 < pageCount) {
			holder.set(53, button(Material.ARROW, "&e下一页", List.of()), p -> open(p, current + 1));
		}
		player.openInventory(holder.getInventory());
	}

	private ItemStack icon(MailEntry entry) {
		ItemStack icon = entry.hasItem() ? entry.item().clone() : new ItemStack(Material.PAPER);
		ItemMeta meta = icon.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component("&f邮件 #" + entry.id() + " &7· &e" + entry.source()));
			meta.lore(MenuText.components(List.of(
				"&7创建：" + TIME.format(Instant.ofEpochMilli(entry.createdAt())),
				"&7过期：" + TIME.format(Instant.ofEpochMilli(entry.expiresAt())),
				entry.hasItem() ? "&7物品数量：" + entry.item().getAmount() : "&7金额：" + entry.money(),
				"&a点击领取"
			)));
			icon.setItemMeta(meta);
		}
		return icon;
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
