package com.xiyuan.plugmc.minigames;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /games [blackjack|wheel|slots] [注额] | build | reload */
public final class GamesCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"blackjack", "wheel", "slots", "build", "reload"
	);

	private final MinigamesPlugin plugin;

	public GamesCommand(MinigamesPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			Player player = requirePlayer(sender);
			if (player != null) {
				plugin.gui().openMenu(player);
			}
			return true;
		}

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "build" -> {
				if (!sender.hasPermission("minigames.admin")) {
					sender.sendMessage(Text.of("&c你没有权限重建实体店。"));
					return true;
				}
				boolean ok = plugin.venue().build(true);
				sender.sendMessage(Text.of(ok ? "&a实体店已重建。" : "&c实体店重建失败，请看后台日志。"));
			}
			case "reload" -> {
				if (!sender.hasPermission("minigames.admin")) {
					sender.sendMessage(Text.of("&c你没有权限重载配置。"));
					return true;
				}
				plugin.reloadSettings();
				sender.sendMessage(Text.of("&a小游戏配置已重载（注额范围 " + plugin.settings().minBet()
					+ " ~ " + plugin.settings().maxBet() + "）。"));
			}
			default -> {
				Player player = requirePlayer(sender);
				if (player == null) {
					return true;
				}
				GameType game = GameType.parse(args[0]);
				if (game == null) {
					usage(sender, label);
					return true;
				}
				int bet = plugin.settings().defaultBet(game);
				if (args.length > 1) {
					try {
						bet = Integer.parseInt(args[1]);
					} catch (NumberFormatException exception) {
						player.sendMessage(Text.of("&c注额必须是整数。"));
						return true;
					}
				}
				if (bet < plugin.settings().minBet() || bet > plugin.settings().maxBet()) {
					player.sendMessage(Text.of("&c注额范围：" + plugin.settings().minBet()
						+ " ~ " + plugin.settings().maxBet() + "。"));
					return true;
				}
				plugin.gui().open(player, game, bet);
			}
		}
		return true;
	}

	private void usage(CommandSender sender, String label) {
		sender.sendMessage(Text.of("&e用法：/" + label + " [blackjack|wheel|slots] [注额] | build | reload"));
	}

	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) {
			if (!player.hasPermission("minigames.use")) {
				player.sendMessage(Text.of("&c你没有权限玩小游戏。"));
				return null;
			}
			return player;
		}
		sender.sendMessage("只有玩家可以玩小游戏。");
		return null;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			return SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
		}
		if (args.length == 2 && GameType.parse(args[0]) != null) {
			return List.of("1", "10", "50", "100");
		}
		return List.of();
	}
}
