package com.xiyuan.plugmc.servermenu;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MenuCommand implements CommandExecutor {
	private final ServerMenuPlugin plugin;
	private final MenuManager menuManager;

	public MenuCommand(ServerMenuPlugin plugin, MenuManager menuManager) {
		this.plugin = plugin;
		this.menuManager = menuManager;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equalsIgnoreCase("moneyhud")) {
			if (!(sender instanceof Player player)) {
				sender.sendMessage("只有玩家可以切换金币 HUD。");
				return true;
			}
			plugin.toggleHud(player);
			player.sendMessage(MenuText.component("&a金币 HUD 已"
				+ (plugin.isHudEnabled(player) ? "开启。" : "关闭。")));
			return true;
		}

		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			if (!sender.hasPermission("server-menu.admin")) {
				sender.sendMessage("你没有权限重新加载菜单配置。");
				return true;
			}
			try {
				menuManager.reload();
			} catch (IllegalStateException exception) {
				plugin.getLogger().severe("菜单配置重载失败：" + exception.getMessage());
				sender.sendMessage("菜单配置无效：" + exception.getMessage());
				return true;
			}
			plugin.refreshHud();
			sender.sendMessage("服务器菜单配置已重载。");
			return true;
		}
		if (args.length != 0) {
			sender.sendMessage("用法：/menu [reload]");
			return true;
		}
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以打开服务器菜单。");
			return true;
		}
		menuManager.open(player);
		return true;
	}
}
