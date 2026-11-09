package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.api.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public final class LeaderboardGui {
	private static final int PAGE_SIZE = 45;

	private final EconomyService economy;

	public LeaderboardGui(EconomyService economy) {
		this.economy = economy;
	}

	public void open(Player player, int page) {
		List<EconomyService.BalanceEntry> entries = economy.topBalances(PAGE_SIZE * 4);
		int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int current = Math.max(0, Math.min(page, pageCount - 1));
		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			"&8余额排行 &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		if (entries.isEmpty()) {
			holder.set(22, item(Material.CHEST, "&7暂无排行数据", List.of("&8服务器还没有经济记录")), null);
		}

		int start = current * PAGE_SIZE;
		for (int index = 0; index < PAGE_SIZE && start + index < entries.size(); index++) {
			int rank = start + index + 1;
			EconomyService.BalanceEntry entry = entries.get(start + index);
			holder.set(index, head(rank, entry, player), null);
		}

		if (current > 0) {
			holder.set(45, item(Material.ARROW, "&e上一页", List.of()), p -> open(p, current - 1));
		}
		holder.set(48, GuiNavigation.backButton(), GuiNavigation::back);
		holder.set(49, item(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		holder.set(50, item(Material.GOLD_INGOT, "&6你的排名", List.of(
			"&7余额：&e" + economy.format(economy.getBalance(player.getUniqueId())),
			"&7名次：&f" + rankOf(player, entries)
		)), null);
		if (current + 1 < pageCount) {
			holder.set(53, item(Material.ARROW, "&e下一页", List.of()), p -> open(p, current + 1));
		}
		player.openInventory(holder.getInventory());
	}

	private String rankOf(Player player, List<EconomyService.BalanceEntry> entries) {
		for (int index = 0; index < entries.size(); index++) {
			if (entries.get(index).playerId().equals(player.getUniqueId())) {
				return Integer.toString(index + 1);
			}
		}
		return "未上榜";
	}

	private ItemStack head(int rank, EconomyService.BalanceEntry entry, Player viewer) {
		ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
		if (skull.getItemMeta() instanceof SkullMeta meta) {
			var offline = Bukkit.getOfflinePlayer(entry.playerId());
			meta.setOwningPlayer(offline);
			String color = entry.playerId().equals(viewer.getUniqueId()) ? "&a" : "&f";
			meta.displayName(MenuText.component("&e#" + rank + " " + color + entry.name()));
			meta.lore(MenuText.components(List.of(
				"&7余额：&e" + economy.format(entry.balance())
			)));
			skull.setItemMeta(meta);
		}
		return skull;
	}

	private ItemStack item(Material material, String name, List<String> lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component(name));
			if (!lore.isEmpty()) {
				meta.lore(MenuText.components(new ArrayList<>(lore)));
			}
			stack.setItemMeta(meta);
		}
		return stack;
	}

	private ItemStack filler() {
		return item(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
	}
}
