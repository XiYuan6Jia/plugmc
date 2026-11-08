package com.xiyuan.plugmc.hot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

import com.xiyuan.plugmc.api.HotPlugin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 扫描并管理 {@code plugmc-hot/} 下的热加载插件。
 */
public final class HotPluginManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("plugmc-hot");
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_-]*");
	private static final String DESCRIPTOR_PATH = "plugmc-plugin.json";

	// Brigadier 没有公开的移除 API，而解析命令时用的是 literals/arguments 缓存，
	// 只从 children 里删掉仍会被解析到，因此需要同时清理三张表。
	private static final Field CHILDREN_FIELD = nodeField("children");
	private static final Field LITERALS_FIELD = nodeField("literals");
	private static final Field ARGUMENTS_FIELD = nodeField("arguments");

	private final MinecraftServer server;
	private final Path hotDir;
	private final Map<String, LoadedHotPlugin> plugins = new LinkedHashMap<>();

	public HotPluginManager(MinecraftServer server) {
		this.server = server;
		this.hotDir = server.getServerDirectory().resolve("plugmc-hot").toAbsolutePath().normalize();
		try {
			Files.createDirectories(hotDir);
		} catch (IOException exception) {
			LOGGER.error("无法创建热加载目录 {}", hotDir, exception);
		}
	}

	public MinecraftServer server() {
		return server;
	}

	public Path hotDir() {
		return hotDir;
	}

	public List<PluginInfo> pluginInfos() {
		return plugins.values().stream()
			.map(plugin -> new PluginInfo(
				plugin.descriptor().id(),
				plugin.descriptor().name(),
				plugin.descriptor().version(),
				plugin.jar().getFileName().toString()
			))
			.toList();
	}

	public record PluginInfo(String id, String name, String version, String jar) {
	}

	/**
	 * 加载热加载目录下的全部 jar，单个失败不影响其它插件。
	 *
	 * @return 本次成功加载的数量
	 */
	public int loadAll() {
		List<Path> jars;
		try {
			Files.createDirectories(hotDir);
			try (Stream<Path> stream = Files.list(hotDir)) {
				jars = stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".jar"))
					.sorted()
					.toList();
			}
		} catch (IOException exception) {
			LOGGER.error("无法读取热加载目录 {}", hotDir, exception);
			return 0;
		}

		int loaded = 0;
		for (Path jar : jars) {
			try {
				load(jar);
				loaded++;
			} catch (Exception exception) {
				LOGGER.error("加载热插件失败 {}: {}", jar.getFileName(), exception.getMessage(), exception);
			}
		}

		LOGGER.info("热加载目录 {}：本次加载 {} 个插件", hotDir, loaded);
		return loaded;
	}

	public PluginInfo load(Path jar) throws Exception {
		Path normalized = jar.toAbsolutePath().normalize();
		if (!normalized.startsWith(hotDir)) {
			throw new IllegalArgumentException("jar 必须位于热加载目录内: " + hotDir);
		}
		if (!Files.isRegularFile(normalized)) {
			throw new IllegalArgumentException("文件不存在: " + normalized);
		}

		PluginDescriptor descriptor = readDescriptor(normalized);
		if (plugins.containsKey(descriptor.id())) {
			throw new IllegalStateException("插件已加载: " + descriptor.id());
		}

		URLClassLoader classLoader = new URLClassLoader(
			new URL[]{normalized.toUri().toURL()},
			HotPlugin.class.getClassLoader()
		);
		LoadedHotPlugin plugin = null;
		try {
			Class<?> mainClass = Class.forName(descriptor.main(), true, classLoader);
			if (!HotPlugin.class.isAssignableFrom(mainClass)) {
				throw new IllegalArgumentException(descriptor.main() + " 未实现 " + HotPlugin.class.getName());
			}

			HotPlugin instance = (HotPlugin) mainClass.getDeclaredConstructor().newInstance();
			plugin = new LoadedHotPlugin(descriptor, normalized, classLoader, instance);
			plugin.context(new HotPluginContextImpl(this, plugin));
			instance.onEnable(plugin.context());

			plugins.put(descriptor.id(), plugin);
			refreshCommands();
			LOGGER.info("已热加载插件 {} {} <- {}", descriptor.id(), descriptor.version(), normalized.getFileName());
			return new PluginInfo(descriptor.id(), descriptor.name(), descriptor.version(), normalized.getFileName().toString());
		} catch (Throwable throwable) {
			if (plugin != null) {
				removeCommands(plugin);
				plugin.tickCallbacks().clear();
				plugin.chatHandlers().clear();
			}
			closeQuietly(classLoader);
			if (throwable instanceof Exception exception) {
				throw exception;
			}
			throw new IllegalStateException(throwable);
		}
	}

	public void unload(String id) {
		LoadedHotPlugin plugin = plugins.remove(id);
		if (plugin == null) {
			throw new IllegalArgumentException("未加载: " + id);
		}

		try {
			plugin.instance().onDisable();
		} catch (Throwable throwable) {
			logger(plugin).error("插件卸载回调异常", throwable);
		}

		removeCommands(plugin);
		plugin.tickCallbacks().clear();
		plugin.chatHandlers().clear();
		closeQuietly(plugin.classLoader());
		refreshCommands();
		LOGGER.info("已卸载热插件 {}", id);
	}

	public void reload(String id) {
		LoadedHotPlugin plugin = plugins.get(id);
		if (plugin == null) {
			throw new IllegalArgumentException("未加载: " + id);
		}

		Path jar = plugin.jar();
		unload(id);
		try {
			load(jar);
		} catch (Exception exception) {
			throw new IllegalStateException("重载失败 " + id + ": " + exception.getMessage(), exception);
		}
	}

	public int reloadAll() {
		for (String id : List.copyOf(plugins.keySet())) {
			unload(id);
		}
		return loadAll();
	}

	/**
	 * 数据包重载后命令树可能被重建，把已加载插件的命令重新挂回去。
	 */
	public void rebindCommands() {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		for (LoadedHotPlugin plugin : plugins.values()) {
			for (LiteralCommandNode<CommandSourceStack> node : plugin.commands()) {
				if (dispatcher.getRoot().getChild(node.getName()) == null) {
					dispatcher.getRoot().addChild(node);
				}
			}
		}
		refreshCommands();
	}

	public void tick() {
		if (plugins.isEmpty()) {
			return;
		}

		for (LoadedHotPlugin plugin : List.copyOf(plugins.values())) {
			for (Consumer<MinecraftServer> callback : List.copyOf(plugin.tickCallbacks())) {
				try {
					callback.accept(server);
				} catch (Throwable throwable) {
					logger(plugin).error("tick 回调异常", throwable);
				}
			}
		}
	}

	public void dispatchChat(ServerPlayer sender, String message) {
		if (plugins.isEmpty() || message == null || message.isBlank()) {
			return;
		}

		for (LoadedHotPlugin plugin : List.copyOf(plugins.values())) {
			for (var handler : List.copyOf(plugin.chatHandlers())) {
				try {
					handler.accept(sender, message);
				} catch (Throwable throwable) {
					logger(plugin).error("聊天回调异常", throwable);
				}
			}
		}
	}

	public void close() {
		for (String id : List.copyOf(plugins.keySet())) {
			LoadedHotPlugin plugin = plugins.remove(id);
			try {
				plugin.instance().onDisable();
			} catch (Throwable throwable) {
				logger(plugin).error("插件卸载回调异常", throwable);
			}
			plugin.tickCallbacks().clear();
			plugin.chatHandlers().clear();
			closeQuietly(plugin.classLoader());
		}
	}

	void registerCommand(LoadedHotPlugin plugin, LiteralCommandNode<CommandSourceStack> node) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		if (dispatcher.getRoot().getChild(node.getName()) != null) {
			throw new IllegalArgumentException("命令已存在: " + node.getName());
		}

		dispatcher.getRoot().addChild(node);
		plugin.commands().add(node);
		refreshCommands();
	}

	private void removeCommands(LoadedHotPlugin plugin) {
		if (plugin.commands().isEmpty()) {
			return;
		}

		RootCommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot();
		for (LiteralCommandNode<CommandSourceStack> node : plugin.commands()) {
			removeNode(root, node);
		}
		plugin.commands().clear();
	}

	private static void removeNode(CommandNode<?> root, CommandNode<?> node) {
		removeFromMap(root, CHILDREN_FIELD, node);
		removeFromMap(root, LITERALS_FIELD, node);
		removeFromMap(root, ARGUMENTS_FIELD, node);
	}

	private static void removeFromMap(CommandNode<?> root, Field field, CommandNode<?> node) {
		if (field == null) {
			return;
		}
		try {
			if (field.get(root) instanceof Map<?, ?> map) {
				map.values().remove(node);
			}
		} catch (IllegalAccessException exception) {
			LOGGER.warn("无法从命令树移除 {}", node.getName(), exception);
		}
	}

	private static Field nodeField(String name) {
		try {
			Field field = CommandNode.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException exception) {
			LOGGER.warn("Brigadier 内部字段 {} 不存在，卸载后的命令可能需要重启才能完全消失", name);
			return null;
		}
	}

	private void refreshCommands() {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			server.getCommands().sendCommands(player);
		}
	}

	private Logger logger(LoadedHotPlugin plugin) {
		return plugin.context() == null ? LOGGER : plugin.context().logger();
	}

	private PluginDescriptor readDescriptor(Path path) throws IOException {
		try (JarFile jarFile = new JarFile(path.toFile())) {
			JarEntry entry = jarFile.getJarEntry(DESCRIPTOR_PATH);
			if (entry == null) {
				throw new IllegalArgumentException("缺少 " + DESCRIPTOR_PATH + ": " + path.getFileName());
			}

			JsonObject json;
			try (Reader reader = new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8)) {
				json = JsonParser.parseReader(reader).getAsJsonObject();
			}

			String id = string(json, "id", "");
			String main = string(json, "main", "");
			if (!ID_PATTERN.matcher(id).matches()) {
				throw new IllegalArgumentException("非法插件 id: " + id);
			}
			if (main.isBlank()) {
				throw new IllegalArgumentException(DESCRIPTOR_PATH + " 缺少 main");
			}

			return new PluginDescriptor(id, string(json, "name", id), string(json, "version", "0.0.0"), main);
		}
	}

	private static String string(JsonObject json, String key, String fallback) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
	}

	private static void closeQuietly(URLClassLoader classLoader) {
		try {
			classLoader.close();
		} catch (IOException ignored) {
		}
	}
}
