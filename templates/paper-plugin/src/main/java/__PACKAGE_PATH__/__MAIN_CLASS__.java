package __PACKAGE__;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class __MAIN_CLASS__ extends JavaPlugin {
	@Override
	public void onEnable() {
		getLogger().info("__MOD_NAME__ 已启用");
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		sender.sendMessage("__MOD_NAME__ 正在运行");
		return true;
	}
}
