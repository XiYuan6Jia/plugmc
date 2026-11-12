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
		// 控制台（或 RCON）代玩家打开菜单：运维排查与线上验证用。
		// 注意 Paper 26.3 下 `execute as <玩家> run menu` 的 sender 仍是远程控制台，
		// 拿不到 Player，所以这里只能显式传玩家名。
		if (args.length == 1 && !(sender instanceof Player)) {
			if (!sender.hasPermission("server-menu.admin")) {
				sender.sendMessage("你没有权限替其他玩家打开菜单。");
				return true;
			}
			Player target = org.bukkit.Bukkit.getPlayerExact(args[0]);
			if (target == null) {
				sender.sendMessage("找不到在线玩家：" + args[0]);
				return true;
			}
			menuManager.open(target);
			sender.sendMessage("已为 " + target.getName() + " 打开服务器菜单。");
			return true;
		}
		// 控制台触发某个槽位（等价于玩家点击这一格）。
		// 服务器运维常常没法替玩家点鼠标（自动化环境下注入鼠标会抢走前台焦点），
		// 这条路径让「点按钮之后到底跳到哪个界面」可以只靠命令验证。
		if (args.length == 3 && args[0].equalsIgnoreCase("click")) {
			if (!sender.hasPermission("server-menu.admin")) {
				sender.sendMessage("你没有权限替其他玩家触发菜单按钮。");
				return true;
			}
			Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
			if (target == null) {
				sender.sendMessage("找不到在线玩家：" + args[1]);
				return true;
			}
			int slot;
			try {
				slot = Integer.parseInt(args[2]);
			} catch (NumberFormatException exception) {
				sender.sendMessage("槽位必须是非负整数：" + args[2]);
				return true;
			}
			org.bukkit.inventory.InventoryView view = target.getOpenInventory();
			if (view.getTopInventory().getHolder() instanceof MenuHolder holder) {
				menuManager.handleClick(target, holder, slot);
				sender.sendMessage("已触发 " + target.getName() + " 的第 " + slot + " 格。");
			} else {
				sender.sendMessage(target.getName() + " 当前没有打开服务器菜单。");
			}
			return true;
		}
		if (args.length != 0) {
			sender.sendMessage("用法：/menu [reload]、/menu click <玩家> <槽位> 或（控制台）/menu <玩家>");
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
