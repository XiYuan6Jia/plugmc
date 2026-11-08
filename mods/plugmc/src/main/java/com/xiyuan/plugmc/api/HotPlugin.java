package com.xiyuan.plugmc.api;

/**
 * 热加载插件入口。实现类必须提供无参构造器，且随 jar 一起打上
 * {@code plugmc-plugin.json} 描述文件。
 */
public interface HotPlugin {
	/**
	 * 插件被加载时调用。抛出的异常会导致本次加载失败并回滚。
	 */
	void onEnable(HotPluginContext context) throws Exception;

	/**
	 * 插件被卸载或服务器关闭时调用。
	 */
	default void onDisable() {
	}
}
