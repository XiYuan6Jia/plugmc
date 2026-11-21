package com.xiyuan.plugmc.lowhp;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.logging.Level;

/**
 * 低血量提示音插件：玩家血量低于阈值时，随机给该玩家播放一段内置音频。
 *
 * <p>音频只能由客户端播放，所以插件会把内置 ogg 打包成资源包，通过内置 HTTP 服务
 * （默认 TCP 20103）下发；播放时只调用 {@code Player#playSound}，不会影响其他玩家。</p>
 */
public final class LowHpPlugin extends JavaPlugin {
	private LowHpConfig settings;
	private AlertService alerts;
	private AlertPack pack;
	private PackServer packServer;
	private long packBytes;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		settings = LowHpConfig.load(getConfig());
		alerts = new AlertService(this, settings);
		alerts.loadMuted();
		pack = new AlertPack(this);
		packServer = new PackServer(this);
		refreshPack(null);
		alerts.start();
		getServer().getPluginManager().registerEvents(new LowHpListener(this), this);
		PluginCommand command = getCommand("lowhp");
		if (command != null) {
			LowHpCommand executor = new LowHpCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		} else {
			getLogger().warning("命令 lowhp 未在 plugin.yml 中注册");
		}
		getLogger().info("低血量提示音 已启用（阈值 " + Math.round(settings.thresholdPercent() * 100)
			+ "%，冷却 " + settings.cooldownSeconds() + " 秒，资源包 "
			+ (packServer.running() ? "端口 " + packServer.boundPort() : "未启动") + "）");
	}

	@Override
	public void onDisable() {
		if (alerts != null) {
			alerts.stop();
			alerts.saveMuted();
			alerts = null;
		}
		if (packServer != null) {
			packServer.stop();
			packServer = null;
		}
		getLogger().info("低血量提示音 已停用");
	}

	/**
	 * 重建资源包并（重新）启动下载服务；callback 用于把结果反馈给命令执行者。
	 */
	public void refreshPack(java.util.function.Consumer<String> callback) {
		try {
			AlertPack.BuildResult result = pack.build(settings);
			packBytes = result.bytes();
			packServer.update(result);
			packServer.start(settings);
			alerts.apply(settings, result.durations());
			String message = "资源包已生成：" + result.durations().size() + " 段提示音（"
				+ result.durations().entrySet().stream()
					.map(entry -> entry.getKey() + "=" + LowHpText.seconds(entry.getValue()))
					.collect(java.util.stream.Collectors.joining("、"))
				+ "），" + result.bytes() + " 字节";
			getLogger().info(message);
			if (callback != null) {
				callback.accept(message);
			}
		} catch (IOException | RuntimeException exception) {
			getLogger().log(Level.SEVERE, "生成提示音资源包失败", exception);
			if (callback != null) {
				callback.accept("生成资源包失败：" + exception.getMessage());
			}
		}
	}

	public void reloadEverything() {
		reloadConfig();
		settings = LowHpConfig.load(getConfig());
		packServer.stop();
		refreshPack(null);
	}

	public String packSizeText() {
		return (packBytes / 1024) + " KB";
	}

	public LowHpConfig settings() {
		return settings;
	}

	public AlertService alerts() {
		return alerts;
	}

	public PackServer packServer() {
		return packServer;
	}
}
