package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.trade.TradeManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线玩家列表：点击头颅发起交易请求，若对方已向自己发起请求则点击直接接受。
 */
public final class TradeGui {
	private final TradeManager tradeManager;

	public TradeGui(TradeManager tradeManager) {
		this.tradeManager = tradeManager;
	}

	public void open(Player player) {
		List<Player> others = new ArrayList<>();
		for (Player online : Bukkit.getOnlinePlayers()) {
			if (!online.getUniqueId().equals(player.getUniqueId())) {
				others.add(online);
			}
		}

		int rows = Math.min(6, Math.max(1, (others.size() + 8) / 9 + 1));
		EconomyMenuHolder holder = new EconomyMenuHolder(rows, "&8选择交易对象");
		int capacity = (rows - 1) * 9;
		for (int index = 0; index < others.size() && index < capacity; index++) {
			Player target = others.get(index);
			boolean incoming = tradeManager.hasPendingFrom(player, target);
			holder.set(index, head(target, incoming), p -> {
				if (incoming && tradeManager.hasPendingFrom(p, target)) {
					tradeManager.accept(p, target.getName());
				} else {
					tradeManager.request(p, target.getName());
				}
			});
		}
		if (others.isEmpty()) {
			holder.set(4, item(Material.BARRIER, "&7暂无其他在线玩家", List.of("&8邀请朋友进服后再交易")), null);
		}
		int footer = (rows - 1) * 9;
		holder.set(footer + 4, GuiNavigation.backButton(), GuiNavigation::back);
		holder.set(footer + 8, item(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	private ItemStack head(Player target, boolean incoming) {
		ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
		if (skull.getItemMeta() instanceof SkullMeta meta) {
			meta.setOwningPlayer(target);
			meta.displayName(MenuText.component((incoming ? "&a" : "&f") + target.getName()));
			meta.lore(MenuText.components(incoming
				? List.of("&a对方请求与你交易", "&e点击接受")
				: List.of("&7点击发送交易请求")));
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
				meta.lore(MenuText.components(lore));
			}
			stack.setItemMeta(meta);
		}
		return stack;
	}
}
