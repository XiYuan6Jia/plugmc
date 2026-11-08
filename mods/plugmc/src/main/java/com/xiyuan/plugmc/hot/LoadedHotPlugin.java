package com.xiyuan.plugmc.hot;

import com.mojang.brigadier.tree.LiteralCommandNode;

import com.xiyuan.plugmc.api.HotPlugin;
import com.xiyuan.plugmc.api.HotPluginContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class LoadedHotPlugin {
	private final PluginDescriptor descriptor;
	private final Path jar;
	private final URLClassLoader classLoader;
	private final HotPlugin instance;
	private final List<LiteralCommandNode<CommandSourceStack>> commands = new ArrayList<>();
	private final List<Consumer<MinecraftServer>> tickCallbacks = new ArrayList<>();
	private HotPluginContext context;

	LoadedHotPlugin(PluginDescriptor descriptor, Path jar, URLClassLoader classLoader, HotPlugin instance) {
		this.descriptor = descriptor;
		this.jar = jar;
		this.classLoader = classLoader;
		this.instance = instance;
	}

	PluginDescriptor descriptor() {
		return descriptor;
	}

	Path jar() {
		return jar;
	}

	URLClassLoader classLoader() {
		return classLoader;
	}

	HotPlugin instance() {
		return instance;
	}

	HotPluginContext context() {
		return context;
	}

	void context(HotPluginContext context) {
		this.context = context;
	}

	List<LiteralCommandNode<CommandSourceStack>> commands() {
		return commands;
	}

	List<Consumer<MinecraftServer>> tickCallbacks() {
		return tickCallbacks;
	}
}
