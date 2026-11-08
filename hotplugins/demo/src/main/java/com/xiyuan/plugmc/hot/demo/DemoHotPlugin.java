package com.xiyuan.plugmc.hot.demo;

import com.xiyuan.plugmc.api.HotPlugin;
import com.xiyuan.plugmc.api.HotPluginContext;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import org.slf4j.Logger;

/**
 * 最小热加载插件示例：注册一条命令和一个 tick 回调，卸载时自动移除。
 */
public final class DemoHotPlugin implements HotPlugin {
	private static final String VERSION = "0.1.0";

	private HotPluginContext context;
	private Logger logger;
	private int ticks;

	@Override
	public void onEnable(HotPluginContext context) {
		this.context = context;
		this.logger = context.logger();

		context.registerCommand(Commands.literal("hotdemo").executes(command -> {
			command.getSource().sendSuccess(
				() -> Component.literal("[HotDemo] v" + VERSION + " ticks=" + ticks),
				false
			);
			return 1;
		}));

		context.onServerTick(server -> {
			ticks++;
			if (ticks % 100 == 0) {
				logger.info("hotdemo heartbeat {}s", ticks / 20);
			}
		});

		logger.info("HotDemo {} enabled", VERSION);
	}

	@Override
	public void onDisable() {
		if (logger != null) {
			logger.info("HotDemo {} disabled after {} ticks", VERSION, ticks);
		}
	}
}
