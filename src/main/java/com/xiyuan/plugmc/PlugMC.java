package com.xiyuan.plugmc;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlugMC implements ModInitializer {
	public static final String MOD_ID = "plugmc";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("PlugMC 已加载");
	}
}
