package com.xiyuan.plugmc.musicdiscs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 唱片库：{@code songs.yml} 存元数据，{@code songs/<id>.ogg} 存音频，
 * {@code incoming/} 是管理员上传的落地区，用 {@code /music import} 导入。
 */
public final class SongLibrary {
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");
	private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

	private final JavaPlugin plugin;
	private final Path root;
	private final Path songsDir;
	private final Path incomingDir;
	private final Path metaFile;
	private final Map<String, Song> songs = new LinkedHashMap<>();

	public SongLibrary(JavaPlugin plugin) {
		this.plugin = plugin;
		this.root = plugin.getDataFolder().toPath();
		this.songsDir = root.resolve("songs");
		this.incomingDir = root.resolve("incoming");
		this.metaFile = root.resolve("songs.yml");
	}

	public void prepareDirectories() {
		try {
			Files.createDirectories(songsDir);
			Files.createDirectories(incomingDir);
		} catch (IOException exception) {
			plugin.getLogger().severe("创建唱片目录失败：" + exception.getMessage());
		}
	}

	public Path incomingDir() {
		return incomingDir;
	}

	public Path songsDir() {
		return songsDir;
	}

	public Optional<Path> audioFile(String id) {
		Path file = songsDir.resolve(id + ".ogg");
		return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
	}

	public void load() {
		songs.clear();
		if (!Files.isRegularFile(metaFile)) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(metaFile.toFile());
		ConfigurationSection section = yaml.getConfigurationSection("songs");
		if (section == null) {
			return;
		}
		for (String id : section.getKeys(false)) {
			ConfigurationSection entry = section.getConfigurationSection(id);
			if (entry == null) {
				continue;
			}
			if (audioFile(id).isEmpty()) {
				plugin.getLogger().warning("唱片 " + id + " 缺少音频文件，已跳过");
				continue;
			}
			songs.put(id, new Song(
				id,
				entry.getString("title", id),
				entry.getString("author", ""),
				(float) entry.getDouble("seconds", 0),
				entry.getInt("comparator", 1),
				entry.getInt("custom-model-data", 0)
			));
		}
		plugin.getLogger().info("已加载 " + songs.size() + " 首自定义唱片");
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Song song : songs.values()) {
			String base = "songs." + song.id();
			yaml.set(base + ".title", song.title());
			yaml.set(base + ".author", song.author());
			yaml.set(base + ".seconds", song.seconds());
			yaml.set(base + ".comparator", song.comparator());
			yaml.set(base + ".custom-model-data", song.customModelData());
		}
		try {
			Files.createDirectories(root);
			yaml.save(metaFile.toFile());
		} catch (IOException exception) {
			plugin.getLogger().severe("保存 songs.yml 失败：" + exception.getMessage());
		}
	}

	public List<Song> songs() {
		return List.copyOf(songs.values());
	}

	public Optional<Song> find(String id) {
		if (id == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(songs.get(id.toLowerCase(Locale.ROOT)));
	}

	public boolean validId(String id) {
		return id != null && ID_PATTERN.matcher(id).matches();
	}

	/**
	 * 导入结果，失败时带上给玩家看的原因。
	 */
	public record ImportResult(boolean ok, String message, Song song) {
	}

	/**
	 * 从 incoming/&lt;id&gt;.ogg 导入（或覆盖）一首歌。
	 */
	public ImportResult importFromIncoming(String id, String title, String author, int comparator) {
		if (!validId(id)) {
			return new ImportResult(false, "ID 只能是小写字母、数字和下划线（1-32 位）", null);
		}
		Path source = incomingDir.resolve(id + ".ogg");
		if (!Files.isRegularFile(source)) {
			return new ImportResult(false, "没有找到 " + incomingDir.resolve(id + ".ogg")
				+ "，请先把 .ogg 文件放进去", null);
		}
		try {
			long size = Files.size(source);
			if (size > MAX_FILE_BYTES) {
				return new ImportResult(false, "文件超过 64 MB，请压缩后再导入", null);
			}
		} catch (IOException exception) {
			return new ImportResult(false, "读取文件失败：" + exception.getMessage(), null);
		}
		Optional<OggVorbis.Info> info = OggVorbis.probe(source);
		if (info.isEmpty()) {
			return new ImportResult(false, "不是有效的 OGG Vorbis 文件（不支持 mp3/wav）", null);
		}
		OggVorbis.Info probe = info.get();
		try {
			Path target = songsDir.resolve(id + ".ogg");
			Files.createDirectories(songsDir);
			try (InputStream input = Files.newInputStream(source);
				OutputStream output = Files.newOutputStream(target)) {
				input.transferTo(output);
			}
			Files.deleteIfExists(source);
		} catch (IOException exception) {
			return new ImportResult(false, "移动文件失败：" + exception.getMessage(), null);
		}
		Song existing = songs.get(id);
		int model = existing != null && existing.customModelData() > 0
			? existing.customModelData()
			: nextModelData();
		Song song = new Song(
			id,
			title,
			author,
			probe.seconds(),
			comparator <= 0 ? (existing == null ? 1 : existing.comparator()) : comparator,
			model
		);
		songs.put(id, song);
		save();
		String extra = probe.channels() > 2
			? "（注意：文件有 " + probe.channels() + " 个声道，客户端可能只按立体声播放）"
			: "";
		return new ImportResult(true, "已导入 " + id + "："
			+ song.displayName() + "，时长 " + song.durationText()
			+ "，" + probe.sampleRate() + " Hz / " + probe.channels() + " 声道" + extra, song);
	}

	public boolean delete(String id) {
		Song removed = songs.remove(id);
		if (removed == null) {
			return false;
		}
		try {
			Files.deleteIfExists(songsDir.resolve(id + ".ogg"));
		} catch (IOException exception) {
			plugin.getLogger().warning("删除音频失败：" + exception.getMessage());
		}
		save();
		return true;
	}

	public void updateMetadata(String id, String title, String author, int comparator) {
		Song song = songs.get(id);
		if (song == null) {
			return;
		}
		songs.put(id, new Song(
			song.id(),
			title == null || title.isBlank() ? song.title() : title,
			author == null ? song.author() : author,
			song.seconds(),
			comparator <= 0 ? song.comparator() : comparator,
			song.customModelData()
		));
		save();
	}

	/**
	 * 未设置自定义模型数据的唱片分配一个稳定值，方便后续自行替换贴图。
	 */
	private int nextModelData() {
		int max = 1000;
		for (Song song : songs.values()) {
			max = Math.max(max, song.customModelData());
		}
		return max + 1;
	}

	public List<String> incomingFiles() {
		List<String> names = new ArrayList<>();
		if (!Files.isDirectory(incomingDir)) {
			return names;
		}
		try (var stream = Files.list(incomingDir)) {
			stream.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.sorted()
				.forEach(names::add);
		} catch (IOException exception) {
			plugin.getLogger().warning("读取 incoming 目录失败：" + exception.getMessage());
		}
		return names;
	}
}
