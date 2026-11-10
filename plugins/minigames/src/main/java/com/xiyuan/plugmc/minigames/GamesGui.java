package com.xiyuan.plugmc.minigames;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 小游戏界面：主菜单 + 21 点牌桌 + 转盘 + 老虎机。
 * 所有资金通过 {@link EconomyBridge} 走经济账本，投注/派彩原因各自独立。
 */
public final class GamesGui {
	private static final int MENU_BLACKJACK = 20;
	private static final int MENU_WHEEL = 22;
	private static final int MENU_SLOTS = 24;
	private static final int MENU_INFO = 4;
	private static final int MENU_CLOSE = 40;

	private static final int BET_DOWN = 20;
	private static final int BET_UP = 24;
	private static final int START = 22;
	private static final int BACK = 45;
	private static final int CLOSE = 49;

	private final MinigamesPlugin plugin;
	private final EconomyBridge economy;

	public GamesGui(MinigamesPlugin plugin, EconomyBridge economy) {
		this.plugin = plugin;
		this.economy = economy;
	}

	public GamesConfig config() {
		return plugin.settings();
	}

	// ---------------------------------------------------------------- 菜单

	public void openMenu(Player player) {
		GamesHolder holder = new GamesHolder(GamesHolder.View.MENU, null, "&8小游戏 · 主城娱乐", 45, 0);
		fill(holder);
		holder.getInventory().setItem(MENU_INFO, item(
			Material.NETHER_STAR,
			"&6&l小游戏中心",
			List.of(
				"&7余额：&e" + money(player),
				"&7注额范围：&f" + config().minBet() + " ~ " + config().maxBet(),
				"&8主城大厅北侧的实体店可以直接右键柜台"
			)
		));
		holder.getInventory().setItem(MENU_BLACKJACK, item(
			GameType.BLACKJACK.icon(),
			"&6" + GameType.BLACKJACK.displayName(),
			List.of(
				"&7庄家 17 点停牌，黑杰克 3:2",
				"&7可以要牌 / 停牌 / 加倍",
				"&8点击开局"
			)
		));
		holder.getInventory().setItem(MENU_WHEEL, item(
			GameType.WHEEL.icon(),
			"&e" + GameType.WHEEL.displayName(),
			List.of(
				"&7返还 0 / 0.5 / 1 / 2 / 5 / 10 倍",
				"&8点击开局"
			)
		));
		holder.getInventory().setItem(MENU_SLOTS, item(
			GameType.SLOTS.icon(),
			"&b" + GameType.SLOTS.displayName(),
			List.of(
				"&7三连最高 30 倍，两个相同返还 1.1 倍",
				"&8点击开局"
			)
		));
		holder.getInventory().setItem(MENU_CLOSE, item(Material.BARRIER, "&c关闭", List.of()));
		player.openInventory(holder.getInventory());
	}

	// ---------------------------------------------------------------- 开局

	public void open(Player player, GameType game) {
		open(player, game, config().defaultBet(game));
	}

	public void open(Player player, GameType game, int bet) {
		if (!config().enabled(game)) {
			player.sendMessage(Text.of("&c该游戏暂时关闭。"));
			return;
		}
		if (!economy.ready()) {
			player.sendMessage(Text.of("&c经济系统未就绪，暂时不能下注。"));
			return;
		}
		switch (game) {
			case BLACKJACK -> {
				GamesHolder holder = new GamesHolder(
					GamesHolder.View.BLACKJACK, game, "&8" + game.displayName(), 54, config().clampBet(bet)
				);
				renderBlackjack(player, holder);
				player.openInventory(holder.getInventory());
			}
			case WHEEL -> {
				GamesHolder holder = new GamesHolder(
					GamesHolder.View.WHEEL, game, "&8" + game.displayName(), 45, config().clampBet(bet)
				);
				renderWheel(player, holder);
				player.openInventory(holder.getInventory());
			}
			case SLOTS -> {
				GamesHolder holder = new GamesHolder(
					GamesHolder.View.SLOTS, game, "&8" + game.displayName(), 45, config().clampBet(bet)
				);
				renderSlots(player, holder);
				player.openInventory(holder.getInventory());
			}
		}
	}

