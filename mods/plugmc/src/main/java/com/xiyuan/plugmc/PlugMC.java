package com.xiyuan.plugmc;

import com.xiyuan.plugmc.command.PlugMCCommand;
import com.xiyuan.plugmc.hot.HotPluginManager;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlugMC implements ModInitializer {
	public static final String MOD_ID = "plugmc";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static HotPluginManager hotPluginManager;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			PlugMCCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			hotPluginManager = new HotPluginManager(server);
			hotPluginManager.loadAll();
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (hotPluginManager != null) {
				hotPluginManager.close();
				hotPluginManager = null;
			}
		});

		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success && hotPluginManager != null) {
				hotPluginManager.rebindCommands();
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (hotPluginManager != null) {
				hotPluginManager.tick();
			}
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, parameters) -> {
			HotPluginManager manager = hotPluginManager;
			if (manager != null) {
				manager.dispatchChat(sender, message.signedContent());
			}
		});

		LOGGER.info("PlugMC 已加载，热加载目录: plugmc-hot/");
	}

	public static HotPluginManager hotPluginManager() {
		return hotPluginManager;
	}
}
