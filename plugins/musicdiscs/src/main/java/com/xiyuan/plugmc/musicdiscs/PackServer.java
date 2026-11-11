package com.xiyuan.plugmc.musicdiscs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内置资源包下载服务：占用一个已分配的 TCP 端口，只暴露资源包本体与 sha1。
 *
 * <p>端口需要平台放行；当前测试服已分配 20101-20105，默认使用 20103。</p>
 */
public final class PackServer {
	private final JavaPlugin plugin;
	private final AtomicReference<byte[]> packBytes = new AtomicReference<>(new byte[0]);
	private final AtomicReference<byte[]> packSha1 = new AtomicReference<>(new byte[0]);
	private HttpServer server;
	private int boundPort;

	public PackServer(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public void update(Path pack, byte[] sha1) throws IOException {
		packBytes.set(Files.readAllBytes(pack));
		packSha1.set(sha1 == null ? new byte[0] : sha1);
	}

	public int boundPort() {
		return boundPort;
	}

	public boolean running() {
		return server != null;
	}

	public void start(MusicConfig config) {
		stop();
		if (!config.builtinPack()) {
			return;
		}
		try {
			InetSocketAddress address = config.bindAddress().isBlank()
				? new InetSocketAddress(config.port())
				: new InetSocketAddress(config.bindAddress(), config.port());
			HttpServer created = HttpServer.create(address, 0);
			created.createContext("/plugmc-music.zip", exchange ->
				respond(exchange, "application/zip", packBytes.get()));
			created.createContext("/plugmc-music.sha1", exchange ->
				respond(exchange, "text/plain; charset=utf-8", toHex(packSha1.get()).getBytes(
					StandardCharsets.UTF_8)));
			created.createContext("/", exchange -> respond(
				exchange,
				"text/plain; charset=utf-8",
				("plugmc-music resource pack\n"
					+ "download: /plugmc-music.zip\n"
					+ "sha1: " + toHex(packSha1.get()) + "\n")
					.getBytes(StandardCharsets.UTF_8)
			));
			created.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "plugmc-music-pack");
				thread.setDaemon(true);
				return thread;
			}));
			created.start();
			this.server = created;
			this.boundPort = created.getAddress().getPort();
			plugin.getLogger().info("资源包下载服务已启动：0.0.0.0:" + boundPort
				+ "（" + packBytes.get().length + " 字节）");
		} catch (IOException exception) {
			plugin.getLogger().severe("资源包下载服务启动失败（端口 " + config.port() + "）："
				+ exception.getMessage());
		}
	}

	public void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	/**
	 * 给玩家下发资源包；未生成或未配置 URL 时只提示不报错。
	 */
	public void send(Player player, MusicConfig config) {
		String url = config.packUrl();
		if (url == null || url.isBlank()) {
			player.sendMessage(MusicText.component("&c资源包地址未配置（pack.public-url）。"));
			return;
		}
		byte[] sha1 = packSha1.get();
		player.setResourcePack(
			UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
			url,
			sha1.length == 0 ? null : sha1,
			MusicText.component(config.prompt()),
			config.required()
		);
		player.sendMessage(MusicText.component("&7已发送资源包：&f" + url));
	}

	private void respond(HttpExchange exchange, String contentType, byte[] body) throws IOException {
		try {
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.getResponseHeaders().set("Cache-Control", "no-cache");
			exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				try (OutputStream output = exchange.getResponseBody()) {
					output.write(body);
				}
			}
		} finally {
			exchange.close();
		}
	}

	private String toHex(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return "";
		}
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(Character.forDigit((value >> 4) & 0xF, 16))
				.append(Character.forDigit(value & 0xF, 16));
		}
		return builder.toString();
	}
}