	// ---------------------------------------------------------------- 点击

	public void click(Player player, GamesHolder holder, int slot) {
		if (holder.spinning()) {
			player.sendMessage(Text.of("&7正在开奖，稍等一下…"));
			return;
		}
		switch (holder.view()) {
			case MENU -> {
				switch (slot) {
					case MENU_BLACKJACK -> open(player, GameType.BLACKJACK);
					case MENU_WHEEL -> open(player, GameType.WHEEL);
					case MENU_SLOTS -> open(player, GameType.SLOTS);
					case MENU_CLOSE -> player.closeInventory();
					default -> {
					}
				}
			}
			case BLACKJACK -> clickBlackjack(player, holder, slot);
			case WHEEL -> clickWheel(player, holder, slot);
			case SLOTS -> clickSlots(player, holder, slot);
		}
	}

	private void clickBlackjack(Player player, GamesHolder holder, int slot) {
		switch (slot) {
			case BET_DOWN -> {
				holder.bet(config().clampBet(holder.bet() - 1));
				renderBlackjack(player, holder);
			}
			case BET_UP -> {
				holder.bet(config().clampBet(holder.bet() + 1));
				renderBlackjack(player, holder);
			}
			case START -> {
				if (holder.table() != null && holder.table().phase() == BlackjackTable.Phase.PLAYER) {
					player.sendMessage(Text.of("&7本局还没结束，先要牌或停牌。"));
					return;
				}
				startBlackjack(player, holder);
			}
			case 30 -> {
				BlackjackTable table = holder.table();
				if (table == null || table.phase() != BlackjackTable.Phase.PLAYER) {
					return;
				}
				table.hit();
				settleBlackjack(player, holder);
			}
			case 32 -> {
				BlackjackTable table = holder.table();
				if (table == null || table.phase() != BlackjackTable.Phase.PLAYER) {
					return;
				}
				table.stand();
				settleBlackjack(player, holder);
			}
			case 34 -> {
				BlackjackTable table = holder.table();
				if (table == null || !table.canDouble()) {
					player.sendMessage(Text.of("&7只有起手两张牌时可以加倍。"));
					return;
				}
				if (!economy.canAfford(player, BigDecimal.valueOf(holder.bet()))) {
					player.sendMessage(Text.of("&c余额不足，无法加倍。"));
					return;
				}
				if (!economy.withdraw(player, BigDecimal.valueOf(holder.bet()), holder.game().betReason())) {
					player.sendMessage(Text.of("&c加倍扣款失败。"));
					return;
				}
				table.doubleDown();
				settleBlackjack(player, holder);
			}
			case BACK -> openMenu(player);
			case CLOSE -> player.closeInventory();
			default -> {
			}
		}
	}

	private void startBlackjack(Player player, GamesHolder holder) {
		int bet = holder.bet();
		if (!economy.canAfford(player, BigDecimal.valueOf(bet))) {
			player.sendMessage(Text.of("&c余额不足，注额需要 " + bet + "。"));
			return;
		}
		if (!economy.withdraw(player, BigDecimal.valueOf(bet), GameType.BLACKJACK.betReason())) {
			player.sendMessage(Text.of("&c下注失败，请稍后再试。"));
			return;
		}
		holder.table(new BlackjackTable(bet, config().blackjackPayout()));
		holder.status("");
		holder.settled(false);
		settleBlackjack(player, holder);
	}

	private void settleBlackjack(Player player, GamesHolder holder) {
		BlackjackTable table = holder.table();
		if (table == null) {
			return;
		}
		if (table.phase() == BlackjackTable.Phase.DONE && !holder.settled()) {
			holder.settled(true);
			BigDecimal payout = table.payout();
			if (payout.signum() > 0) {
				economy.deposit(player, payout, GameType.BLACKJACK.winReason());
			}
			player.sendMessage(Text.of("&6[21点] &f" + table.message()
				+ (payout.signum() > 0 ? " &7（到账 " + economy.format(payout) + "）" : "")
				+ " &7余额 " + money(player)));
		}
		renderBlackjack(player, holder);
	}

