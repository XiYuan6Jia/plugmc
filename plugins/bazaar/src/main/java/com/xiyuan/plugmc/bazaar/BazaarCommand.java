package com.xiyuan.plugmc.bazaar;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /bazaar [status|list|check|rebuild|reload] */
public final class BazaarCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS =
		List.of("status", "list", "check", "rebuild", "reload");

	private final BazaarPlugin plugin;

	public BazaarCommand(BazaarPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			status(sender);
			return true;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "status" -> status(sender);
			case "list" -> list(sender);
			case "check" -> check(sender);
			case "rebuild" -> rebuild(sender);
			case "reload" -> reload(sender);
			default -> sender.sendMessage(BazaarText.component("&7用法：/bazaar [status|list|check|rebuild|reload]"));
		}
		return true;
	}

	private boolean admin(CommandSender sender) {
		if (sender.hasPermission("bazaar.admin")) {
			return true;
		}
		sender.sendMessage(BazaarText.component("&c你没有这个权限。"));
		return false;
	}

	private void status(CommandSender sender) {
		BazaarConfig config = plugin.config();
		int capacity = BazaarDistributor.capacity(config);
		int used = plugin.stalls() == null ? 0 : plugin.stalls().occupiedCount();
		sender.sendMessage(BazaarText.component("&f&l商业街状态"));
		sender.sendMessage(BazaarText.component("&7维度 &f" + config.worldName() + " &7地面 &fY=" + config.floorY()));
		sender.sendMessage(BazaarText.component("&7玩家摊位 &f" + used + " / " + capacity
			+ " &7（装饰方案 " + config.decors().size() + " 套）"));
		sender.sendMessage(BazaarText.component("&7刷新间隔 &f" + config.refreshTicks() + " tick"));
		if (capacity == 0) {
			sender.sendMessage(BazaarText.component("&e摊位区还没配置（stalls.player 的 x1/z1 要大于 x0/z0）。"));
		}
		int overflow = plugin.distributor() == null ? 0 : plugin.distributor().lastOverflow();
		if (overflow > 0) {
			sender.sendMessage(BazaarText.component("&e有 &f" + overflow + " &e位玩家因为摊位满了没能上摊。"));
		}
	}

	private void list(CommandSender sender) {
		BazaarTenants tenants = plugin.currentTenants();
		if (tenants == null) {
			sender.sendMessage(BazaarText.component("&7经济系统没有提供交易数据。"));
			return;
		}
		if (tenants.isEmpty()) {
			sender.sendMessage(BazaarText.component("&7现在没有任何人在摆摊。"));
			return;
		}
		sender.sendMessage(BazaarText.component("&f&l正在摆摊的玩家（" + tenants.tenants().size() + "）"));
		Map<String, Integer> slots = plugin.stalls() == null ? Map.of() : plugin.stalls().occupancySummary();
		for (BazaarTenants.Tenant tenant : tenants.tenants()) {
			Integer slot = slots.get(tenant.name());
			String where = slot == null ? "&8（还没上摊）" : "&8#" + (slot + 1);
			sender.sendMessage(BazaarText.component(
				"&f" + tenant.name() + " " + where + " &7" + tenant.kindSummary()));
			for (BazaarDeal deal : tenant.deals()) {
				sender.sendMessage(BazaarText.component("  " + BazaarText.dealLine(deal)));
			}
		}
	}

	/** 自检：真摆一个摊位、刷新一次、再解放，逐项核对。跑完不留痕迹。 */
	private void check(CommandSender sender) {
		if (!admin(sender)) {
			return;
		}
		sender.sendMessage(BazaarText.component("&7正在自检（摆一个摊位 → 刷新 → 解放 → 核对还原）…"));
		List<String> report = plugin.selfTest().run();
		int pass = 0;
		for (String line : report) {
			sender.sendMessage(BazaarText.component(line));
			if (line.startsWith("&a")) {
				pass++;
			}
		}
		boolean allOk = pass == report.size();
		sender.sendMessage(BazaarText.component((allOk ? "&a" : "&e")
			+ "自检完成：" + pass + " / " + report.size() + " 项通过"));
	}

	private void rebuild(CommandSender sender) {
		if (!admin(sender)) {
			return;
		}
		plugin.stalls().releaseAll();
		BazaarTenants tenants = plugin.currentTenants();
		if (tenants == null) {
			sender.sendMessage(BazaarText.component("&e已清空摊位；经济系统没有提供交易数据，暂时不会重建。"));
			return;
		}
		plugin.distributor().sync(tenants, plugin.config());
		sender.sendMessage(BazaarText.component("&a已按当前交易重建摊位：&f"
			+ plugin.stalls().occupiedCount() + " &a个。"));
	}

	private void reload(CommandSender sender) {
		if (!admin(sender)) {
			return;
		}
		plugin.reloadAll();
		sender.sendMessage(BazaarText.component("&a商业街配置已重载，摊位已按新配置重建。"));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			List<String> out = new ArrayList<>();
			String prefix = args[0].toLowerCase(Locale.ROOT);
			for (String sub : SUBCOMMANDS) {
				if (sub.startsWith(prefix)) {
					out.add(sub);
				}
			}
			return out;
		}
		return List.of();
	}
}
