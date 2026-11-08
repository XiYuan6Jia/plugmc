package com.xiyuan.plugmc.api;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * 热加载插件可用的宿主能力。注册进去的命令与 tick 回调会在插件卸载时自动移除。
 */
public interface HotPluginContext {
	String id();

	MinecraftServer server();

	Logger logger();

	/**
	 * 注册一条服务端命令；命令名冲突时加载失败。
	 */
	void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command);

	/**
	 * 注册服务器 tick 回调（每 tick 一次，运行在服务端线程）。
	 */
	void onServerTick(Consumer<MinecraftServer> callback);
}
