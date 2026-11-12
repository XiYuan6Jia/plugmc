package com.xiyuan.plugmc.cosmetic;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /cosmetic 命令。
 *
 * <p>为什么 wear / unwear / toggle / clear 都带可选玩家参数：控制台没有"主手"，
 * 而 {@code execute as <玩家> run cosmetic ...} 走的是 {@link ProxiedCommandSender}，
 * 光判 {@code sender instanceof Player} 会一直失败。两条路都留着，运维怎么方便怎么来。</p>
 */
final class CosmeticCommand implements CommandExecutor, TabCompleter {

	private final CosmeticPlugin plugin;

	CosmeticCommand(CosmeticPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			Player self = asPlayer(sender);
			if (self != null) {
				self.openInventory(plugin.gui().build(self));
			} else {
				sender.sendMessage(CosmeticText.component("&7用法：/cosmetic <reload|clear|toggle|debug>"));
			}
			return true;
		}

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "reload" -> {
				if (!sender.hasPermission("cosmetic.admin")) {
					sender.sendMessage(CosmeticText.component("&c没有权限"));
					return true;
				}
				plugin.reload();
				sender.sendMessage(CosmeticText.component("&a配置已重新载入：" + plugin.config().slots.size() + " 个槽位"));
			}
			case "clear" -> {
				Player target = resolve(sender, args.length > 1 ? args[1] : null);
				if (target == null) {
					sender.sendMessage(CosmeticText.component("&c找不到该玩家"));
					return true;
				}
				if (!target.equals(asPlayer(sender)) && !sender.hasPermission("cosmetic.admin")) {
					sender.sendMessage(CosmeticText.component("&c只能取下自己的装备"));
					return true;
				}
				List<ItemStack> removed = plugin.wardrobe().clear(target.getUniqueId());
				plugin.wardrobe().save();
				plugin.attributes().refresh(target);
				plugin.refresh(target);

				int count = 0;
				for (ItemStack one : removed) {
					var leftovers = target.getInventory().addItem(one);
					for (ItemStack drop : leftovers.values()) {
						target.getWorld().dropItemNaturally(target.getLocation(), drop);
					}
					count++;
				}
				sender.sendMessage(CosmeticText.component("&e已清空 " + target.getName() + " 的 " + count + " 件装饰"));
			}
			case "toggle" -> {
				Player target = resolve(sender, args.length > 1 ? args[1] : null);
				if (target == null) {
					sender.sendMessage(CosmeticText.component("&7用法：/cosmetic toggle [玩家]"));
					return true;
				}
				CosmeticDisplays displays = plugin.displayDisplays();
				if (displays.isHidden(target.getUniqueId())) {
					displays.setHidden(target.getUniqueId(), false);
					plugin.refresh(target);
					target.sendMessage(CosmeticText.component("&a装饰已显示"));
				} else {
					displays.removeAll(target.getUniqueId(), true);
					displays.setHidden(target.getUniqueId(), true);
					target.sendMessage(CosmeticText.component("&7装饰已隐藏"));
				}
			}
			case "debug" -> {
				if (!sender.hasPermission("cosmetic.admin")) {
					sender.sendMessage(CosmeticText.component("&c没有权限"));
					return true;
				}
				sender.sendMessage(CosmeticText.component("&b装备外观诊断"));
				sender.sendMessage(CosmeticText.component("&7槽位定义：&f" + plugin.config().slots.size()
					+ " &7属性透传：&f" + plugin.config().attributesEnabled));
				sender.sendMessage(CosmeticText.component("&7活动装饰实体：&f" + plugin.displayDisplays().activeCount()
					+ " &7透传修饰符：&f" + plugin.attributes().activeModifierCount()));
				Player self = asPlayer(sender);
				if (self != null) {
					self.sendMessage(CosmeticText.component("&7你的槽位：&f"
						+ plugin.wardrobe().slotIds(self.getUniqueId())));
				}
			}
			case "wear" -> {
				Player target = resolve(sender, args.length > 2 ? args[2] : null);
				if (target == null) {
					sender.sendMessage(CosmeticText.component("&7用法：/cosmetic wear <槽位> [玩家]"));
					sender.sendMessage(CosmeticText.component("&7控制台可用：/cosmetic wear head CosProbe"));
					return true;
				}
				if (args.length < 2) {
					sender.sendMessage(CosmeticText.component("&7用法：/cosmetic wear <槽位> [玩家]"));
					return true;
				}
				CosmeticConfig.Slot slot = plugin.config().slot(args[1]);
				if (slot == null) {
					sender.sendMessage(CosmeticText.component("&c没有这个槽位：" + args[1]));
					return true;
				}
				ItemStack inHand = target.getInventory().getItemInMainHand();
				if (inHand.getType().isAir()) {
					sender.sendMessage(CosmeticText.component("&7" + target.getName() + " 的主手是空的"));
					return true;
				}
				plugin.gui().wearFromHand(target, slot);
				plugin.attributes().refresh(target);
				plugin.refresh(target);
				sender.sendMessage(CosmeticText.component("&a已把 " + target.getName() + " 的主手物品放进 " + slot.name()));
			}
			case "unwear" -> {
				Player target = resolve(sender, args.length > 2 ? args[2] : null);
				if (target == null) {
					sender.sendMessage(CosmeticText.component("&7用法：/cosmetic unwear <槽位> [玩家]"));
					sender.sendMessage(CosmeticText.component("&7控制台可用：/cosmetic unwear head CosProbe"));
					return true;
				}
				if (args.length < 2) {
					sender.sendMessage(CosmeticText.component("&7用法：/cosmetic unwear <槽位> [玩家]"));
					return true;
				}
				CosmeticConfig.Slot slot = plugin.config().slot(args[1]);
				if (slot == null) {
					sender.sendMessage(CosmeticText.component("&c没有这个槽位：" + args[1]));
					return true;
				}
				ItemStack removed = plugin.gui().unwear(target, slot);
				plugin.attributes().refresh(target);
				plugin.refresh(target);
				sender.sendMessage(CosmeticText.component(removed == null
					? "&7这个槽位是空的"
					: "&e已取下 " + slot.name() + " &7的物品"));
			}
			case "gui" -> {
				Player target = resolve(sender, args.length > 1 ? args[1] : null);
				if (target == null) {
					sender.sendMessage(CosmeticText.component("&7用法：/cosmetic gui [玩家]"));
					sender.sendMessage(CosmeticText.component("&7控制台可用：/cosmetic gui <玩家名>"));
					return true;
				}
				if (!target.equals(asPlayer(sender)) && !sender.hasPermission("cosmetic.admin")) {
					sender.sendMessage(CosmeticText.component("&c只能打开自己的装备外观"));
					return true;
				}
				target.openInventory(plugin.gui().build(target));
				sender.sendMessage(CosmeticText.component("&a已为 " + target.getName() + " 打开装备外观界面"));
			}
			case "slots" -> {
				StringBuilder sb = new StringBuilder();
				for (CosmeticConfig.Slot slot : plugin.config().slots) {
					if (sb.length() > 0) {
						sb.append(", ");
					}
					sb.append(slot.id());
				}
				sender.sendMessage(CosmeticText.component("&7可用槽位：&f" + sb));
			}
			default -> sender.sendMessage(CosmeticText.component(
				"&7用法：/cosmetic [gui [玩家]|reload|clear [玩家]|toggle [玩家]|debug|wear <槽位> [玩家]|unwear <槽位> [玩家]|slots]"));
		}
		return true;
	}

	/**
	 * 把参数解析成玩家：不给名字就用命令执行者自身。
	 */
	private Player resolve(CommandSender sender, String name) {
		if (name == null) {
			return asPlayer(sender);
		}
		Player exact = Bukkit.getPlayerExact(name);
		if (exact != null) {
			return exact;
		}
		try {
			UUID id = UUID.fromString(name);
			return Bukkit.getPlayer(id);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	/**
	 * 从命令来源里取出真实的玩家：{@code execute as <玩家>} 时来源是 {@link ProxiedCommandSender}，
	 * 玩家在 {@code getCallee()} 里而不是 {@code sender} 本身。
	 */
	private static Player asPlayer(CommandSender sender) {
		if (sender instanceof Player player) {
			return player;
		}
		if (sender instanceof ProxiedCommandSender proxy) {
			if (proxy.getCallee() instanceof Player callee) {
				return callee;
			}
		}
		return null;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			List<String> options = new ArrayList<>(List.of("clear", "toggle", "debug", "wear", "unwear", "slots"));
			if (sender.hasPermission("cosmetic.admin")) {
				options.add("reload");
				options.add("gui");
			}
			return options.stream()
				.filter(one -> one.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
				.toList();
		}
		if (args.length == 2) {
			if (args[0].equalsIgnoreCase("wear") || args[0].equalsIgnoreCase("unwear")) {
				return plugin.config().slots.stream()
					.map(CosmeticConfig.Slot::id)
					.filter(one -> one.startsWith(args[1].toLowerCase(Locale.ROOT)))
					.toList();
			}
			if ((args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("gui"))
				&& sender.hasPermission("cosmetic.admin")) {
				List<String> names = new ArrayList<>();
				for (Player player : Bukkit.getOnlinePlayers()) {
					names.add(player.getName());
				}
				return names.stream()
					.filter(one -> one.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
					.toList();
			}
			return List.of();
		}
		if (args.length == 3
			&& (args[0].equalsIgnoreCase("wear") || args[0].equalsIgnoreCase("unwear"))
			&& sender.hasPermission("cosmetic.admin")) {
			List<String> names = new ArrayList<>();
			for (Player player : Bukkit.getOnlinePlayers()) {
				names.add(player.getName());
			}
			return names.stream()
				.filter(one -> one.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
				.toList();
		}
		return List.of();
	}
}
