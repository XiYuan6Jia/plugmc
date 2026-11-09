package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.order.BuyOrder;
import com.xiyuan.plugmc.economy.order.OrderService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class OrderGui {
	private static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());
	private static final int PAGE_SIZE = 45;

	private final OrderService service;
	private final ChatPrompt chatPrompt;

	public OrderGui(OrderService service, ChatPrompt chatPrompt) {
		this.service = service;
		this.chatPrompt = chatPrompt;
	}

	public void openList(Player player, int page) {
		List<BuyOrder> orders = service.orders();
		int pageCount = Math.max(1, (orders.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int current = Math.max(0, Math.min(page, pageCount - 1));
		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			"&8收购订单 &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		if (orders.isEmpty()) {
			holder.set(22, item(Material.CHEST, "&7暂无收购订单", List.of("&8点击下方按钮发布收购")), null);
		} else {
			int start = current * PAGE_SIZE;
			for (int index = 0; index < PAGE_SIZE && start + index < orders.size(); index++) {
				BuyOrder order = orders.get(start + index);
				holder.set(index, icon(order), p -> openDetail(p, order.id(), page));
			}
		}

		if (current > 0) {
			holder.set(45, item(Material.ARROW, "&e上一页", List.of()), p -> openList(p, current - 1));
		}
		holder.set(49, GuiNavigation.backButton(), GuiNavigation::back);
		holder.set(50, item(Material.HOPPER, "&a发布收购（主手物品）", List.of(
			"&7用主手物品作为收购样本",
			"&8点击后输入单价"
		)), this::startCreate);
		if (current + 1 < pageCount) {
			holder.set(53, item(Material.ARROW, "&e下一页", List.of()), p -> openList(p, current + 1));
		}
		player.openInventory(holder.getInventory());
	}

	private void openDetail(Player player, long id, int backPage) {
		BuyOrder order = service.order(id);
		if (order == null || !order.active()) {
			player.sendMessage("该收购订单已结束。");
			openList(player, backPage);
			return;
		}

		EconomyMenuHolder holder = new EconomyMenuHolder(6, "&8收购 #" + id);
		holder.fill(filler());
		holder.set(13, icon(order), null);
		holder.set(4, item(Material.GOLD_INGOT, "&6余额", List.of(
			"&e" + service.format(service.balance(player))
		)), null);

		if (order.buyer().equals(player.getUniqueId())) {
			holder.set(31, item(Material.RED_STAINED_GLASS_PANE, "&c取消订单", List.of(
				"&7剩余托管资金退回余额"
			)), p -> {
				service.cancel(p, id);
				openList(p, backPage);
			});
		} else {
			holder.set(20, item(Material.LIME_STAINED_GLASS_PANE, "&a交付 1 个", List.of(
				"&7从背包扣除对应物品"
			)), p -> fill(p, id, 1, false, backPage));
			holder.set(21, item(Material.LIME_STAINED_GLASS_PANE, "&a交付 16 个", List.of()), p ->
				fill(p, id, 16, false, backPage));
			holder.set(22, item(Material.LIME_STAINED_GLASS_PANE, "&a交付全部", List.of(
				"&7最多交付订单剩余数量"
			)), p -> fill(p, id, 0, true, backPage));
		}

		holder.set(45, item(Material.ARROW, "&e返回收购列表", List.of()), p -> openList(p, backPage));
		holder.set(49, item(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	private void fill(Player player, long id, int amount, boolean all, int backPage) {
		service.fill(player, id, amount, all);
		openDetail(player, id, backPage);
	}

	private void startCreate(Player player) {
		if (player.getInventory().getItemInMainHand().getType().isAir()) {
			player.sendMessage("请先把要收购的物品拿在主手。");
			return;
		}
		chatPrompt.request(player, "请输入收购单价：", input -> {
			BigDecimal price;
			try {
				price = new BigDecimal(input);
			} catch (NumberFormatException exception) {
				player.sendMessage("单价无效：" + input);
				return;
			}
			openCreateQuantity(player, price);
		});
	}

	private void openCreateQuantity(Player player, BigDecimal price) {
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir()) {
			player.sendMessage("主手已经空了，发布取消。");
			openList(player, 0);
			return;
		}
		int available = hand.getAmount();
		EconomyMenuHolder holder = new EconomyMenuHolder(3, "&8收购数量");
		holder.fill(filler());
		holder.set(4, item(Material.PAPER, "&f" + hand.getType().name(), List.of(
			"&7单价：&e" + service.format(price),
			"&7托管总额：&e" + service.format(price.multiply(BigDecimal.valueOf(available)))
		)), null);
		holder.set(10, item(Material.LIME_STAINED_GLASS_PANE, "&a收购 1 个", List.of()), p ->
			create(p, price, 1, false));
		holder.set(12, item(Material.LIME_STAINED_GLASS_PANE, "&a收购 16 个", List.of()), p ->
			create(p, price, 16, false));
		holder.set(14, item(Material.LIME_STAINED_GLASS_PANE, "&a收购 64 个", List.of()), p ->
			create(p, price, 64, false));
		holder.set(16, item(Material.CHEST, "&a收购整组 &7(" + available + ")", List.of()), p ->
			create(p, price, available, true));
		holder.set(22, item(Material.ARROW, "&e返回收购列表", List.of()), p -> openList(p, 0));
		player.openInventory(holder.getInventory());
	}

	private void create(Player player, BigDecimal price, int amount, boolean all) {
		player.closeInventory();
		if (service.create(player, price, amount, all)) {
			openList(player, 0);
		}
	}

	private ItemStack icon(BuyOrder order) {
		ItemStack icon = order.template().clone();
		icon.setAmount(Math.max(1, Math.min(64, order.remaining())));
		ItemMeta meta = icon.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component("&f#" + order.id() + " &7· &e"
				+ order.template().getType().name()));
			meta.lore(MenuText.components(List.of(
				"&7单价：&e" + service.format(order.unitPrice()),
				"&7剩余：&f" + order.remaining(),
				"&7买家：&f" + (order.buyerName().isBlank() ? "未知" : order.buyerName()),
				"&7截止：&f" + TIME.format(Instant.ofEpochMilli(order.expiresAt())),
				"&8点击查看详情"
			)));
			icon.setItemMeta(meta);
		}
		return icon;
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

	private ItemStack filler() {
		return item(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
	}
}