	private void clickWheel(Player player, GamesHolder holder, int slot) {
		switch (slot) {
			case BET_DOWN -> {
				holder.bet(config().clampBet(holder.bet() - 1));
				renderWheel(player, holder);
			}
			case BET_UP -> {
				holder.bet(config().clampBet(holder.bet() + 1));
				renderWheel(player, holder);
			}
			case START -> spinWheel(player, holder);
			case BACK -> openMenu(player);
			case CLOSE -> player.closeInventory();
			default -> {
			}
		}
	}

	private void spinWheel(Player player, GamesHolder holder) {
		int bet = holder.bet();
		if (!cooldownReady(player)) {
			return;
		}
		if (!economy.canAfford(player, BigDecimal.valueOf(bet))) {
			player.sendMessage(Text.of("&c余额不足，注额需要 " + bet + "。"));
			return;
		}
		if (!economy.withdraw(player, BigDecimal.valueOf(bet), GameType.WHEEL.betReason())) {
			player.sendMessage(Text.of("&c下注失败，请稍后再试。"));
			return;
		}
		WheelGame.Sector result = WheelGame.spin();
		int target = WheelGame.sectors().indexOf(result);
		holder.spinning(true);
		holder.status("&7转盘转动中…");
		int[] step = {0};
		new BukkitRunnable() {
			@Override
			public void run() {
				if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder(false) != holder) {
					cancel();
					return;
				}
				if (step[0] >= 14) {
					holder.highlight(target);
					holder.spinning(false);
					BigDecimal payout = WheelGame.payout(bet, result);
					if (payout.signum() > 0) {
						economy.deposit(player, payout, GameType.WHEEL.winReason());
					}
					holder.status(payout.signum() > 0
						? "&a命中 " + result.label() + " &7→ &e" + economy.format(payout)
						: "&7落到 " + result.label());
					player.sendMessage(Text.of("&6[转盘] &f" + result.label()
						+ (payout.signum() > 0 ? " &7到账 &e" + economy.format(payout) : " &7没中")
						+ " &7余额 " + money(player)));
					renderWheel(player, holder);
					cancel();
					return;
				}
				holder.highlight(ThreadLocalRandom.current().nextInt(WheelGame.sectors().size()));
				renderWheel(player, holder);
				step[0]++;
			}
		}.runTaskTimer(plugin, 0L, 2L);
	}

	private void clickSlots(Player player, GamesHolder holder, int slot) {
		switch (slot) {
			case BET_DOWN -> {
				holder.bet(config().clampBet(holder.bet() - 1));
				renderSlots(player, holder);
			}
			case BET_UP -> {
				holder.bet(config().clampBet(holder.bet() + 1));
				renderSlots(player, holder);
			}
			case START -> spinSlots(player, holder);
			case BACK -> openMenu(player);
			case CLOSE -> player.closeInventory();
			default -> {
			}
		}
	}

	private void spinSlots(Player player, GamesHolder holder) {
		int bet = holder.bet();
		if (!cooldownReady(player)) {
			return;
		}
		if (!economy.canAfford(player, BigDecimal.valueOf(bet))) {
			player.sendMessage(Text.of("&c余额不足，注额需要 " + bet + "。"));
			return;
		}
		if (!economy.withdraw(player, BigDecimal.valueOf(bet), GameType.SLOTS.betReason())) {
			player.sendMessage(Text.of("&c下注失败，请稍后再试。"));
			return;
		}
		int[] result = {
			SlotsGame.randomIndex(),
			SlotsGame.randomIndex(),
			SlotsGame.randomIndex()
		};
		holder.spinning(true);
		holder.status("&7滚轮转动中…");
		int[] step = {0};
		new BukkitRunnable() {
			@Override
			public void run() {
				if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder(false) != holder) {
					cancel();
					return;
				}
				if (step[0] >= 12) {
					System.arraycopy(result, 0, holder.reels(), 0, 3);
					holder.spinning(false);
					BigDecimal payout = SlotsGame.payout(bet, result);
					if (payout.signum() > 0) {
						economy.deposit(player, payout, GameType.SLOTS.winReason());
					}
					holder.status(payout.signum() > 0
						? "&a" + SlotsGame.describe(result) + " &7→ &e" + economy.format(payout)
						: "&7没有组合");
					player.sendMessage(Text.of("&6[老虎机] &f"
						+ SlotsGame.symbol(result[0]).label() + " &8| "
						+ SlotsGame.symbol(result[1]).label() + " &8| "
						+ SlotsGame.symbol(result[2]).label()
						+ (payout.signum() > 0 ? " &7到账 &e" + economy.format(payout) : " &7没中")
						+ " &7余额 " + money(player)));
					renderSlots(player, holder);
					cancel();
					return;
				}
				holder.reels()[0] = SlotsGame.randomIndex();
				holder.reels()[1] = SlotsGame.randomIndex();
				holder.reels()[2] = SlotsGame.randomIndex();
				renderSlots(player, holder);
				step[0]++;
			}
		}.runTaskTimer(plugin, 0L, 2L);
	}

	private boolean cooldownReady(Player player) {
		return plugin.checkCooldown(player, config().cooldownSeconds());
	}

	// ---------------------------------------------------------------- 渲染

	private void renderBlackjack(Player player, GamesHolder holder) {
		fill(holder);
		BlackjackTable table = holder.table();
		holder.getInventory().setItem(4, item(Material.GOLD_INGOT, "&6注额 &e" + holder.bet(), List.of(
			"&7余额：&e" + money(player),
			"&7庄家 17 点停牌，黑杰克按 &e" + config().blackjackPayout() + " &7倍赔付",
			table == null ? "&8点击「开始新局」发牌" : "&8" + table.message()
		)));
		if (table != null) {
			List<BlackjackTable.Card> dealer = table.dealerCards();
			for (int index = 0; index < dealer.size() && index < 5; index++) {
				holder.getInventory().setItem(9 + index, card(dealer.get(index), false));
			}
			List<BlackjackTable.Card> mine = table.playerCards();
			for (int index = 0; index < mine.size() && index < 5; index++) {
				holder.getInventory().setItem(27 + index, card(mine.get(index), true));
			}
			holder.getInventory().setItem(18, item(Material.BOOK, "&7庄家点数：&f" + table.dealerValue(),
				List.of("&8翻牌前仅供示意")));
			holder.getInventory().setItem(36, item(Material.BOOK, "&7你的点数：&f" + table.playerValue(),
				List.of(table.doubled() ? "&8已加倍" : "&8")));
		}
		boolean playing = table != null && table.phase() == BlackjackTable.Phase.PLAYER;
		holder.getInventory().setItem(BET_DOWN, item(Material.RED_STAINED_GLASS_PANE, "&c注额 -1",
			List.of("&7当前 &f" + holder.bet())));
		holder.getInventory().setItem(BET_UP, item(Material.LIME_STAINED_GLASS_PANE, "&a注额 +1",
			List.of("&7当前 &f" + holder.bet())));
		holder.getInventory().setItem(START, item(Material.EMERALD_BLOCK,
			playing ? "&7本局进行中" : "&a开始新局", List.of("&7下注 &e" + holder.bet())));
		holder.getInventory().setItem(30, item(Material.LIME_WOOL, "&a要牌",
			List.of(playing ? "&7再要一张" : "&8先开始新局")));
		holder.getInventory().setItem(32, item(Material.RED_WOOL, "&c停牌",
			List.of(playing ? "&7交给庄家" : "&8先开始新局")));
		holder.getInventory().setItem(34, item(Material.GOLD_BLOCK, "&6加倍",
			List.of("&7注额翻倍并只补一张", "&8起手两张牌时可用")));
		holder.getInventory().setItem(BACK, item(Material.ARROW, "&e返回小游戏菜单", List.of()));
		holder.getInventory().setItem(CLOSE, item(Material.BARRIER, "&c关闭", List.of()));
	}

	private void renderWheel(Player player, GamesHolder holder) {
		fill(holder);
		holder.getInventory().setItem(4, item(Material.GOLD_INGOT, "&6注额 &e" + holder.bet(), List.of(
			"&7余额：&e" + money(player),
			"&7扇区权重：空手 32% / 0.5× 30% / 1× 22% / 2× 10% / 5× 5% / 10× 1%",
			holder.status().isBlank() ? "&8点击「开始旋转」" : holder.status()
		)));
		List<WheelGame.Sector> sectors = WheelGame.sectors();
		for (int index = 0; index < sectors.size(); index++) {
			WheelGame.Sector sector = sectors.get(index);
			boolean active = holder.highlight() == index;
			holder.getInventory().setItem(11 + index, item(
				active ? Material.ORANGE_STAINED_GLASS_PANE : sector.material(),
				(active ? "&6&l▶ " : "") + sector.label(),
				List.of("&7权重 " + sector.weight() + "%")
			));
		}
		holder.getInventory().setItem(BET_DOWN, item(Material.RED_STAINED_GLASS_PANE, "&c注额 -1",
			List.of("&7当前 &f" + holder.bet())));
		holder.getInventory().setItem(BET_UP, item(Material.LIME_STAINED_GLASS_PANE, "&a注额 +1",
			List.of("&7当前 &f" + holder.bet())));
		holder.getInventory().setItem(START, item(Material.EMERALD_BLOCK, "&a开始旋转",
			List.of("&7下注 &e" + holder.bet())));
		holder.getInventory().setItem(40, item(Material.ARROW, "&e返回小游戏菜单", List.of()));
		holder.getInventory().setItem(44, item(Material.BARRIER, "&c关闭", List.of()));
	}

	private void renderSlots(Player player, GamesHolder holder) {
		fill(holder);
		holder.getInventory().setItem(4, item(Material.GOLD_INGOT, "&6注额 &e" + holder.bet(), List.of(
			"&7余额：&e" + money(player),
			"&7三连：樱桃 4× / 柠檬 6× / 铃铛 10× / 钻石 15× / 幸运7 30×",
			"&7两个相同返还 1.1 倍",
			holder.status().isBlank() ? "&8点击「开始转动」" : holder.status()
		)));
		int[] slots = {20, 22, 24};
		for (int index = 0; index < 3; index++) {
			SlotsGame.Symbol symbol = SlotsGame.symbol(holder.reels()[index]);
			holder.getInventory().setItem(slots[index], item(symbol.material(), symbol.label(),
				List.of("&8第 " + (index + 1) + " 个滚轮")));
		}
		holder.getInventory().setItem(BET_DOWN, item(Material.RED_STAINED_GLASS_PANE, "&c注额 -1",
			List.of("&7当前 &f" + holder.bet())));
		holder.getInventory().setItem(BET_UP, item(Material.LIME_STAINED_GLASS_PANE, "&a注额 +1",
			List.of("&7当前 &f" + holder.bet())));
		holder.getInventory().setItem(30, item(Material.EMERALD_BLOCK, "&a开始转动",
			List.of("&7下注 &e" + holder.bet())));
		holder.getInventory().setItem(40, item(Material.ARROW, "&e返回小游戏菜单", List.of()));
		holder.getInventory().setItem(44, item(Material.BARRIER, "&c关闭", List.of()));
	}

	private ItemStack card(BlackjackTable.Card card, boolean mine) {
		return item(card.value() == 11 ? Material.DIAMOND : Material.PAPER,
			(mine ? "&f" : "&7") + card.label(),
			List.of("&8点数 " + card.value()));
	}

	private void fill(GamesHolder holder) {
		ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
		for (int slot = 0; slot < holder.getInventory().getSize(); slot++) {
			holder.getInventory().setItem(slot, filler.clone());
		}
	}

	private ItemStack item(Material material, String name, List<String> lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.displayName(Text.of(name));
			if (!lore.isEmpty()) {
				List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
				for (String line : lore) {
					lines.add(Text.of(line));
				}
				meta.lore(lines);
			}
			stack.setItemMeta(meta);
		}
		return stack;
	}

	private String money(Player player) {
		return economy.format(economy.balance(player));
	}
}
