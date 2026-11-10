package com.xiyuan.plugmc.antixray;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AntiXrayCommand implements CommandExecutor, TabCompleter {
	private final AntiXrayPlugin plugin;

	public AntiXrayCommand(AntiXrayPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
		switch (action) {
			case "status" -> status(sender, args);
			case "apply" -> apply(sender);
			case "reload" -> reload(sender);
			case "reset" -> reset(sender, args);
			default -> sender.sendMessage("用法：/antixray [status [玩家]|apply|reload|reset [玩家]]");
		}
		return true;
	}

	private void status(CommandSender sender, String[] args) {
		PaperAntiXray.Status status = plugin.paperStatus();
		if (!status.known()) {
			sender.sendMessage("Paper 防透视配置：未找到 config/paper-world-defaults.yml");
		} else if (status.enabled()) {
			sender.sendMessage("Paper 防透视：已开启（engine-mode=" + status.engineMode() + "）");
		} else {
			sender.sendMessage("Paper 防透视：未开启（engine-mode=" + status.engineMode()
				+ "），执行 /antixray apply 后重启生效");
		}
		AntiXrayPlugin.Settings settings = plugin.settings();
		sender.sendMessage("隐藏矿检测：" + (settings.detectionEnabled() ? "开启" : "关闭")
			+ "，窗口 " + settings.windowMinutes() + " 分钟，阈值 "
			+ settings.minHiddenOres() + " 个 / " + Math.round(settings.minHiddenRatio() * 100) + "%");

		Map<UUID, OreStats> stats = plugin.listener().stats();
		if (args.length >= 2) {
			Player target = Bukkit.getPlayerExact(args[1]);
			if (target == null) {
				sender.sendMessage("找不到在线玩家：" + args[1]);
				return;
			}
			OreStats oreStats = stats.get(target.getUniqueId());
			if (oreStats == null) {
				sender.sendMessage(target.getName() + "：本次启动后还没有挖掘矿石记录。");
				return;
			}
			sender.sendMessage(describe(target.getName(), oreStats, settings));
			return;
		}
		if (stats.isEmpty()) {
			sender.sendMessage("本次启动后还没有玩家挖掘矿石。");
			return;
		}
		List<Map.Entry<UUID, OreStats>> entries = new ArrayList<>(stats.entrySet());
		entries.sort(Comparator.comparingInt(
			entry -> -entry.getValue().hiddenWithin(settings.windowMinutes() * 60_000L)
		));
		for (Map.Entry<UUID, OreStats> entry : entries) {
			sender.sendMessage(describe(entry.getValue().playerName(), entry.getValue(), settings));
		}
	}

	private String describe(String name, OreStats stats, AntiXrayPlugin.Settings settings) {
		long windowMillis = settings.windowMinutes() * 60_000L;
		int hidden = stats.hiddenWithin(windowMillis);
		int total = stats.totalWithin(windowMillis);
		int percent = total <= 0 ? 0 : (int) Math.round(hidden * 100.0 / total);
		return name + "：窗口内矿石 " + total + "，隐藏 " + hidden + "（" + percent + "%）";
	}

	private void apply(CommandSender sender) {
		try {
			PaperAntiXray.Status status = plugin.applyPaperAntiXray();
			sender.sendMessage("已写入 Paper 防透视配置：enabled=" + status.enabled()
				+ "，engine-mode=" + status.engineMode() + "。重启服务器后生效（已生成 .bak 备份）。");
		} catch (IOException exception) {
			sender.sendMessage("写入 Paper 防透视配置失败：" + exception.getMessage());
		}
	}

	private void reload(CommandSender sender) {
		plugin.loadSettings();
		sender.sendMessage("反透视配置已重载。");
	}

	private void reset(CommandSender sender, String[] args) {
		if (args.length < 2) {
			plugin.listener().resetAll();
			sender.sendMessage("已清空所有玩家的矿石检测统计。");
			return;
		}
		Player target = Bukkit.getPlayerExact(args[1]);
		if (target == null) {
			sender.sendMessage("找不到在线玩家：" + args[1]);
			return;
		}
		plugin.listener().reset(target.getUniqueId());
		sender.sendMessage("已清空 " + target.getName() + " 的矿石检测统计。");
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			return List.of("status", "apply", "reload", "reset").stream()
				.filter(option -> option.startsWith(prefix))
				.toList();
		}
		if (args.length == 2 && (args[0].equalsIgnoreCase("status") || args[0].equalsIgnoreCase("reset"))) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			return Bukkit.getOnlinePlayers().stream()
				.map(Player::getName)
				.filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
				.toList();
		}
		return List.of();
	}
}
