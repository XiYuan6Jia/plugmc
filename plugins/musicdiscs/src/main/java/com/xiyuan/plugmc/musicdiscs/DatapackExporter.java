package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出数据包：把每首歌注册成 {@code <namespace>:<id>} 的 {@code jukebox_song}，
 * 放进 {@code <世界>/datapacks/} 并重启/重载后，原版唱机就能识别自定义唱片。
 *
 * <p>音频仍然来自资源包，因此数据包与资源包必须同时生效。</p>
 */
public final class DatapackExporter {
	private final MusicPlugin plugin;
	private final SongLibrary library;
	private final MusicConfig config;

	public DatapackExporter(MusicPlugin plugin, SongLibrary library, MusicConfig config) {
		this.plugin = plugin;
		this.library = library;
		this.config = config;
	}

	public Path datapackRoot() {
		return plugin.getDataFolder().toPath().resolve("datapack").resolve("plugmc-music");
	}

	/**
	 * 主世界存档里的数据包目录：放这里才能被服务端自动加载。
	 *
	 * <p>新版存档把维度拆到了 {@code world/dimensions/minecraft/overworld}，
	 * {@code getWorldFolder()} 返回的是维度目录，所以向上找到含 {@code level.dat} 的层级根。</p>
	 */
	public Path worldDatapackRoot() {
		List<org.bukkit.World> worlds = plugin.getServer().getWorlds();
		if (worlds.isEmpty()) {
			return datapackRoot();
		}
		Path candidate = worlds.getFirst().getWorldFolder().toPath();
		for (int depth = 0; depth < 6 && candidate != null; depth++) {
			if (Files.isRegularFile(candidate.resolve("level.dat"))) {
				return candidate.resolve("datapacks").resolve("plugmc-music");
			}
			candidate = candidate.getParent();
		}
		return worlds.getFirst().getWorldFolder().toPath()
			.resolve("datapacks").resolve("plugmc-music");
	}

	public record ExportResult(Path root, int songs, List<String> warnings) {
	}

	public ExportResult export() throws IOException {
		return exportTo(datapackRoot());
	}

	/**
	 * 把数据包写进主世界存档，返回写入了多少首歌。
	 */
	public int installToWorld() throws IOException {
		return exportTo(worldDatapackRoot()).songs();
	}

	public ExportResult exportTo(Path root) throws IOException {
		Path songDir = root.resolve("data").resolve(config.namespace()).resolve("jukebox_song");
		deleteRecursively(root.resolve("data"));
		Files.createDirectories(songDir);

		List<String> warnings = new ArrayList<>();
		List<Song> songs = library.songs();
		for (Song song : songs) {
			Files.writeString(
				songDir.resolve(song.id() + ".json"),
				jukeboxSongJson(song),
				StandardCharsets.UTF_8
			);
		}
		Files.writeString(
			root.resolve("pack.mcmeta"),
			"{\"pack\":{\"description\":\"plugmc custom music discs\","
				+ "\"min_format\":" + config.datapackFormat() + ","
				+ "\"max_format\":" + config.datapackFormat() + "}}\n",
			StandardCharsets.UTF_8
		);
		if (songs.isEmpty()) {
			warnings.add("唱片库为空，数据包只包含 pack.mcmeta");
		}
		return new ExportResult(root, songs.size(), warnings);
	}

	/**
	 * 把数据包同步到主世界并在有新曲目时重载数据，让原版唱机立刻能用。
	 *
	 * <p>返回 true 表示这次真的触发了重载。</p>
	 */
	public boolean syncAndReloadIfNeeded() {
		MusicConfig settings = config;
		if (!settings.autoInstallDatapack()) {
			return false;
		}
		boolean missing;
		try {
			missing = library.songs().stream()
				.anyMatch(song -> !plugin.discs().hasJukeboxSong(song, settings));
			installToWorld();
		} catch (IOException exception) {
			plugin.getLogger().warning("同步数据包失败：" + exception.getMessage());
			return false;
		}
		if (!missing && datapackEnabled()) {
			return false;
		}
		// 数据包只是为“下次启动后”准备原版唱机注册表；运行中无法热加载注册表，
		// 因此这里只保证文件就位并启用，不去 reloadData（那会连带解析旧注册表的函数）。
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			try {
				io.papermc.paper.datapack.DatapackManager manager =
					plugin.getServer().getDatapackManager();
				manager.refreshPacks();
				io.papermc.paper.datapack.Datapack pack = manager.getPacks().stream()
					.filter(candidate -> candidate.getName() != null
						&& candidate.getName().contains("plugmc-music"))
					.findFirst()
					.orElse(null);
				if (pack == null) {
					plugin.getLogger().warning("没有发现数据包 plugmc-music："
						+ worldDatapackRoot() + " 可能写入失败");
					return;
				}
				if (!pack.isEnabled()) {
					pack.setEnabled(true);
				}
				plugin.getLogger().info("数据包 plugmc-music 已就位（下次启动生效）；"
					+ "当前新增曲目由插件版唱机播放，无需重启即可使用");
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("启用数据包失败：" + exception.getMessage());
			}
		});
		return true;
	}

	/**
	 * 数据包是否已经被服务端发现并启用。
	 */
	public boolean datapackEnabled() {
		try {
			return plugin.getServer().getDatapackManager().getEnabledPacks().stream()
				.anyMatch(pack -> pack.getName() != null && pack.getName().contains("plugmc-music"));
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private String jukeboxSongJson(Song song) {
		String description = escape(song.displayName());
		return "{\n"
			+ "  \"sound_event\": {\n"
			+ "    \"sound_id\": \"" + config.namespace() + ":" + song.soundPath().replace('/', '.')
			+ "\",\n"
			+ "    \"range\": 64.0\n"
			+ "  },\n"
			+ "  \"description\": {\"text\": \"" + description + "\"},\n"
			+ "  \"length_in_seconds\": " + String.format("%.3f", Math.max(1.0, song.seconds())) + ",\n"
			+ "  \"comparator_output\": " + song.comparator() + "\n"
			+ "}\n";
	}

	private String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (var stream = Files.walk(path)) {
			for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		}
	}
}
