package com.xiyuan.plugmc.economy.bank;

import com.xiyuan.plugmc.economy.gui.BankGui;
import com.xiyuan.plugmc.economy.shop.ShopItem;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /bank 命令：打开建材银行、存取建材、查询估值。
 */
public final class BankCommand implements CommandExecutor, TabCompleter {
	private final BankService bank;
	private final BankGui gui;

	public BankCommand(BankService bank, BankGui gui) {
		this.bank = bank;
		this.gui = gui;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!bank.enabled()) {
			sender.sendMessage("建材银行未开启。");
			return true;
		}
		if (args.length == 0) {
			if (sender instanceof Player player) {
				gui.openMain(player);
			} else {
				sender.sendMessage("用法：/bank <deposit|withdraw|value|balance|reload>");
			}
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "deposit", "in", "存" -> {
				if (!(sender instanceof Player player)) {
					sender.sendMessage("只有玩家可以存入物品。");
					return true;
				}
				int amount = parseAmount(args.length > 1 ? args[1] : null);
				bank.depositHand(player, amount);
			}
			case "depositall", "deposit-all", "all", "全部", "存全部" -> {
				if (!(sender instanceof Player player)) {
					sender.sendMessage("只有玩家可以存入物品。");
					return true;
				}
				bank.depositInventory(player);
			}
			case "withdraw", "take", "out", "取" -> {
				if (!(sender instanceof Player player)) {
					sender.sendMessage("只有玩家可以取出物品。");
					return true;
				}
				if (args.length < 2) {
					sender.sendMessage("用法：/bank withdraw <商品> [数量]");
					return true;
				}
				Optional<ShopItem> item = bank.find(args[1]);
				if (item.isEmpty()) {
					player.sendMessage("建材银行没有这个物品：" + args[1]);
					return true;
				}
				int amount = args.length > 2 ? parseAmount(args[2]) : 1;
				if (amount <= 0) {
					amount = Integer.MAX_VALUE;
				}
				bank.withdraw(player, item.get(), amount);
			}
			case "value", "price", "估值" -> {
				if (args.length < 2) {
					ItemStack hand = sender instanceof Player player
						? player.getInventory().getItemInMainHand()
						: null;
					if (hand == null || hand.getType().isAir()) {
						sender.sendMessage("用法：/bank value <商品>");
						return true;
					}
					bank.match(hand).ifPresentOrElse(
						item -> sender.sendMessage(bank.describe(item)),
						() -> sender.sendMessage("主手物品不在建材银行范围内。")
					);
					return true;
				}
				Optional<ShopItem> item = bank.find(args[1]);
				if (item.isEmpty()) {
					sender.sendMessage("建材银行没有这个物品：" + args[1]);
				} else {
					sender.sendMessage(bank.describe(item.get()));
				}
			}
			case "balance", "额度" -> {
				if (!(sender instanceof Player player)) {
					sender.sendMessage("只有玩家可以查询银行额度。");
					return true;
				}
				player.sendMessage("建材银行总额度："
					+ bank.format(bank.creditOf(player.getUniqueId())));
				for (com.xiyuan.plugmc.economy.shop.ShopCategory category : bank.categories()) {
					long points = bank.creditOf(player.getUniqueId(), category.id());
					if (points > 0) {
						player.sendMessage(" - " + category.displayName() + "："
							+ bank.format(points));
					}
				}
				long legacy = bank.creditOf(player.getUniqueId(), BankPools.LEGACY);
				if (legacy > 0) {
					player.sendMessage(" - 历史额度：" + bank.format(legacy) + "（可跨类使用）");
				}
			}
			case "reload" -> {
				if (!sender.hasPermission("economy.bank.admin")) {
					sender.sendMessage("你没有权限重载建材银行配置。");
					return true;
				}
				bank.reload();
				sender.sendMessage("建材银行配置已重载，可用分类："
					+ String.join("、", bank.acceptedCategoryIds()));
			}
			case "save" -> {
				if (!sender.hasPermission("economy.bank.admin")) {
					sender.sendMessage("你没有权限保存建材银行数据。");
					return true;
				}
				bank.save();
				sender.sendMessage("建材银行数据已保存。");
			}
			default -> {
				sender.sendMessage("用法：/bank [deposit [数量|all] | depositall | withdraw <商品> [数量] "
					+ "| value <商品> | balance | reload | save]");
			}
		}
		return true;
	}

	private int parseAmount(String raw) {
		if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("all")) {
			return 0;
		}
		try {
			return Math.max(0, Integer.parseInt(raw));
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	@Override
	public List<String> onTabComplete(
		CommandSender sender,
		Command command,
		String alias,
		String[] args
	) {
		if (args.length == 1) {
			return filter(
				List.of("deposit", "depositall", "withdraw", "value", "balance", "reload", "save"),
				args[0]
			);
		}
		if (args.length == 2) {
			String sub = args[0].toLowerCase(Locale.ROOT);
			if (sub.equals("withdraw") || sub.equals("take") || sub.equals("out")
				|| sub.equals("value") || sub.equals("price")) {
				List<String> ids = new ArrayList<>();
				for (ShopItem item : bank.itemsForAll()) {
					ids.add(item.id());
				}
				return filter(ids, args[1]);
			}
			if (sub.equals("deposit") || sub.equals("in")) {
				return filter(List.of("1", "16", "64", "all"), args[1]);
			}
		}
		if (args.length == 3 && (args[0].equalsIgnoreCase("withdraw")
			|| args[0].equalsIgnoreCase("take") || args[0].equalsIgnoreCase("out"))) {
			return filter(List.of("1", "16", "64", "2304"), args[2]);
		}
		return List.of();
	}

	private List<String> filter(List<String> options, String prefix) {
		String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
		List<String> result = new ArrayList<>();
		for (String option : options) {
			if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
				result.add(option);
			}
		}
		return result;
	}
}
