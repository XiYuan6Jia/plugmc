package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把唱片库打包成客户端资源包（zip）：{@code sounds.json} + 每首歌的 ogg。
 *
 * <p>物品贴图不在这里生成，唱片使用原版音乐唱片外观（带自定义模型数据，
 * 需要换贴图时由管理员自行追加模型文件），避免与其它资源包冲突。</p>
 */
public final class ResourcePackBuilder {
	private final JavaPlugin plugin;
	private final SongLibrary library;
	private final MusicConfig config;
	private final Path packDir;

	public ResourcePackBuilder(JavaPlugin plugin, SongLibrary library, MusicConfig config) {
		this.plugin = plugin;
		this.library = library;
		this.config = config;
		this.packDir = plugin.getDataFolder().toPath().resolve("pack");
	}

	public Path packFile() {
		return packDir.resolve("plugmc-music.zip");
	}

	public record BuildResult(Path file, byte[] sha1, long bytes, int songs) {
	}

	/**
	 * 重新生成资源包；没有歌曲时也会生成一个只含 pack.mcmeta 的空包，避免下发 404。
	 */
	public BuildResult build() throws IOException {
		Files.createDirectories(packDir);
		Path target = packFile();
		Path temp = packDir.resolve("plugmc-music.zip.tmp");
		Map<String, byte[]> sounds = new LinkedHashMap<>();
		int count = 0;
		try (OutputStream fileOutput = Files.newOutputStream(temp);
			ZipOutputStream zip = new ZipOutputStream(fileOutput)) {
			writeEntry(zip, "pack.mcmeta", packMeta());
			for (Song song : library.songs()) {
				Path audio = library.audioFile(song.id()).orElse(null);
				if (audio == null) {
					continue;
				}
				writeEntry(zip, "assets/" + config.namespace() + "/sounds/"
					+ song.soundPath() + ".ogg", Files.readAllBytes(audio));
				sounds.put(song.soundPath().replace('/', '.'), null);
				count++;
			}
			writeEntry(zip, "assets/" + config.namespace() + "/sounds.json", soundsJson());
		}
		Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		byte[] sha1 = sha1(target);
		long bytes = Files.size(target);
		return new BuildResult(target, sha1, bytes, count);
	}

	private byte[] packMeta() {
		String json = "{\"pack\":{\"description\":\"plugmc custom music discs\","
			+ "\"pack_format\":" + config.packFormat() + "}}";
		return json.getBytes(StandardCharsets.UTF_8);
	}

	private byte[] soundsJson() {
		StringBuilder builder = new StringBuilder("{\n");
		int index = 0;
		for (Song song : library.songs()) {
			if (library.audioFile(song.id()).isEmpty()) {
				continue;
			}
			if (index++ > 0) {
				builder.append(",\n");
			}
			builder.append("  \"disc.").append(song.id()).append("\": {\n")
				.append("    \"sounds\": [\n")
				.append("      {\"name\": \"").append(config.namespace()).append(':')
				.append(song.soundPath()).append("\", \"stream\": true}\n")
				.append("    ]\n")
				.append("  }");
		}
		builder.append("\n}\n");
		return builder.toString().getBytes(StandardCharsets.UTF_8);
	}

	private void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(0L);
		zip.putNextEntry(entry);
		zip.write(data);
		zip.closeEntry();
	}

	private byte[] sha1(Path file) throws IOException {
		try (InputStream input = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
			return digest.digest();
		} catch (NoSuchAlgorithmException exception) {
			plugin.getLogger().warning("当前 JVM 不支持 SHA-1：" + exception.getMessage());
			return new byte[0];
		}
	}
}
