package com.xiyuan.plugmc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.xiyuan.plugmc.PlugMC;
import com.xiyuan.plugmc.hot.HotPluginManager;
import com.xiyuan.plugmc.hot.HotPluginManager.PluginInfo;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

public final class PlugMCCommand {
	private static final String PREFIX = "[PlugMC] ";

	private PlugMCCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("plugmc")
			.requires(source -> Commands.LEVEL_ALL.check(source.permissions()));

		root.then(Commands.literal("list")
			.executes(context -> list(context.getSource())));
		root.then(Commands.literal("path")
			.executes(context -> path(context.getSource())));
		root.then(Commands.literal("load")
			.requires(admin())
			.then(Commands.argument("file", StringArgumentType.greedyString())
				.executes(context -> load(context.getSource(), StringArgumentType.getString(context, "file")))));
		root.then(Commands.literal("unload")
			.requires(admin())
			.then(Commands.argument("id", StringArgumentType.word())
				.executes(context -> unload(context.getSource(), StringArgumentType.getString(context, "id")))));
		root.then(Commands.literal("reload")
			.requires(admin())
			.then(Commands.argument("id", StringArgumentType.word())
				.executes(context -> reload(context.getSource(), StringArgumentType.getString(context, "id")))));
		root.then(Commands.literal("reloadall")
			.requires(admin())
			.executes(context -> reloadAll(context.getSource())));

		dispatcher.register(root);
	}

	private static Predicate<CommandSourceStack> admin() {
		return source -> Commands.LEVEL_ADMINS.check(source.permissions());
	}

	private static int list(CommandSourceStack source) {
		HotPluginManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}

		List<PluginInfo> infos = manager.pluginInfos();
		if (infos.isEmpty()) {
			reply(source, "当前没有已加载的热插件。目录: " + manager.hotDir(), false);
			return 1;
		}

		StringBuilder builder = new StringBuilder("已加载 ").append(infos.size()).append(" 个热插件:");
		for (PluginInfo info : infos) {
			builder.append('\n')
				.append("- ")
				.append(info.id())
				.append(' ')
				.append(info.version())
				.append(" (")
				.append(info.name())
				.append(") <- ")
				.append(info.jar());
		}
		reply(source, builder.toString(), false);
		return infos.size();
	}

	private static int path(CommandSourceStack source) {
		HotPluginManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}

		reply(source, "热加载目录: " + manager.hotDir(), false);
		return 1;
	}

	private static int load(CommandSourceStack source, String file) {
		HotPluginManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}

		String name = file.trim();
		if (name.isEmpty()) {
			failure(source, "文件名不能为空");
			return 0;
		}

		Path target = manager.hotDir().resolve(name).normalize();
		if (!target.startsWith(manager.hotDir())) {
			failure(source, "只允许加载热加载目录内的 jar");
			return 0;
		}
		if (!name.endsWith(".jar")) {
			target = target.resolveSibling(target.getFileName() + ".jar");
		}
		if (!Files.isRegularFile(target)) {
			failure(source, "文件不存在: " + target);
			return 0;
		}

		try {
			PluginInfo info = manager.load(target);
			reply(source, "已加载 " + info.id() + " " + info.version(), true);
			return 1;
		} catch (Exception exception) {
			failure(source, "加载失败: " + exception.getMessage());
			return 0;
		}
	}

	private static int unload(CommandSourceStack source, String id) {
		HotPluginManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}

		try {
			manager.unload(id);
			reply(source, "已卸载 " + id, true);
			return 1;
		} catch (RuntimeException exception) {
			failure(source, exception.getMessage());
			return 0;
		}
	}

	private static int reload(CommandSourceStack source, String id) {
		HotPluginManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}

		try {
			manager.reload(id);
			reply(source, "已重载 " + id, true);
			return 1;
		} catch (RuntimeException exception) {
			failure(source, exception.getMessage());
			return 0;
		}
	}

	private static int reloadAll(CommandSourceStack source) {
		HotPluginManager manager = requireManager(source);
		if (manager == null) {
			return 0;
		}

		int loaded = manager.reloadAll();
		reply(source, "重载完成，本次加载 " + loaded + " 个热插件", true);
		return loaded;
	}

	private static HotPluginManager requireManager(CommandSourceStack source) {
		HotPluginManager manager = PlugMC.hotPluginManager();
		if (manager == null) {
			failure(source, "热加载管理器尚未就绪");
		}
		return manager;
	}

	private static void reply(CommandSourceStack source, String message, boolean broadcast) {
		source.sendSuccess(() -> Component.literal(PREFIX + message), broadcast);
	}

	private static void failure(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(PREFIX + message));
	}
}
