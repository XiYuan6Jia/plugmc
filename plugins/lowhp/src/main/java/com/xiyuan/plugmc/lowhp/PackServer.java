package com.xiyuan.plugmc.lowhp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内置 HTTP 服务：只暴露低血量提示音资源包的 zip 与 sha1，端口需平台放行。
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

	public void update(AlertPack.BuildResult result) throws IOException {
		packBytes.set(java.nio.file.Files.readAllBytes(result.file()));
		packSha1.set(result.sha1());
	}

	public boolean running() {
		return server != null;
	}

	public int boundPort() {
		return boundPort;
	}

	public void start(LowHpConfig config) {
		stop();
		try {
			InetSocketAddress address = config.bindAddress().isBlank()
				? new InetSocketAddress(config.port())
				: new InetSocketAddress(config.bindAddress(), config.port());
			HttpServer created = HttpServer.create(address, 0);
			created.createContext("/lowhp-alert.zip", exchange ->
				respond(exchange, "application/zip", packBytes.get()));
			created.createContext("/lowhp-alert.sha1", exchange ->
				respond(exchange, "text/plain; charset=utf-8",
					toHex(packSha1.get()).getBytes(StandardCharsets.UTF_8)));
			created.createContext("/", exchange -> respond(
				exchange,
				"text/plain; charset=utf-8",
				("lowhp alert sound pack\n"
					+ "download: /lowhp-alert.zip\n"
					+ "sha1: " + toHex(packSha1.get()) + "\n")
					.getBytes(StandardCharsets.UTF_8)
			));
			created.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
				Thread thread = new Thread(runnable, "lowhp-pack");
				thread.setDaemon(true);
				return thread;
			}));
			created.start();
			server = created;
			boundPort = created.getAddress().getPort();
			plugin.getLogger().info("提示音资源包服务已启动：0.0.0.0:" + boundPort
				+ "（" + packBytes.get().length + " 字节）");
		} catch (IOException exception) {
			plugin.getLogger().severe("资源包服务启动失败（端口 " + config.port() + "）："
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
	 * 给玩家下发资源包；没有配置地址时只提示。
	 */
	public void send(Player player, LowHpConfig config) {
		String url = config.packUrl() == null ? "" : config.packUrl().trim();
		if (url.isEmpty()) {
			return;
		}
		byte[] sha1 = packSha1.get();
		player.setResourcePack(
			UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
			url,
			sha1.length == 0 ? null : sha1,
			LowHpText.component(config.prompt()),
			config.required()
		);
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
