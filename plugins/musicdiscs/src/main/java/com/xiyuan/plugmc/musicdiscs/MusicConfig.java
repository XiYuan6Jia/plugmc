package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 自定义唱片配置。资源包既可以由插件内置 HTTP 服务下发，也可以指向外部地址。
 */
public record MusicConfig(
	boolean enabled,
	int packFormat,
	String packMode,
	String externalUrl,
	String publicUrl,
	String bindAddress,
	int port,
	int joinDelayTicks,
	boolean required,
	String prompt,
	String discMaterialName,
	boolean useItemModel,
	double volume,
	int playbackRange,
	boolean stopOnQuit,
	int datapackFormat,
	String namespace,
	boolean autoInstallDatapack,
	long uploadMaxBytes,
	int uploadMaxSeconds,
	int uploadTokenSeconds
) {
	public MusicConfig {
		packFormat = packFormat <= 0 ? 121 : packFormat;
		datapackFormat = datapackFormat <= 0 ? 121 : datapackFormat;
		packMode = packMode == null || packMode.isBlank() ? "builtin" : packMode.trim().toLowerCase();
		bindAddress = bindAddress == null ? "" : bindAddress.trim();
		port = Math.max(1, Math.min(65535, port));
		joinDelayTicks = Math.max(1, Math.min(600, joinDelayTicks));
		volume = Math.max(0.1, Math.min(10.0, volume));
		playbackRange = Math.max(4, Math.min(128, playbackRange));
		namespace = namespace == null || namespace.isBlank() ? "plugmc" : namespace.trim().toLowerCase();
		uploadMaxBytes = Math.max(64L * 1024, Math.min(64L * 1024 * 1024, uploadMaxBytes));
		uploadMaxSeconds = Math.max(5, Math.min(1800, uploadMaxSeconds));
		uploadTokenSeconds = Math.max(60, Math.min(3600, uploadTokenSeconds));
	}

	public static MusicConfig load(FileConfiguration config) {
		return new MusicConfig(
			config.getBoolean("enabled", true),
			config.getInt("pack.format", 121),
			config.getString("pack.mode", "builtin"),
			config.getString("pack.external-url", ""),
			config.getString("pack.public-url", "http://q1.minekuai.com:20103/plugmc-music.zip"),
			config.getString("pack.bind-address", ""),
			config.getInt("pack.port", 20103),
			config.getInt("pack.join-delay-ticks", 60),
			config.getBoolean("pack.required", false),
			config.getString("pack.prompt", "&6服务器自定义唱片资源包"),
			config.getString("disc.material", "PAPER"),
			config.getBoolean("disc.use-item-model", true),
			config.getDouble("playback.volume", 1.0),
			config.getInt("playback.range", 32),
			config.getBoolean("playback.stop-on-quit", true),
			config.getInt("datapack.format", 121),
			config.getString("datapack.namespace", "plugmc"),
			config.getBoolean("datapack.auto-install", true),
			config.getLong("upload.max-bytes", 5L * 1024 * 1024),
			config.getInt("upload.max-seconds", 360),
			config.getInt("upload.token-seconds", 900)
		);
	}

	public Material discMaterial() {
		Material material = Material.matchMaterial(discMaterialName == null ? "" : discMaterialName);
		return material == null || material.isAir() ? Material.PAPER : material;
	}

	public boolean builtinPack() {
		return !"external".equals(packMode);
	}

	public String packUrl() {
		if (!builtinPack() && externalUrl != null && !externalUrl.isBlank()) {
			return externalUrl.trim();
		}
		return publicUrl == null ? "" : publicUrl.trim();
	}

	/**
	 * 上传页面对外地址：去掉资源包文件名后的根地址。
	 */
	public String uploadUrl() {
		String url = packUrl();
		if (url == null || url.isBlank()) {
			return "";
		}
		int slash = url.lastIndexOf('/');
		return slash > 0 ? url.substring(0, slash) + "/upload" : url;
	}
}
