package com.xiyuan.plugmc.megastructures;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /mega [list|tp <id>|back|info|check|reload] */
public final class MegaCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"list", "tp", "back", "info", "check", "reload"
	);

	private final MegaPlugin plugin;

	public MegaCommand(MegaPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			Player player = requirePlayer(sender);
			if (player != null) {
				plugin.manager().enter(player, "ring");
			}
			return true;
		}

		String action = args[0].toLowerCase(Locale.ROOT);
		switch (action) {
			case "list" -> list(sender);
			case "tp" -> {
				if (args.length < 2) {
					sender.sendMessage(MegaText.component("&e用法：/mega tp <" + viewpointIds() + ">"));
					return true;
				}
				Player player = requirePlayer(sender);
				if (player != null) {
					plugin.manager().enter(player, args[1]);
				}
			}
			case "back" -> {
				Player player = requirePlayer(sender);
				if (player != null) {
					plugin.manager().back(player);
				}
			}
			case "info" -> info(sender);
			case "check" -> check(sender);
			case "reload" -> reload(sender);
			default -> {
				MegaViewpoint viewpoint = MegaStructures.viewpoint(action);
				if (viewpoint == null) {
					usage(sender, label);
					return true;
				}
				Player player = requirePlayer(sender);
				if (player != null) {
					plugin.manager().enter(player, viewpoint);
				}
			}
		}
		return true;
	}

	private void list(CommandSender sender) {
		sender.sendMessage(MegaText.component("&6星际巨构观景点"));
		for (MegaViewpoint viewpoint : MegaStructures.viewpoints()) {
			ClickEvent click = ClickEvent.runCommand("mega tp " + viewpoint.id());
			sender.sendMessage(MegaText.component("&7- &f" + viewpoint.label()
				+ " &7(" + (int) viewpoint.x() + ", " + (int) viewpoint.y() + ", " + (int) viewpoint.z() + ") ")
				.append(MegaText.component("&e[传送]")
					.clickEvent(click)
					.hoverEvent(HoverEvent.showText(MegaText.component("&7/mega tp " + viewpoint.id())))));
		}
		sender.sendMessage(MegaText.component("&7巨构：轨道环、核心尖塔、戴森球、太空电梯、星际之门"));
	}

	private void info(CommandSender sender) {
		if (!plugin.megaWorld().ready()) {
			sender.sendMessage(MegaText.component("&c巨构维度未加载。"));
			return;
		}
		MegaConfig config = plugin.settings();
		World world = plugin.megaWorld().world();
		sender.sendMessage(MegaText.component("&6星际巨构维度 &f" + world.getName()
			+ " &7（程序生成，观景点 " + MegaStructures.viewpoints().size() + " 个）"));
		sender.sendMessage(MegaText.component("&7出生点：&f"
			+ String.format("%.1f, %.1f, %.1f", config.spawnX(), config.spawnY(), config.spawnZ())
			+ " &7在线玩家：&f" + world.getPlayers().size()
			+ " &7世界边界：&f" + (int) world.getWorldBorder().getSize()));
		sender.sendMessage(MegaText.component("&7巨构：&f轨道环（半径 " + OrbitalRing.RADIUS
			+ "）、核心尖塔（高 " + (CoreSpire.Y_MAX - CoreSpire.Y_MIN)
			+ "）、戴森球（半径 " + DysonSphere.RADIUS
			+ "）、太空电梯（高 " + (SpaceElevator.SHAFT_TOP - SpaceElevator.BASE_Y)
			+ "）、星际之门（半径 " + StarGate.RADIUS + "）"));
	}

	private void check(CommandSender sender) {
		if (!sender.hasPermission("mega.admin")) {
			sender.sendMessage(MegaText.component("&c你没有权限自检巨构结构。"));
			return;
		}
		if (!plugin.megaWorld().ready()) {
			sender.sendMessage(MegaText.component("&c巨构维度未加载。"));
			return;
		}
		World world = plugin.megaWorld().world();
		sender.sendMessage(MegaText.component("&6星际巨构结构自检 &7（首次读取会生成对应区块）"));
		for (MegaCheck check : MegaStructures.checks()) {
			Material actual = world.getBlockAt(check.x(), check.y(), check.z()).getType();
			boolean ok = actual == check.expected();
			String line = (ok ? "&a✔ " : "&c✘ ") + check.label()
				+ " &7(" + check.x() + "," + check.y() + "," + check.z() + " "
				+ (ok ? actual.name() : "期望 " + check.expected().name() + "，实际 " + actual.name()) + ")";
			sender.sendMessage(MegaText.component(line));
		}
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("mega.admin")) {
			sender.sendMessage(MegaText.component("&c你没有权限重载巨构配置。"));
			return;
		}
		plugin.reloadSettings();
		plugin.megaWorld().applyRules(plugin.settings());
		sender.sendMessage(MegaText.component("&a巨构配置已重载（世界名修改需重启；已生成区块不会重建）。"));
	}

	private void usage(CommandSender sender, String label) {
		sender.sendMessage(MegaText.component("&e用法：/" + label
			+ " [list|tp <" + viewpointIds() + ">|back|info|check|reload]"));
	}

	private String viewpointIds() {
		List<String> ids = new ArrayList<>();
		for (MegaViewpoint viewpoint : MegaStructures.viewpoints()) {
			ids.add(viewpoint.id());
		}
		return String.join("|", ids);
	}

	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) {
			if (!player.hasPermission("mega.use")) {
				player.sendMessage(MegaText.component("&c你没有权限进入星际巨构。"));
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
			List<String> options = new ArrayList<>(SUBCOMMANDS);
			for (MegaViewpoint viewpoint : MegaStructures.viewpoints()) {
				options.add(viewpoint.id());
			}
			return options.stream()
				.filter(option -> option.startsWith(prefix))
				.distinct()
				.toList();
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			return MegaStructures.viewpoints().stream()
				.map(MegaViewpoint::id)
				.filter(id -> id.startsWith(prefix))
				.toList();
		}
		return List.of();
	}
}
