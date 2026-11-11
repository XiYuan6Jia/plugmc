package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
	private final JavaPlugin plugin;
	private final SongLibrary library;
	private final MusicConfig config;

	public DatapackExporter(JavaPlugin plugin, SongLibrary library, MusicConfig config) {
		this.plugin = plugin;
		this.library = library;
		this.config = config;
	}

	public Path datapackRoot() {
		return plugin.getDataFolder().toPath().resolve("datapack").resolve("plugmc-music");
	}

	public record ExportResult(Path root, int songs, List<String> warnings) {
	}

	public ExportResult export() throws IOException {
		Path root = datapackRoot();
		Path songDir = root.resolve("data").resolve(config.namespace()).resolve("jukebox_song");
		Path functionDir = root.resolve("data").resolve(config.namespace()).resolve("function");
		deleteRecursively(root.resolve("data"));
		Files.createDirectories(songDir);
		Files.createDirectories(functionDir);

		List<String> warnings = new ArrayList<>();
		List<Song> songs = library.songs();
		for (Song song : songs) {
			Files.writeString(
				songDir.resolve(song.id() + ".json"),
				jukeboxSongJson(song),
				StandardCharsets.UTF_8
			);
			Files.writeString(
				functionDir.resolve("give_" + song.id() + ".mcfunction"),
				giveCommand(song) + "\n",
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
		Files.writeString(
			functionDir.resolve("give_all.mcfunction"),
			songs.stream().map(this::giveCommand).reduce("", (a, b) -> a + b + "\n"),
			StandardCharsets.UTF_8
		);
		if (songs.isEmpty()) {
			warnings.add("唱片库为空，数据包只包含 pack.mcmeta");
		}
		return new ExportResult(root, songs.size(), warnings);
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

	private String giveCommand(Song song) {
		return "give @s minecraft:" + config.discMaterial().getKey().getKey()
			+ "[minecraft:jukebox_playable=\"" + config.namespace() + ":" + song.id() + "\"] 1";
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
