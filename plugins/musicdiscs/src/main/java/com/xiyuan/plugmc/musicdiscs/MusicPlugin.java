package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 自定义唱片插件：管理音乐库、打包资源包并提供下载与玩家上传页面、插件侧播放，
 * 以及按需导出 {@code jukebox_song} 数据包以支持原版唱机。
 */
public final class MusicPlugin extends JavaPlugin implements Listener {
	private MusicConfig settings;
	private SongLibrary library;
	private ResourcePackBuilder packs;
	private PackServer packServer;
	private DiscItems discs;
	private PlaybackManager playback;
	private DatapackExporter datapacks;
	private UploadService uploads;
	private JukeboxManager jukeboxes;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		settings = MusicConfig.load(getConfig());
		library = new SongLibrary(this);
		library.prepareDirectories();
		library.load();
		packs = new ResourcePackBuilder(this, library, settings);
		packServer = new PackServer(this);
		discs = new DiscItems(this);
		playback = new PlaybackManager(this, library, settings);
		datapacks = new DatapackExporter(this, library, settings);
		uploads = new UploadService(this, library);
		jukeboxes = new JukeboxManager(this);
		refreshPack(null);
		getServer().getPluginManager().registerEvents(new MusicListener(this), this);
		getServer().getPluginManager().registerEvents(this, this);
		PluginCommand command = getCommand("music");
		if (command != null) {
			MusicCommand executor = new MusicCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		} else {
			getLogger().warning("命令 music 未在 plugin.yml 中注册");
		}
		getLogger().info("自定义唱片 已启用（" + library.songs().size() + " 首，资源包 "
			+ (packServer.running() ? "服务端口 " + packServer.boundPort() : "未启动")
			+ "，玩家上传上限 " + (settings.uploadMaxBytes() / 1024 / 1024) + " MB / "
			+ MusicText.duration(settings.uploadMaxSeconds()) + "）");
	}

	@Override
	public void onDisable() {
		if (playback != null) {
			playback.stopAll();
			playback = null;
		}
		if (jukeboxes != null) {
			jukeboxes.stopAll();
			jukeboxes = null;
		}
		if (packServer != null) {
			packServer.stop();
			packServer = null;
		}
		getLogger().info("自定义唱片 已停用");
	}

	/**
	 * 重新打包资源包并重启下载服务；callback 用于把结果反馈给命令执行者。
	 */
	public void refreshPack(Consumer<String> callback) {
		try {
			ResourcePackBuilder.BuildResult result = packs.build();
			packServer.update(result.file(), result.sha1());
			packServer.ensureStarted(settings);
			// 每次曲目变动都把 jukebox_song 数据包同步到主世界，保证原版唱机可用
			datapacks.syncAndReloadIfNeeded();
			String message = "资源包已更新：" + result.songs() + " 首，" + result.bytes() + " 字节"
				+ (packServer.running() ? "，下载端口 " + packServer.boundPort() : "");
			getLogger().info(message);
			if (callback != null) {
				callback.accept(message);
			}
		} catch (IOException | RuntimeException exception) {
			getLogger().log(Level.SEVERE, "生成资源包失败", exception);
			if (callback != null) {
				callback.accept("生成资源包失败：" + exception.getMessage());
			}
		}
	}

	public void reloadEverything() {
		reloadConfig();
		settings = MusicConfig.load(getConfig());
		library.load();
		playback.stopAll();
		playback = new PlaybackManager(this, library, settings);
		datapacks = new DatapackExporter(this, library, settings);
		packs = new ResourcePackBuilder(this, library, settings);
		uploads = new UploadService(this, library);
		jukeboxes = new JukeboxManager(this);
		// 端口/绑定地址可能变化，重载时重启监听
		packServer.stop();
		refreshPack(null);
	}

	/**
	 * 玩家看到的网页根地址（上传页面）。
	 */
	public String publicBaseUrl() {
		String url = settings.packUrl();
		if (url == null || url.isBlank()) {
			return "（未配置 pack.public-url）";
		}
		int slash = url.lastIndexOf('/');
		return slash > 0 ? url.substring(0, slash) + "/" : url;
	}

	public MusicConfig settings() {
		return settings;
	}

	public SongLibrary library() {
		return library;
	}

	public PackServer packServer() {
		return packServer;
	}

	public DiscItems discs() {
		return discs;
	}

	public PlaybackManager playback() {
		return playback;
	}

	public DatapackExporter datapacks() {
		return datapacks;
	}

	public UploadService uploads() {
		return uploads;
	}

	public JukeboxManager jukeboxes() {
		return jukeboxes;
	}

	@EventHandler
	public void onInventoryClick(InventoryClickEvent event) {
		if (!(event.getInventory().getHolder(false) instanceof MusicGui gui)) {
			return;
		}
		event.setCancelled(true);
		if (event.getWhoClicked() instanceof Player player) {
			MusicGui.handle(this, player, gui, event.getRawSlot(), event.isShiftClick());
		}
	}

	@EventHandler
	public void onInventoryDrag(InventoryDragEvent event) {
		if (event.getInventory().getHolder(false) instanceof MusicGui) {
			event.setCancelled(true);
		}
	}
}
