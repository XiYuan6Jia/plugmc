package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 唱片库：{@code songs.yml} 存元数据，{@code songs/<id>.ogg} 存音频，
 * {@code incoming/} 是管理员上传的落地区，用 {@code /music import} 导入。
 *
 * <p>玩家上传的曲目记录 owner（UUID），一个账号只能有一首。</p>
 */
public final class SongLibrary {
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

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
				entry.getString("owner", ""),
				entry.getString("owner-name", "")
			));
		}
		long playerSongs = songs.values().stream().filter(Song::playerSong).count();
		plugin.getLogger().info("已加载 " + songs.size() + " 首自定义唱片（其中玩家上传 "
			+ playerSongs + " 首）");
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Song song : songs.values()) {
			String base = "songs." + song.id();
			yaml.set(base + ".title", song.title());
			yaml.set(base + ".author", song.author());
			yaml.set(base + ".seconds", song.seconds());
			yaml.set(base + ".comparator", song.comparator());
			if (song.playerSong()) {
				yaml.set(base + ".owner", song.owner());
				yaml.set(base + ".owner-name", song.ownerName());
			}
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

	public Optional<Song> ownedBy(UUID owner) {
		if (owner == null) {
			return Optional.empty();
		}
		String id = owner.toString();
		return songs.values().stream().filter(song -> id.equals(song.owner())).findFirst();
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
	 * 管理员从 incoming/&lt;id&gt;.ogg 导入（或覆盖）一首歌。
	 */
	public ImportResult importFromIncoming(
		String id,
		String title,
		String author,
		int comparator,
		MusicConfig config
	) {
		if (!validId(id)) {
			return new ImportResult(false, "ID 只能是小写字母、数字和下划线（1-32 位）", null);
		}
		Path source = incomingDir.resolve(id + ".ogg");
		if (!Files.isRegularFile(source)) {
			return new ImportResult(false, "没有找到 " + source + "，请先把 .ogg 文件放进去", null);
		}
		try {
			long size = Files.size(source);
			if (size > config.uploadMaxBytes()) {
				return new ImportResult(false, "文件超过 " + (config.uploadMaxBytes() / 1024 / 1024)
					+ " MB 限制", null);
			}
			byte[] data = Files.readAllBytes(source);
			ImportResult result = importBytes(id, data, title, author, comparator, "", "", config);
			if (result.ok()) {
				Files.deleteIfExists(source);
			}
			return result;
		} catch (IOException exception) {
			return new ImportResult(false, "读取文件失败：" + exception.getMessage(), null);
		}
	}

	/**
	 * 用原始字节导入（玩家上传走这里）。owner 为空表示服务器曲目。
	 */
	public ImportResult importBytes(
		String id,
		byte[] data,
		String title,
		String author,
		int comparator,
		String owner,
		String ownerName,
		MusicConfig config
	) {
		if (!validId(id)) {
			return new ImportResult(false, "ID 只能是小写字母、数字和下划线（1-32 位）", null);
		}
		if (data == null || data.length == 0) {
			return new ImportResult(false, "文件是空的", null);
		}
		if (data.length > config.uploadMaxBytes()) {
			return new ImportResult(false, "文件超过 " + (config.uploadMaxBytes() / 1024 / 1024)
				+ " MB 限制", null);
		}
		Optional<OggVorbis.Info> info = OggVorbis.probe(data);
		if (info.isEmpty()) {
			return new ImportResult(false, "不是有效的 OGG Vorbis 文件（不支持 mp3/wav）", null);
		}
		OggVorbis.Info probe = info.get();
		if (probe.seconds() > config.uploadMaxSeconds() + 0.5f) {
			return new ImportResult(false, "时长 " + MusicText.duration(probe.seconds())
				+ " 超过 " + MusicText.duration(config.uploadMaxSeconds()) + " 限制", null);
		}
		try {
			Path target = songsDir.resolve(id + ".ogg");
			Files.createDirectories(songsDir);
			try (OutputStream output = Files.newOutputStream(target)) {
				output.write(data);
			}
		} catch (IOException exception) {
			return new ImportResult(false, "保存文件失败：" + exception.getMessage(), null);
		}
		Song existing = songs.get(id);
		Song song = new Song(
			id,
			title,
			author,
			probe.seconds(),
			comparator <= 0 ? (existing == null ? 1 : existing.comparator()) : comparator,
			owner,
			ownerName
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
			song.owner(),
			song.ownerName()
		));
		save();
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
