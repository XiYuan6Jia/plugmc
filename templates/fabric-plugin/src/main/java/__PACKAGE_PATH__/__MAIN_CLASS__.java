package __PACKAGE__;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class __MAIN_CLASS__ implements ModInitializer {
	public static final String MOD_ID = "__MOD_ID__";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("__MOD_NAME__ 已加载");
	}
}
