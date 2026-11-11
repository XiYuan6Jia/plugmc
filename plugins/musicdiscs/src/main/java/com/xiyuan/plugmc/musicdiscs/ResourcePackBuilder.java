package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * 把唱片库打包成客户端资源包（zip）：{@code sounds.json} + 每首歌的 ogg，
 * 以及每张唱片独立的物品模型与 16×16 贴图。
 *
 * <p>物品模型走 {@code minecraft:item_model} 组件 + {@code assets/<ns>/items/<model>.json}，
 * <b>不会覆盖任何原版物品模型</b>，唱片也不再占用原版唱片材质。</p>
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
				if (config.useItemModel()) {
					writeItemModel(zip, song);
				}
				count++;
			}
			writeEntry(zip, "assets/" + config.namespace() + "/sounds.json", soundsJson());
		}
		Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		byte[] sha1 = sha1(target);
		long bytes = Files.size(target);
		return new BuildResult(target, sha1, bytes, count);
	}

	private void writeItemModel(ZipOutputStream zip, Song song) throws IOException {
		String namespace = config.namespace();
		String model = song.itemModel();
		writeEntry(zip, "assets/" + namespace + "/items/" + model + ".json",
			("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace
				+ ":item/" + model + "\"}}\n").getBytes(StandardCharsets.UTF_8));
		writeEntry(zip, "assets/" + namespace + "/models/item/" + model + ".json",
			("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\""
				+ namespace + ":item/" + model + "\"}}\n").getBytes(StandardCharsets.UTF_8));
		writeEntry(zip, "assets/" + namespace + "/textures/item/" + model + ".png",
			discTexture(song.id()));
	}

	/**
	 * 生成 16×16 的黑胶唱片贴图，颜色由曲目 ID 决定，每首歌都有独立外观。
	 */
	private byte[] discTexture(String id) throws IOException {
		int hash = id.hashCode();
		float hue = Math.abs(hash % 360) / 360f;
		java.awt.Color base = java.awt.Color.getHSBColor(hue, 0.65f, 0.75f);
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		double center = 7.5;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double distance = Math.hypot(x - center, y - center);
				int argb = 0;
				if (distance <= 7.2) {
					if (distance <= 1.2) {
						argb = 0x00000000;
					} else if (distance >= 6.2) {
						argb = 0xFF202020 | (base.getRGB() & 0x00FFFFFF);
					} else if (distance >= 5.2) {
						argb = brighten(base, 1.25f);
					} else if (((int) Math.floor(distance)) % 2 == 0) {
						argb = brighten(base, 0.75f);
					} else {
						argb = base.getRGB() | 0xFF000000;
					}
				}
				image.setRGB(x, y, argb);
			}
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, "png", output);
		return output.toByteArray();
	}

	private int brighten(java.awt.Color color, float factor) {
		int red = Math.min(255, (int) (color.getRed() * factor));
		int green = Math.min(255, (int) (color.getGreen() * factor));
		int blue = Math.min(255, (int) (color.getBlue() * factor));
		return 0xFF000000 | (red << 16) | (green << 8) | blue;
	}

	private byte[] packMeta() {
		String json = "{\"pack\":{\"description\":\"plugmc custom music discs\","
			+ "\"pack_format\":" + config.packFormat() + "}}";
		return json.getBytes(StandardCharsets.UTF_8);
	}

	private byte[] soundsJson() {
		Map<String, Song> entries = new LinkedHashMap<>();
		for (Song song : library.songs()) {
			if (library.audioFile(song.id()).isPresent()) {
				entries.put(song.soundPath().replace('/', '.'), song);
			}
		}
		StringBuilder builder = new StringBuilder("{\n");
		int index = 0;
		for (Song song : entries.values()) {
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
		try (var input = Files.newInputStream(file)) {
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
