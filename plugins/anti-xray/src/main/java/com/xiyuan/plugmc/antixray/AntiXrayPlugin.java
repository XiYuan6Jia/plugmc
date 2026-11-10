package com.xiyuan.plugmc.antixray;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AntiXrayPlugin extends JavaPlugin {
	static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
	private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private Settings settings;
	private XrayListener listener;
	private File paperConfig;
	private File alertLog;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		loadSettings();
		paperConfig = new File(getServer().getWorldContainer(), "config/paper-world-defaults.yml");
		alertLog = new File(getDataFolder(), "alerts.log");

		listener = new XrayListener(this);
		getServer().getPluginManager().registerEvents(listener, this);

		PluginCommand command = getCommand("antixray");
		if (command == null) {
			getLogger().warning("命令 antixray 未在 plugin.yml 中注册");
		} else {
			AntiXrayCommand executor = new AntiXrayCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		}

		PaperAntiXray.Status status = paperStatus();
		if (!status.known()) {
			getLogger().warning("未找到 Paper 防透视配置：" + paperConfig
				+ "（插件仍会做隐藏矿检测预警）");
		} else if (!status.enabled()) {
			getLogger().warning("Paper 内置防透视未开启；执行 /antixray apply 并重启后生效"
				+ "（当前仅做隐藏矿检测预警）");
		} else {
			getLogger().info("Paper 内置防透视已开启（engine-mode=" + status.engineMode() + "）");
		}
		getLogger().info("反矿物透视 已启用（窗口 " + settings.windowMinutes() + " 分钟，隐藏矿阈值 "
			+ settings.minHiddenOres() + " 个，占比 "
			+ Math.round(settings.minHiddenRatio() * 100) + "%）");
	}

	@Override
	public void onDisable() {
		getLogger().info("反矿物透视 已停用");
	}

	public void loadSettings() {
		reloadConfig();
		boolean detection = getConfig().getBoolean("detection.enabled", true);
		int window = Math.max(1, getConfig().getInt("detection.window-minutes", 10));
		int minHidden = Math.max(1, getConfig().getInt("detection.min-hidden-ores", 20));
		double ratio = getConfig().getDouble("detection.min-hidden-ratio", 0.75);
		ratio = Math.max(0.0, Math.min(1.0, ratio));
		int cooldown = Math.max(1, getConfig().getInt("detection.alert-cooldown-minutes", 10));
		int engineMode = getConfig().getInt("paper.engine-mode", 1);
		engineMode = engineMode == 2 ? 2 : 1;
		settings = new Settings(detection, window, minHidden, ratio, cooldown, engineMode);
	}

	void checkAlert(Player player, OreStats stats) {
		Settings current = settings;
		long windowMillis = current.windowMinutes() * 60_000L;
		int hidden = stats.hiddenWithin(windowMillis);
		int total = stats.totalWithin(windowMillis);
		if (total <= 0 || hidden < current.minHiddenOres()) {
			return;
		}
		double ratio = (double) hidden / total;
		if (ratio < current.minHiddenRatio()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - stats.lastAlertAt() < current.alertCooldownMinutes() * 60_000L) {
			return;
		}
		stats.lastAlertAt(now);

		String message = "⚠ [反透视] " + player.getName() + " 最近 " + current.windowMinutes()
			+ " 分钟挖掘隐藏矿石 " + hidden + " 个（占矿石挖掘 " + Math.round(ratio * 100)
			+ "%），疑似透视。";
		getLogger().warning(message);
		for (Player online : Bukkit.getOnlinePlayers()) {
			if (online.hasPermission("antixray.notify")) {
				online.sendMessage(LEGACY.deserialize("&c" + message));
			}
		}
		appendAlertLog(message);
	}

	private void appendAlertLog(String message) {
		try {
			File parent = alertLog.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			Files.writeString(
				alertLog.toPath(),
				"[" + LOG_STAMP.format(LocalDateTime.now()) + "] " + message + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		} catch (IOException exception) {
			getLogger().warning("写入 alerts.log 失败：" + exception.getMessage());
		}
	}

	public PaperAntiXray.Status paperStatus() {
		return PaperAntiXray.status(paperConfig.toPath());
	}

	public PaperAntiXray.Status applyPaperAntiXray() throws IOException {
		Path path = paperConfig.toPath();
		PaperAntiXray.apply(path, settings.engineMode());
		return PaperAntiXray.status(path);
	}

	public Settings settings() {
		return settings;
	}

	public XrayListener listener() {
		return listener;
	}

	public record Settings(
		boolean detectionEnabled,
		int windowMinutes,
		int minHiddenOres,
		double minHiddenRatio,
		int alertCooldownMinutes,
		int engineMode
	) {
	}
}
