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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class OrderGui {
	private static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());
	private static final int PAGE_SIZE = 45;
	private static final List<Material> ITEM_CATALOG = Arrays.stream(Material.values())
		.filter(material -> material.isItem() && !material.isAir())
		.filter(material -> !material.name().startsWith("LEGACY_"))
		.sorted(Comparator.comparing(Enum::name))
		.toList();

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
		holder.set(48, item(Material.COMPASS, "&b选择物品求购", List.of(
			"&7从全部物品中选择要收购的东西",
			"&8点击打开物品列表"
		)), p -> openItemPicker(p, 0));
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
				"&7从背包扣除对应物品",
				"&8物品送达买方邮箱，钱立即到账"
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
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir()) {
			player.sendMessage("请先把要收购的物品拿在主手，或点击「选择物品求购」。");
			return;
		}
		startPrice(player, hand.clone());
	}

	private void startPrice(Player player, ItemStack template) {
		chatPrompt.request(player, "请输入收购单价：", input -> {
			BigDecimal price;
			try {
				price = new BigDecimal(input);
			} catch (NumberFormatException exception) {
				player.sendMessage("单价无效：" + input);
				return;
			}
			if (price.signum() <= 0) {
				player.sendMessage("单价必须大于 0。");
				return;
			}
			ItemStack single = template.clone();
			single.setAmount(1);
			openQuantity(player, single, price, 1);
		});
	}

	/**
	 * 数量选择：用 -/+ 按钮调整，而不是固定档位。
	 */
	private void openQuantity(Player player, ItemStack template, BigDecimal price, int quantity) {
		int max = service.maxAffordable(player, price);
		int current = Math.max(1, Math.min(quantity, max));

		EconomyMenuHolder holder = new EconomyMenuHolder(3, "&8收购数量");
		holder.fill(filler());
		BigDecimal total = price.multiply(BigDecimal.valueOf(current));
		BigDecimal fee = service.estimateFee(price, current);
		holder.set(4, item(Material.PAPER, "&f" + template.getType().name(), List.of(
			"&7单价：&e" + service.format(price),
			"&7数量：&f" + current + " &8/ 最多 " + max,
			"&7托管总额：&e" + service.format(total),
			"&7手续费：&e" + service.format(fee),
			"&7余额：&e" + service.format(service.balance(player))
		)), null);
		holder.set(13, item(Material.LIME_STAINED_GLASS_PANE, "&a数量：" + current, List.of(
			"&7使用下方按钮增减数量"
		)), null);

		holder.set(10, quantityButton("-64", -64), p ->
			openQuantity(p, template, price, current - 64));
		holder.set(11, quantityButton("-16", -16), p ->
			openQuantity(p, template, price, current - 16));
		holder.set(12, quantityButton("-1", -1), p ->
			openQuantity(p, template, price, current - 1));
		holder.set(14, quantityButton("+1", 1), p ->
			openQuantity(p, template, price, current + 1));
		holder.set(15, quantityButton("+16", 16), p ->
			openQuantity(p, template, price, current + 16));
		holder.set(16, quantityButton("+64", 64), p ->
			openQuantity(p, template, price, current + 64));

		holder.set(18, item(Material.GOLD_BLOCK, "&6最大可买：" + max, List.of(
			"&7按当前余额与单价计算",
			"&8点击设为最大数量"
		)), p -> openQuantity(p, template, price, max));
		holder.set(22, item(Material.EMERALD_BLOCK, "&a确认发布订单", List.of(
			"&7收购 &f" + current + " 个 " + template.getType().name(),
			"&7托管 &e" + service.format(total),
			"&7手续费 &e" + service.format(fee)
		)), p -> create(p, template, price, current));
		holder.set(26, item(Material.ARROW, "&e返回收购列表", List.of()), p -> openList(p, 0));
		player.openInventory(holder.getInventory());
	}

	private ItemStack quantityButton(String label, int delta) {
		return item(
			delta > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
			(delta > 0 ? "&a" : "&c") + label + " 个",
			List.of("&7点击" + (delta > 0 ? "增加" : "减少") + " &f" + Math.abs(delta) + " &7个")
		);
	}

	private void create(Player player, ItemStack template, BigDecimal price, int amount) {
		player.closeInventory();
		if (service.create(player, template, price, amount)) {
			openList(player, 0);
		}
	}

	/**
	 * 全部物品选择页：列出所有可作为物品的材质，分页展示。
	 */
	private void openItemPicker(Player player, int page) {
		List<Material> items = ITEM_CATALOG;
		int pageCount = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int current = Math.max(0, Math.min(page, pageCount - 1));
		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			"&8选择收购物品 &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		int start = current * PAGE_SIZE;
		for (int index = 0; index < PAGE_SIZE && start + index < items.size(); index++) {
			Material material = items.get(start + index);
			holder.set(index, item(material, "&f" + material.name(), List.of(
				"&7点击选择该物品作为收购目标"
			)), p -> startPrice(p, new ItemStack(material)));
		}

		if (current > 0) {
			holder.set(45, item(Material.ARROW, "&e上一页", List.of()), p -> openItemPicker(p, current - 1));
		}
		holder.set(49, item(Material.ARROW, "&e返回收购列表", List.of()), p -> openList(p, 0));
		if (current + 1 < pageCount) {
			holder.set(53, item(Material.ARROW, "&e下一页", List.of()), p -> openItemPicker(p, current + 1));
		}
		player.openInventory(holder.getInventory());
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
