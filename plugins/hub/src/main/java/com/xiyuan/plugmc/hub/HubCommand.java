package com.xiyuan.plugmc.hub;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /hub [back|info|check|parkour|setspawn|build|reload] */
public final class HubCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"back", "info", "check", "parkour", "setspawn", "build", "reload"
	);
	private static final List<String> PARKOUR_SUBCOMMANDS = List.of("quit", "top", "best");

	private final HubPlugin plugin;

	public HubCommand(HubPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			Player player = requirePlayer(sender);
			if (player == null) {
				return true;
			}
			if (!plugin.hubWorld().isHub(player.getLocation())) {
				plugin.hubManager().enter(player);
			} else {
				player.teleport(plugin.hubWorld().spawn(plugin.settings()));
				player.sendMessage(HubText.component("&7已把你送回主城出生点。"));
			}
			return true;
		}

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "back" -> {
				Player player = requirePlayer(sender);
				if (player != null) {
					plugin.hubManager().back(player);
				}
			}
			case "info" -> info(sender);
			case "check" -> check(sender);
			case "parkour" -> parkour(sender, args);
			case "setspawn" -> setspawn(sender);
			case "build" -> build(sender);
			case "reload" -> reload(sender);
			default -> usage(sender, label);		}
		return true;
	}

	private void info(CommandSender sender) {
		if (!plugin.hubWorld().ready()) {
			sender.sendMessage(HubText.component("&c主城维度未加载。"));
			return;
		}
		HubConfig config = plugin.settings();
		Location spawn = plugin.hubWorld().spawn(config);
		sender.sendMessage(HubText.component("&6主城维度 &f" + plugin.hubWorld().world().getName()
			+ " &7（虚空生成，" + config.radius() * 2 + "×" + config.radius() * 2 + " 大厅）"));
		sender.sendMessage(HubText.component("&7出生点：&f"
			+ String.format("%.1f, %.1f, %.1f", spawn.getX(), spawn.getY(), spawn.getZ())
			+ " &7在线玩家：&f" + plugin.hubWorld().world().getPlayers().size()));
		sender.sendMessage(HubText.component("&7大厅已生成：&f"
			+ (plugin.marker().isBuilt() ? "是" : "否")
			+ " &7跑酷：&f" + (plugin.parkour() != null && plugin.parkour().course() != null
				? config.parkourEnabled() ? "已启用" : "已关闭"
				: "未生成")));
		sender.sendMessage(HubText.component("&7难度：&f" + plugin.hubWorld().world().getDifficulty()
			+ " &7锁血：&f" + config.lockHealth()
			+ " &7锁饱食度：&f" + config.lockFood()));
	}

	private void check(CommandSender sender) {
		if (!sender.hasPermission("hub.admin")) {
			sender.sendMessage(HubText.component("&c你没有权限自检主城大厅。"));
			return;
		}
		if (!plugin.hubWorld().ready()) {
			sender.sendMessage(HubText.component("&c主城维度未加载。"));
			return;
		}
		sender.sendMessage(HubText.component("&6主城大厅结构自检"));
		for (String line : HubLobby.verify(plugin.hubWorld().world(), plugin.settings(), plugin.marker().isBuilt())) {
			sender.sendMessage(HubText.component(line));
		}
		ParkourManager parkour = plugin.parkour();
		if (parkour != null && parkour.course() != null) {
			sender.sendMessage(HubText.component("&6跑酷赛道自检"));
			for (String line : parkour.course().verify(plugin.hubWorld().world())) {
				sender.sendMessage(HubText.component(line));
			}
		}
	}

	private void parkour(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		ParkourManager parkour = plugin.parkour();
		if (parkour == null || parkour.course() == null) {
			player.sendMessage(HubText.component("&c跑酷赛道未启用，请联系管理员。"));
			return;
		}
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "join";
		switch (action) {
			case "join", "start" -> parkour.join(player);
			case "quit", "leave" -> parkour.quit(player, true);
			case "best" -> player.sendMessage(HubText.component("&6你的跑酷最佳成绩：&e" + parkour.bestOf(player)));
			case "top" -> {
				List<String> lines = parkour.topLines(10);
				if (lines.isEmpty()) {
					player.sendMessage(HubText.component("&7还没有人完成跑酷。"));
					return;
				}
				player.sendMessage(HubText.component("&6跑酷排行榜"));
				for (String line : lines) {
					player.sendMessage(HubText.component(line));
				}
			}
			default -> player.sendMessage(HubText.component("&e用法：/hub parkour [quit|top|best]"));
		}
	}

	private void setspawn(CommandSender sender) {
		if (!sender.hasPermission("hub.admin")) {
			sender.sendMessage(HubText.component("&c你没有权限设置主城出生点。"));
			return;
		}
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (!plugin.hubWorld().isHub(player.getLocation())) {
			player.sendMessage(HubText.component("&c请站在主城大厅里再执行该命令。"));
			return;
		}
		plugin.hubWorld().world().setSpawnLocation(player.getLocation());
		plugin.getConfig().set("spawn.x", player.getLocation().getX());
		plugin.getConfig().set("spawn.y", player.getLocation().getY());
		plugin.getConfig().set("spawn.z", player.getLocation().getZ());
		plugin.getConfig().set("spawn.yaw", (double) player.getLocation().getYaw());
		plugin.getConfig().set("spawn.pitch", (double) player.getLocation().getPitch());
		plugin.saveConfig();
		plugin.reloadSettings();
		player.sendMessage(HubText.component("&a主城出生点已更新。"));
	}

	private void build(CommandSender sender) {
		if (!sender.hasPermission("hub.admin")) {
			sender.sendMessage(HubText.component("&c你没有权限重建主城大厅。"));
			return;
		}
		if (!plugin.hubWorld().ready()) {
			sender.sendMessage(HubText.component("&c主城维度未加载。"));
			return;
		}
		sender.sendMessage(HubText.component("&7正在重建主城大厅…"));
		plugin.hubWorld().ensureLobby(plugin.settings(), true);
		plugin.ensureParkour(true);
		sender.sendMessage(HubText.component("&a主城大厅与跑酷赛道已重建。"));
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("hub.admin")) {
			sender.sendMessage(HubText.component("&c你没有权限重载主城配置。"));
			return;
		}
		plugin.reloadSettings();
		plugin.hubWorld().applyRules(plugin.settings());
		plugin.ensureParkour(false);
		sender.sendMessage(HubText.component("&a主城配置已重载（世界名修改需重启生效）。"));
	}

	private void usage(CommandSender sender, String label) {
		sender.sendMessage(HubText.component("&e用法：/" + label
			+ " [back|info|check|parkour|setspawn|build|reload]"));
	}

	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) {
			if (!player.hasPermission("hub.use")) {
				player.sendMessage(HubText.component("&c你没有权限进入主城大厅。"));
				return null;
			}
			return player;
		}
		sender.sendMessage("只有玩家可以使用该命令。");
		return null;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			return SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("parkour")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			return PARKOUR_SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
		}
		return List.of();
	}
}
