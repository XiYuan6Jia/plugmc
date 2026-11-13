package com.xiyuan.plugmc.bazaar;

import com.xiyuan.plugmc.economy.api.TradeMenus;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 点摊位后弹出的选择界面。
 *
 * <p>一个摊位上的交易可能同时有售卖、拍卖、求购三种，而它们分属经济系统的三个界面。
 * 只有一种时直接打开那个界面；有两种以上时先弹这一屏让玩家自己挑，避免"点了人偶却
 * 打开了拍卖行"这种意外。</p>
 *
 * <p><b>为什么走命令而不是直接操作经济系统的 GUI</b>：经济系统的 {@code MarketGui}
 * 之类都是它自己 new 出来的内部对象，没有对外暴露。走命令等于走它自己的入口 ——
 * 打开的是同一个界面，玩家在里面点的每一步也都走原有校验（余额、税费、托管、限购），
 * 商业街这边不会掺进任何交易逻辑。</p>
 */
final class BazaarMenu implements InventoryHolder, Listener {
	/** 每种交易对应的经济系统入口命令（无参执行就是打开界面）。 */
	private static final Map<BazaarDeal.Kind, String> COMMANDS = new EnumMap<>(BazaarDeal.Kind.class);

	static {
		COMMANDS.put(BazaarDeal.Kind.MARKET, "market");
		COMMANDS.put(BazaarDeal.Kind.AUCTION, "auction");
		COMMANDS.put(BazaarDeal.Kind.ORDER, "order");
	}

	/** 只用来挂事件监听的空实例（每个菜单各自是一份 holder，监听器注册一次就够）。 */
	private static final BazaarMenu LISTENER = new BazaarMenu();

	static BazaarMenu listener() {
		return LISTENER;
	}

	/** 这一屏上每个按钮对应的交易类型，下标 i 的按钮占 slot i*2。 */
	private final List<BazaarDeal.Kind> kinds = new ArrayList<>();
	private Inventory inventory;
	/** 这一屏属于谁 —— 点按钮时要按这个人的 UUID 过滤界面。 */
	private java.util.UUID ownerId;

	private BazaarMenu() {
	}

	private BazaarMenu(List<BazaarDeal.Kind> kinds, java.util.UUID ownerId) {
		this.kinds.addAll(kinds);
		this.ownerId = ownerId;
	}

	/**
	 * 打开某个摊主的交易界面；只有一种交易时直接进那一屏。
	 *
	 * @param viewer 点击摊位的玩家
	 * @param tenant 摊主
	 */
	static void open(Player viewer, BazaarTenants.Tenant tenant) {
		List<BazaarDeal.Kind> present = new ArrayList<>();
		for (BazaarDeal.Kind kind : BazaarDeal.Kind.values()) {
			for (BazaarDeal deal : tenant.deals()) {
				if (deal.kind() == kind) {
					present.add(kind);
					break;
				}
			}
		}
		if (present.isEmpty()) {
			viewer.sendMessage(BazaarText.component("&7这个摊位已经空了。"));
			return;
		}
		java.util.UUID owner = tenant.ownerId();
		if (present.size() == 1) {
			run(viewer, present.get(0), owner);
			return;
		}
		BazaarMenu menu = new BazaarMenu(present, owner);
		menu.build(tenant);
		viewer.openInventory(menu.inventory);
	}

	/** 固定摊位：银行、官方商店直接开对应界面，活动摊没有界面只做提示。 */
	static void openFixed(Player viewer, String kind) {
		switch (kind.toLowerCase(java.util.Locale.ROOT)) {
			case "bank" -> Bukkit.dispatchCommand(viewer, "bank");
			case "official" -> Bukkit.dispatchCommand(viewer, "shop");
			default -> {
				viewer.sendMessage(BazaarText.component("&f&l活动摊"));
				viewer.sendMessage(BazaarText.component("&7限时活动，具体看服务器公告。"));
			}
		}
	}

	private void build(BazaarTenants.Tenant tenant) {
		int rows = (kinds.size() + 8) / 9;
		inventory = Bukkit.createInventory(this, Math.max(9, rows * 9),
			BazaarText.component("&8" + tenant.name() + " 的交易"));
		for (int i = 0; i < kinds.size(); i++) {
			inventory.setItem(i * 2, button(kinds.get(i), tenant));
		}
	}

	private static ItemStack button(BazaarDeal.Kind kind, BazaarTenants.Tenant tenant) {
		Material icon = switch (kind) {
			case MARKET -> Material.CHEST;
			case AUCTION -> Material.GOLDEN_HORSE_ARMOR;
			case ORDER -> Material.WRITABLE_BOOK;
		};
		ItemStack stack = new ItemStack(icon);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(BazaarText.component("&f&l" + kind.label()));
		List<Component> lore = new ArrayList<>();
		lore.add(BazaarText.component("&7" + tenant.kindSummary()));
		for (BazaarDeal deal : tenant.deals()) {
			if (deal.kind() == kind) {
				lore.add(BazaarText.component(BazaarText.dealLine(deal)));
			}
		}
		lore.add(BazaarText.component(""));
		lore.add(BazaarText.component("&e点一下打开"));
		meta.lore(lore);
		stack.setItemMeta(meta);
		return stack;
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getInventory().getHolder() instanceof BazaarMenu menu)) {
			return;
		}
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		int slot = event.getRawSlot();
		if (slot < 0 || slot >= menu.kinds.size() * 2 || slot % 2 != 0) {
			return;
		}
		BazaarDeal.Kind kind = menu.kinds.get(slot / 2);
		player.closeInventory();
		run(player, kind, menu.ownerId);
	}

	/**
	 * 交给经济系统打开「这个人」的界面。
	 *
	 * <p>优先走 {@link TradeMenus}（列表已按卖家过滤）；经济系统没提供该服务、
	 * 或这个人的对应交易刚好在这一瞬间消失了，就退回原来的无参命令 —— 至少能打开
	 * 全服列表，不会让玩家点了没反应。</p>
	 */
	private static void run(Player viewer, BazaarDeal.Kind kind, java.util.UUID ownerId) {
		var registration = Bukkit.getServicesManager().getRegistration(TradeMenus.class);
		TradeMenus menus = registration == null ? null : registration.getProvider();
		if (menus != null && ownerId != null) {
			boolean opened = switch (kind) {
				case MARKET -> menus.openMarketOf(viewer, ownerId);
				case AUCTION -> menus.openAuctionOf(viewer, ownerId);
				case ORDER -> menus.openOrderOf(viewer, ownerId);
			};
			if (opened) {
				return;
			}
			viewer.sendMessage(BazaarText.component("&7这些交易刚刚结束了，已为你打开全部列表。"));
		}
		String command = COMMANDS.get(kind);
		if (command != null) {
			Bukkit.dispatchCommand(viewer, command);
		}
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}
}
