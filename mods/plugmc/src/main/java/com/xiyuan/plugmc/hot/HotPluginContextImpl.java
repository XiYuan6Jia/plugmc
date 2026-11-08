package com.xiyuan.plugmc.hot;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.xiyuan.plugmc.api.HotPluginContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class HotPluginContextImpl implements HotPluginContext {
	private final HotPluginManager manager;
	private final LoadedHotPlugin plugin;
	private final Logger logger;

	HotPluginContextImpl(HotPluginManager manager, LoadedHotPlugin plugin) {
		this.manager = manager;
		this.plugin = plugin;
		this.logger = LoggerFactory.getLogger("plugmc-hot/" + plugin.descriptor().id());
	}

	@Override
	public String id() {
		return plugin.descriptor().id();
	}

	@Override
	public MinecraftServer server() {
		return manager.server();
	}

	@Override
	public Logger logger() {
		return logger;
	}

	@Override
	public void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
		manager.registerCommand(plugin, command.build());
	}

	@Override
	public void onServerTick(Consumer<MinecraftServer> callback) {
		plugin.tickCallbacks().add(callback);
	}

	@Override
	public void onChatMessage(BiConsumer<ServerPlayer, String> handler) {
		plugin.chatHandlers().add(handler);
	}
}
