package com.xiyuan.plugmc.demo;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class Demo extends JavaPlugin {
	@Override
	public void onEnable() {
		getLogger().info("示例插件 已启用");
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		sender.sendMessage("示例插件 正在运行");
		return true;
	}
}
