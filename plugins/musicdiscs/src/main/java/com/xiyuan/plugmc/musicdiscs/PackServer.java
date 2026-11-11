package com.xiyuan.plugmc.musicdiscs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内置 HTTP 服务：下发资源包，并提供玩家上传页面。
 *
 * <p>只暴露四个路径：{@code /plugmc-music.zip}、{@code /plugmc-music.sha1}、
 * 上传页面 {@code /}、上传接口 {@code POST /upload}。端口需要平台放行。</p>
 */
public final class PackServer {
	private final MusicPlugin plugin;
	private final AtomicReference<byte[]> packBytes = new AtomicReference<>(new byte[0]);
	private final AtomicReference<byte[]> packSha1 = new AtomicReference<>(new byte[0]);
	private HttpServer server;
	private int boundPort;

	public PackServer(MusicPlugin plugin) {
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

	public void start(MusicConfig config) {		stop();
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
			created.createContext("/upload", this::handleUpload);
			created.createContext("/", this::handlePage);
			created.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
				Thread thread = new Thread(runnable, "plugmc-music-web");
				thread.setDaemon(true);
				return thread;
			}));
			created.start();
			this.server = created;
			this.boundPort = created.getAddress().getPort();
			plugin.getLogger().info("资源包下载与上传服务已启动：0.0.0.0:" + boundPort
				+ "（当前资源包 " + packBytes.get().length + " 字节）");
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
	 * 只在服务未启动时启动：资源包内容通过 AtomicReference 热更新，不需要重启监听端口。
	 */
	public void ensureStarted(MusicConfig config) {
		if (server == null) {
			start(config);
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

	private void handlePage(HttpExchange exchange) throws IOException {
		try {
			String path = exchange.getRequestURI().getPath();
			if (!path.equals("/") && !path.equals("/upload.html")) {
				respond(exchange, "text/plain; charset=utf-8",
					"plugmc-music\n".getBytes(StandardCharsets.UTF_8), 404);
				return;
			}
			respond(exchange, "text/html; charset=utf-8",
				uploadPage().getBytes(StandardCharsets.UTF_8));
		} catch (RuntimeException exception) {
			plugin.getLogger().warning("上传页面生成失败：" + exception);
			respond(exchange, "text/plain; charset=utf-8",
				"upload page error\n".getBytes(StandardCharsets.UTF_8), 500);
		}
	}

	/**
	 * POST /upload?token=XXXXXX&title=曲名，请求体是 ogg 原始字节。
	 */
	private void handleUpload(HttpExchange exchange) throws IOException {
		try {
			doHandleUpload(exchange);
		} catch (RuntimeException exception) {
			plugin.getLogger().warning("处理上传请求失败：" + exception);
			respond(exchange, "application/json; charset=utf-8",
				json(false, "服务器内部错误，请稍后再试").getBytes(StandardCharsets.UTF_8), 500);
		}
	}

	private void doHandleUpload(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			respond(exchange, "application/json; charset=utf-8",
				json(false, "请用页面上的上传按钮（POST）").getBytes(StandardCharsets.UTF_8), 405);
			return;
		}
		MusicConfig settings = plugin.settings();
		Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
		String token = query.getOrDefault("token", "");
		String title = query.getOrDefault("title", "");
		byte[] body;
		try (InputStream input = exchange.getRequestBody()) {
			body = input.readNBytes((int) Math.min(settings.uploadMaxBytes() + 1, Integer.MAX_VALUE));
			if (body.length > settings.uploadMaxBytes()) {
				respond(exchange, "application/json; charset=utf-8",
					json(false, "文件超过 " + (settings.uploadMaxBytes() / 1024 / 1024) + " MB 限制")
						.getBytes(StandardCharsets.UTF_8));
				return;
			}
		}
		UploadService.UploadResult result = plugin.uploads().accept(token, title, body, settings);
		respond(exchange, "application/json; charset=utf-8",
			json(result.ok(), result.message()).getBytes(StandardCharsets.UTF_8));
	}

	private String uploadPage() {
		MusicConfig settings = plugin.settings();
		long maxMb = settings.uploadMaxBytes() / 1024 / 1024;
		String maxDuration = MusicText.duration(settings.uploadMaxSeconds());
		return """
			<!doctype html>
			<html lang="zh-CN"><head><meta charset="utf-8">
			<meta name="viewport" content="width=device-width,initial-scale=1">
			<title>上传自定义唱片</title>
			<style>
			body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#14161a;color:#e6e6e6;
			margin:0;padding:24px;display:flex;justify-content:center}
			main{max-width:520px;width:100%}
			h1{font-size:20px;margin:0 0 8px}
			p{color:#9aa4b2;font-size:14px;line-height:1.6;margin:6px 0}
			label{display:block;margin:14px 0 6px;font-size:14px;color:#cbd5e1}
			input{width:100%%;padding:10px;border-radius:8px;border:1px solid #2b3038;background:#1b1f26;
			color:#e6e6e6;font-size:14px;box-sizing:border-box}
			button{margin-top:16px;width:100%%;padding:12px;border:0;border-radius:8px;background:#2f7cf6;
			color:#fff;font-size:15px;cursor:pointer}
			button:disabled{background:#39404b;cursor:not-allowed}
			pre{white-space:pre-wrap;background:#1b1f26;border:1px solid #2b3038;border-radius:8px;
			padding:12px;font-size:13px;color:#9fe3a1;margin-top:16px;min-height:20px}
			.tip{font-size:13px;color:#7c8798}
			</style></head><body><main>
			<h1>上传自定义唱片</h1>
			<p>1. 游戏内执行 <b>/music upload</b> 获取 6 位上传码。</p>
			<p>2. 音频需为 <b>OGG Vorbis</b>，不超过 <b>%d MB</b>，时长不超过 <b>%s</b>。</p>
			<p class="tip">每个账号限一首；如需更换请先在游戏内 <b>/music delete</b>。</p>
			<label for="token">上传码</label>
			<input id="token" maxlength="6" placeholder="例如 A1B2C3" autocomplete="off">
			<label for="title">曲名（可选）</label>
			<input id="title" maxlength="32" placeholder="不填则用你的游戏名">
			<label for="file">选择 .ogg 文件</label>
			<input id="file" type="file" accept=".ogg,audio/ogg">
			<button id="go">开始上传</button>
			<pre id="out"></pre>
			</main>
			<script>
			const out=document.getElementById('out'),go=document.getElementById('go');
			go.addEventListener('click',async()=>{
			  const token=document.getElementById('token').value.trim().toUpperCase();
			  const title=document.getElementById('title').value.trim();
			  const file=document.getElementById('file').files[0];
			  if(!token||!file){out.textContent='请填写上传码并选择文件';return;}
			  go.disabled=true;out.textContent='上传中…';
			  try{
			    const res=await fetch('/upload?token='+encodeURIComponent(token)
			      +'&title='+encodeURIComponent(title),
			      {method:'POST',headers:{'Content-Type':'application/octet-stream'},body:file});
			    const data=await res.json();
			    out.textContent=(data.ok?'✅ ':'❌ ')+data.message;
			  }catch(err){out.textContent='❌ 上传失败：'+err;}
			  go.disabled=false;
			});
			</script>
			</body></html>
			""".replace("%%", "\u0000").replace("%d", Long.toString(maxMb))
			.replace("%s", maxDuration).replace("\u0000", "%");
	}

	private String json(boolean ok, String message) {
		return "{\"ok\":" + ok + ",\"message\":\"" + message
			.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
	}

	private Map<String, String> parseQuery(String rawQuery) {
		Map<String, String> values = new HashMap<>();
		if (rawQuery == null || rawQuery.isBlank()) {
			return values;
		}
		for (String pair : rawQuery.split("&")) {
			int index = pair.indexOf('=');
			if (index <= 0) {
				continue;
			}
			values.put(
				URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
				URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8)
			);
		}
		return values;
	}

	private void respond(HttpExchange exchange, String contentType, byte[] body) throws IOException {
		respond(exchange, contentType, body, 200);
	}

	private void respond(HttpExchange exchange, String contentType, byte[] body, int status)
		throws IOException {
		try {
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.getResponseHeaders().set("Cache-Control", "no-cache");
			exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
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
