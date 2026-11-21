package com.xiyuan.plugmc.lowhp;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
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
 * 把插件内置的两段提示音打包成客户端资源包（zip），并提供 SHA-1 与时长信息。
 *
 * <p>音频以 {@code stream: true} 注册，长片段也不会一次性占满内存。</p>
 */
public final class AlertPack {
	private static final String NAMESPACE = "lowhp";

	private final JavaPlugin plugin;
	private final Path packDir;

	public AlertPack(JavaPlugin plugin) {
		this.plugin = plugin;
		this.packDir = plugin.getDataFolder().toPath().resolve("pack");
	}

	public Path packFile() {
		return packDir.resolve("lowhp-alert.zip");
	}

	public record BuildResult(Path file, byte[] sha1, long bytes, Map<String, Float> durations) {
	}

	public BuildResult build(LowHpConfig config) throws IOException {
		Files.createDirectories(packDir);
		Path temp = packDir.resolve("lowhp-alert.zip.tmp");
		Map<String, Float> durations = new LinkedHashMap<>();
		try (OutputStream fileOutput = Files.newOutputStream(temp);
			ZipOutputStream zip = new ZipOutputStream(fileOutput)) {
			writeEntry(zip, "pack.mcmeta", packMeta(config));
			for (Alert alert : Alert.values()) {
				byte[] data = readResource("pack/" + alert.fileName());
				if (data == null) {
					plugin.getLogger().warning("资源包里缺少内置音频：" + alert.fileName());
					continue;
				}
				OggDuration.Reader info = OggDuration.probe(data);
				if (info == null) {
					plugin.getLogger().warning("内置音频不是有效的 OGG：" + alert.fileName());
					continue;
				}
				durations.put(alert.id(), info.seconds());
				writeEntry(zip, "assets/" + NAMESPACE + "/sounds/" + alert.soundPath() + ".ogg", data);
			}
			writeEntry(zip, "assets/" + NAMESPACE + "/sounds.json", soundsJson());
		}
		Path target = packFile();
		Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return new BuildResult(target, sha1(target), Files.size(target), durations);
	}

	private byte[] packMeta(LowHpConfig config) {
		String json = "{\"pack\":{\"description\":\"lowhp alert sounds\","
			+ "\"min_format\":" + formatArray(config.minFormat()) + ","
			+ "\"max_format\":" + formatArray(config.maxFormat()) + "}}";
		return json.getBytes(StandardCharsets.UTF_8);
	}

	private String formatArray(java.util.List<Integer> format) {
		return "[" + format.get(0) + "," + format.get(1) + "]";
	}

	private byte[] soundsJson() {
		StringBuilder builder = new StringBuilder("{\n");
		Alert[] values = Alert.values();
		for (int index = 0; index < values.length; index++) {
			Alert alert = values[index];
			if (index > 0) {
				builder.append(",\n");
			}
			builder.append("  \"").append(alert.soundPath().replace('/', '.')).append("\": {\n")
				.append("    \"sounds\": [\n")
				.append("      {\"name\": \"").append(NAMESPACE).append(':')
				.append(alert.soundPath()).append("\", \"stream\": true}\n")
				.append("    ]\n")
				.append("  }");
		}
		builder.append("\n}\n");
		return builder.toString().getBytes(StandardCharsets.UTF_8);
	}

	private byte[] readResource(String path) throws IOException {
		try (InputStream input = plugin.getResource(path)) {
			if (input == null) {
				return null;
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			input.transferTo(output);
			return output.toByteArray();
		}
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
