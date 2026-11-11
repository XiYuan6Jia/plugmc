package com.xiyuan.plugmc.ride;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /ride <玩家> 骑上去，/ride off 下来，/ride info 查看状态，/ride force 管理员强制下车。
 */
public final class RideCommand implements CommandExecutor, TabCompleter {
	private final RidePlugin plugin;

	public RideCommand(RidePlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以使用骑乘功能。");
			return true;
		}
		if (!plugin.settings().enabled()) {
			player.sendMessage(RideText.component("&c骑乘功能暂时关闭。"));
			return true;
		}
		if (!player.hasPermission("ride.use")) {
			player.sendMessage(RideText.component("&c骑乘功能暂时只开放给 OP。"));
			return true;
		}
		if (args.length == 0) {
			info(player);
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "off", "leave", "down", "下来" -> {
				if (!plugin.rides().dismount(player, "&7你下来了。")) {
					player.sendMessage(RideText.component("&7你没有在骑乘。"));
				}
			}
			case "info", "status", "状态" -> info(player);
			case "list", "列表" -> {
				StringBuilder builder = new StringBuilder();
				for (Player online : Bukkit.getOnlinePlayers()) {
					if (online.equals(player)) {
						continue;
					}
					if (builder.length() > 0) {
						builder.append("&7, ");
					}
					builder.append("&f").append(online.getName());
				}
				player.sendMessage(RideText.component("&6当前可骑乘的玩家："
					+ (builder.length() == 0 ? "&7（无）" : builder.toString())));
			}
			case "force", "强制" -> {
				if (!player.hasPermission("ride.admin")) {
					player.sendMessage(RideText.component("&c你没有权限强制其他玩家下车。"));
					return true;
				}
				if (args.length < 2) {
					player.sendMessage(RideText.component("&e用法：/ride force <玩家>"));
					return true;
				}
				Player target = Bukkit.getPlayerExact(args[1]);
				if (target == null) {
					player.sendMessage(RideText.component("&c玩家不在线：" + args[1]));
					return true;
				}
				plugin.rides().dismountAll(target, "&e管理员把你们放了下来。");
				boolean self = plugin.rides().dismount(target, null);
				player.sendMessage(RideText.component(self
					? "&a已让 &f" + target.getName() + " &a下车。"
					: "&7" + target.getName() + " 当前没有骑乘。"));
			}
			case "reload" -> {
				if (!player.hasPermission("ride.admin")) {
					player.sendMessage(RideText.component("&c你没有权限重载骑乘配置。"));
					return true;
				}
				plugin.reloadSettings();
				player.sendMessage(RideText.component("&a骑乘配置已重载。"));
			}
			default -> {
				Player carrier = Bukkit.getPlayerExact(args[0]);
				if (carrier == null || !carrier.isOnline()) {
					player.sendMessage(RideText.component("&c玩家不在线：" + args[0]));
					return true;
				}
				plugin.rides().mount(player, carrier);
			}
		}
		return true;
	}

	private void info(Player player) {
		UUID carrierId = plugin.rides().carrierOf(player.getUniqueId());
		if (carrierId != null) {
			Player carrier = Bukkit.getPlayer(carrierId);
			player.sendMessage(RideText.component("&6你正骑着：&f"
				+ (carrier == null ? carrierId : carrier.getName())
				+ "&7，潜行或 &f/ride off &7下来。"));
			return;
		}
		List<String> riders = new ArrayList<>();
		for (Player online : Bukkit.getOnlinePlayers()) {
			if (player.getUniqueId().equals(plugin.rides().carrierOf(online.getUniqueId()))) {
				riders.add(online.getName());
			}
		}
		if (!riders.isEmpty()) {
			player.sendMessage(RideText.component("&6骑在你身上的人：&f" + String.join("、", riders)));
		}
		long cooldown = plugin.rides().cooldownRemaining(player.getUniqueId());
		player.sendMessage(RideText.component("&7用法：&f/ride <玩家> &7骑上去，&f/ride off &7下来"
			+ (cooldown > 0 ? "，冷却 &f" + cooldown + " &7秒" : "")));
	}

	@Override
	public List<String> onTabComplete(
		CommandSender sender,
		Command command,
		String alias,
		String[] args
	) {
		if (args.length == 1) {
			List<String> options = new ArrayList<>(List.of("off", "info", "list", "reload", "force"));
			if (sender instanceof Player player) {
				for (Player online : Bukkit.getOnlinePlayers()) {
					if (!online.equals(player)) {
						options.add(online.getName());
					}
				}
			}
			return filter(options, args[0]);
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("force")) {
			return filter(
				Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
				args[1]
			);
		}
		return List.of();
	}

	private List<String> filter(List<String> options, String prefix) {
		String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
		return options.stream()
			.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
			.toList();
	}
}
