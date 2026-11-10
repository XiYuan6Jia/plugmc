package com.xiyuan.plugmc.sethome;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HomeCommand implements CommandExecutor, TabCompleter {
	private final SetHomePlugin plugin;

	public HomeCommand(SetHomePlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以使用该命令。");
			return true;
		}
		if (!player.hasPermission("sethome.use")) {
			send(player, "&c你没有使用家园传送的权限。");
			return true;
		}
		switch (command.getName().toLowerCase(Locale.ROOT)) {
			case "sethome" -> setHome(player, args);
			case "home" -> goHome(player, args);
			case "delhome" -> deleteHome(player, args);
			case "homes" -> listHomes(player);
			case "deathpoint" -> deathPoint(player);
			default -> {
				return false;
			}
		}
		return true;
	}

	private void setHome(Player player, String[] args) {
		String name = HomeStore.normalizeName(args.length > 0 ? args[0] : null);
		if (!HomeStore.validName(name)) {
			send(player, "&c家园名称只能包含字母、数字、下划线、短横线或中文，最长 16 个字符。");
			return;
		}
		HomeStore store = plugin.homes();
		boolean exists = store.has(player.getUniqueId(), name);
		int max = plugin.maxHomesFor(player);
		if (!exists && store.count(player.getUniqueId()) >= max) {
			send(player, "&c家园数量已达上限（&f" + max + " &c个），请先用 &f/delhome <名称> &c删除。");
			return;
		}
		Location location = player.getLocation();
		store.setHome(player.getUniqueId(), player.getName(), name, location);
		send(player, "&a已" + (exists ? "更新" : "设置") + "家园 &f" + name + " &a于 &f"
			+ format(location) + "&a。");
	}

	private void goHome(Player player, String[] args) {
		String name = HomeStore.normalizeName(args.length > 0 ? args[0] : null);
		Location target = plugin.homes().home(player.getUniqueId(), name);
		if (target == null) {
			send(player, "&c没有名为 &f" + name + " &c的家园，使用 &f/homes &c查看已有的家。");
			return;
		}
		plugin.teleports().request(player, target, "家园 " + name);
	}

	private void deleteHome(Player player, String[] args) {
		String name = HomeStore.normalizeName(args.length > 0 ? args[0] : null);
		if (!plugin.homes().remove(player.getUniqueId(), name)) {
			send(player, "&c没有名为 &f" + name + " &c的家园。");
			return;
		}
		send(player, "&a已删除家园 &f" + name + "&a。");
	}

	private void listHomes(Player player) {
		List<String> names = plugin.homes().names(player.getUniqueId());
		if (names.isEmpty()) {
			send(player, "&7你还没有设置家园，使用 &f/sethome [名称] &7设置第一个家。");
			return;
		}
		player.sendMessage(HomeText.of("&e你的家园（&f" + names.size() + "&e/&f"
			+ plugin.maxHomesFor(player) + "&e）："));
		for (String name : names) {
			Location location = plugin.homes().home(player.getUniqueId(), name);
			Component line = HomeText.of(" &8- &f" + name + " &7" + format(location) + " ");
			line = line.append(HomeText.of("&a[传送]")
				.clickEvent(ClickEvent.runCommand("home " + name))
				.hoverEvent(HoverEvent.showText(HomeText.of("&7点击传送到 &f" + name))));
			line = line.append(HomeText.of(" ")).append(HomeText.of("&c[删除]")
				.clickEvent(ClickEvent.runCommand("delhome " + name))
				.hoverEvent(HoverEvent.showText(HomeText.of("&7点击删除 &f" + name))));
			player.sendMessage(line);
		}
	}

	private void deathPoint(Player player) {
		if (!plugin.deathpointEnabled()) {
			send(player, "&c死亡点传送已关闭。");
			return;
		}
		Location location = player.getLastDeathLocation();
		if (location == null || location.getWorld() == null) {
			send(player, "&7没有找到你的死亡地点记录。");
			return;
		}
		plugin.teleports().request(player, location, "死亡地点");
	}

	private static String format(Location location) {
		if (location == null || location.getWorld() == null) {
			return "未知位置";
		}
		return String.format(Locale.ROOT, "%s %.1f, %.1f, %.1f",
			location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
	}

	private static void send(CommandSender sender, String text) {
		sender.sendMessage(HomeText.of(text));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!(sender instanceof Player player) || args.length != 1) {
			return List.of();
		}
		String name = command.getName().toLowerCase(Locale.ROOT);
		if (!name.equals("home") && !name.equals("delhome")) {
			return List.of();
		}
		String prefix = args[0].toLowerCase(Locale.ROOT);
		List<String> matches = new ArrayList<>();
		for (String home : plugin.homes().names(player.getUniqueId())) {
			if (home.startsWith(prefix)) {
				matches.add(home);
			}
		}
		return matches;
	}
}
