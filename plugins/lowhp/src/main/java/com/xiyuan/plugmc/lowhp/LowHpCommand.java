package com.xiyuan.plugmc.lowhp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /lowhp：查看状态、试听、开关提示音、重载配置。
 */
public final class LowHpCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS =
		List.of("status", "test", "toggle", "stop", "pack", "reload");

	private final LowHpPlugin plugin;

	public LowHpCommand(LowHpPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "status", "状态" -> status(sender);
			case "test", "试听" -> test(sender, args);
			case "toggle", "mute", "开关" -> toggle(sender);
			case "stop", "停止" -> stop(sender);
			case "pack", "资源包" -> pack(sender, args);
			case "reload", "重载" -> reload(sender);
			default -> usage(sender);
		}
		return true;
	}

	private void usage(CommandSender sender) {
		sender.sendMessage(LowHpText.component("&6/lowhp 用法："));
		sender.sendMessage(LowHpText.component("&f/lowhp &7查看阈值与状态"));
		sender.sendMessage(LowHpText.component("&f/lowhp test [a|b] &7试听提示音"));
		sender.sendMessage(LowHpText.component("&f/lowhp toggle &7开关自己的低血量提示音"));
		sender.sendMessage(LowHpText.component("&f/lowhp stop &7停止当前播放"));
		if (sender.hasPermission("lowhp.admin")) {
			sender.sendMessage(LowHpText.component("&f/lowhp pack [玩家] &7重新下发资源包"));
			sender.sendMessage(LowHpText.component("&f/lowhp reload &7重载配置并重建资源包"));
		}
	}

	private void status(CommandSender sender) {
		LowHpConfig config = plugin.settings();
		sender.sendMessage(LowHpText.component("&6低血量提示音："
			+ (config.enabled() ? "&a已启用" : "&c已关闭")));
		sender.sendMessage(LowHpText.component("&7触发阈值：&f"
			+ Math.round(config.thresholdPercent() * 100) + "% &7最大生命值，回血超过 &f"
			+ Math.round(config.healPercent() * 100) + "% &7停止播放"));
		sender.sendMessage(LowHpText.component("&7冷却：&f" + config.cooldownSeconds() + " 秒"
			+ " &7音量：&f" + config.volume() + " &7音高：&f" + config.pitch()));
		StringBuilder alerts = new StringBuilder();
		for (Alert alert : config.alertList()) {
			if (alerts.length() > 0) {
				alerts.append("&7、");
			}
			alerts.append("&f").append(alert.id()).append("&7(").append(alert.display()).append("&7)");
		}
		sender.sendMessage(LowHpText.component("&7随机曲目：" + alerts));
		sender.sendMessage(LowHpText.component("&7资源包：&f" + config.packUrl()
			+ " &7(" + (plugin.packServer().running()
				? "端口 " + plugin.packServer().boundPort() : "未启动") + ")"));
		if (sender instanceof Player player) {
			sender.sendMessage(LowHpText.component("&7你的状态："
				+ (plugin.alerts().muted(player) ? "&c已关闭提示音" : "&a开启提示音")
				+ " &7阈值 &f" + String.format("%.1f", plugin.alerts().threshold(player))
				+ " &7点，当前 &f" + String.format("%.1f", player.getHealth()) + " &7点"));
		}
	}

	private void test(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以试听提示音。");
			return;
		}
		Alert alert = args.length > 1 ? Alert.byId(args[1]) : null;
		if (args.length > 1 && alert == null) {
			player.sendMessage(LowHpText.component("&c没有这段提示音：" + args[1] + "（可选 a / b）"));
			return;
		}
		if (alert == null) {
			List<Alert> alerts = plugin.settings().alertList();
			alert = alerts.get((int) (Math.random() * alerts.size()));
		}
		if (plugin.alerts().playNow(player, alert)) {
			player.sendMessage(LowHpText.component("&a正在播放：&f" + alert.display()
				+ " &7只有你能听到"));
		}
	}

	private void toggle(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以开关自己的提示音。");
			return;
		}
		boolean muted = plugin.alerts().toggleMuted(player);
		player.sendMessage(LowHpText.component(muted
			? "&7已关闭低血量提示音，随时用 &f/lowhp toggle &7重新打开。"
			: "&a已开启低血量提示音。"));
	}

	private void stop(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			return;
		}
		if (plugin.alerts().stop(player)) {
			player.sendMessage(LowHpText.component("&7已停止播放。"));
		} else {
			player.sendMessage(LowHpText.component("&7当前没有在播放提示音。"));
		}
	}

	private void pack(CommandSender sender, String[] args) {
		Player target;
		if (args.length > 1) {
			if (!sender.hasPermission("lowhp.admin")) {
				sender.sendMessage("你没有给其他玩家下发资源包的权限。");
				return;
			}
			target = org.bukkit.Bukkit.getPlayerExact(args[1]);
			if (target == null) {
				sender.sendMessage("玩家不在线：" + args[1]);
				return;
			}
		} else if (sender instanceof Player player) {
			target = player;
		} else {
			sender.sendMessage("用法：/lowhp pack [玩家]");
			return;
		}
		plugin.packServer().send(target, plugin.settings());
		sender.sendMessage("已向 " + target.getName() + " 下发提示音资源包。");
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("lowhp.admin")) {
			sender.sendMessage("你没有重载低血量提示音的权限。");
			return;
		}
		plugin.reloadEverything();
		sender.sendMessage("配置已重载，资源包已重建（"
			+ plugin.packSizeText() + "）。");
	}

	@Override
	public List<String> onTabComplete(
		CommandSender sender,
		Command command,
		String alias,
		String[] args
	) {
		if (args.length == 1) {
			return filter(SUBCOMMANDS, args[0]);
		}
		if (args.length == 2) {
			String sub = args[0].toLowerCase(Locale.ROOT);
			if (sub.equals("test")) {
				List<String> ids = new ArrayList<>();
				for (Alert alert : Alert.values()) {
					ids.add(alert.id());
				}
				return filter(ids, args[1]);
			}
			if (sub.equals("pack")) {
				return filter(
					org.bukkit.Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
					args[1]
				);
			}
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
