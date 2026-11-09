package com.xiyuan.plugmc.economy.mail;

import com.xiyuan.plugmc.economy.gui.MailGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

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

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equalsIgnoreCase("mail")) {
			return List.of();
		}
		if (args.length == 1) {
			return "claim".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("claim") : List.of();
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
			return List.of("all");
		}
		return List.of();
	}
}
