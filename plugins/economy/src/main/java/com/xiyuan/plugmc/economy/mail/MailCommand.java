package com.xiyuan.plugmc.economy.mail;

import com.xiyuan.plugmc.economy.gui.MailGui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

public final class MailCommand implements CommandExecutor, TabCompleter {
	private final MailService service;
	private final MailGui gui;

	public MailCommand(MailService service, MailGui gui) {
		this.service = service;
		this.gui = gui;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!command.getName().equalsIgnoreCase("mail")) {
			return false;
		}
		if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
			give(sender, args);
			return true;
		}
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以查看邮箱。");
			return true;
		}
		if (!player.hasPermission("economy.mail.use")) {
			player.sendMessage("你没有权限使用邮箱。");
			return true;
		}
		if (args.length == 0) {
			gui.open(player);
			return true;
		}

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "claim" -> {
				if (args.length < 2) {
					player.sendMessage("用法：/mail claim <id|all>");
					return true;
				}
				if (args[1].equalsIgnoreCase("all")) {
					int claimed = service.claimAll(player);
					player.sendMessage("已领取 " + claimed + " 封邮件。");
					return true;
				}
				try {
					service.claim(player, Long.parseLong(args[1]));
				} catch (NumberFormatException exception) {
					player.sendMessage("邮件编号必须是数字。");
				}
			}
			default -> player.sendMessage("用法：/mail [claim <id|all>]");
		}
		return true;
	}

	/**
	 * 管理端补偿/发放：把物品直接投递到玩家邮箱，玩家在线且背包有空位时会自动领取。
	 */
	private void give(CommandSender sender, String[] args) {
		if (!sender.hasPermission("economy.mail.admin")) {
			sender.sendMessage("你没有权限给玩家发送邮件。");
			return;
		}
		if (args.length < 4) {
			sender.sendMessage("用法：/mail give <玩家> <物品> <数量>");
			return;
		}
		OfflinePlayer target = Bukkit.getPlayerExact(args[1]) != null
			? Bukkit.getPlayerExact(args[1])
			: Bukkit.getOfflinePlayerIfCached(args[1]);
		if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
			sender.sendMessage("找不到玩家：" + args[1]);
			return;
		}
		Material material = Material.matchMaterial(args[2]);
		if (material == null || !material.isItem()) {
			sender.sendMessage("物品无效：" + args[2] + "（可用 minecraft:clay_ball 这类名称）");
			return;
		}
		int amount;
		try {
			amount = Integer.parseInt(args[3]);
		} catch (NumberFormatException exception) {
			sender.sendMessage("数量必须是正整数。");
			return;
		}
		if (amount <= 0) {
			sender.sendMessage("数量必须是正整数。");
			return;
		}

		int maxStack = Math.max(1, material.getMaxStackSize());
		int sent = 0;
		while (sent < amount) {
			int chunk = Math.min(amount - sent, maxStack);
			if (!service.sendItem(target.getUniqueId(), new ItemStack(material, chunk), "ADMIN_MAIL")) {
				break;
			}
			sent += chunk;
		}
		if (sent <= 0) {
			sender.sendMessage("邮箱已满，无法投递。");
			return;
		}
		String name = target.getName() == null ? args[1] : target.getName();
		if (sent < amount) {
			sender.sendMessage("已投递 " + sent + " 个 " + material.name() + " 给 " + name
				+ "，邮箱已满，剩余 " + (amount - sent) + " 个未发送。");
		} else {
			sender.sendMessage("已投递 " + sent + " 个 " + material.name() + " 给 " + name + " 的邮箱。");
		}
		Player online = target.getPlayer();
		if (online != null) {
			service.notify(online);
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equalsIgnoreCase("mail")) {
			return List.of();
		}
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			return List.of("claim", "give").stream().filter(option -> option.startsWith(prefix)).toList();
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
			return List.of("all");
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
			return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		}
		return List.of();
	}
}
