package com.xiyuan.plugmc.bazaar;

import com.xiyuan.plugmc.economy.api.TradeBoard;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 商业街：把经济系统的三类交易（挂单/拍卖/求购）实时投射成主城里的实体摊位。
 *
 * <p>本插件<b>不持有任何交易数据</b>，只做两件事：隔一段时间向经济系统要一份当前挂单，
 * 按玩家分组，然后让摊位与人偶去对齐这份快照。所以交易怎么变（上架、成交、取消、过期），
 * 商业街都会在下一次刷新时自动跟着变 —— 这就是「动态实时联动」的全部机制。</p>
 *
 * <p>经济系统没装时插件不会报错退出，只是空转并给出提示：商业街的数据来源缺失时，
 * 宁可什么都不显示，也不要显示一份和真实交易对不上的假数据。</p>
 */
public final class BazaarPlugin extends JavaPlugin {
	private BazaarConfig config;
	private BazaarStalls stalls;
	private BazaarDistributor distributor;
	private BazaarSelfTest selfTest;
	private BukkitTask refreshTask;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		config = BazaarConfig.load(getConfig());
		stalls = new BazaarStalls(this, config);
		distributor = new BazaarDistributor(stalls);
		selfTest = new BazaarSelfTest(this, stalls);

		// 上一轮（崩溃/强杀）可能留下孤儿实体：必须先清干净，否则人偶会叠在一起
		int orphans = stalls.sweepOrphans();
		if (orphans > 0) {
			getLogger().info("清理了上一轮遗留的摊位实体：" + orphans);
		}

		// 固定摊位（官方/银行/活动）与玩家交易无关，插件一启用就摆出来
		stalls.buildFixed();

		getServer().getPluginManager().registerEvents(new BazaarListener(this, stalls), this);
		BazaarCommand command = new BazaarCommand(this);
		if (getCommand("bazaar") != null) {
			getCommand("bazaar").setExecutor(command);
			getCommand("bazaar").setTabCompleter(command);
		}

		startRefresh();
		getLogger().info("商业街已启用：" + BazaarDistributor.capacity(config) + " 个玩家摊位，" + config.decors().size() + " 套装饰");
	}

	@Override
	public void onDisable() {
		stopRefresh();
		if (stalls != null) {
			// 停服时全部还原，避免把装饰方块留在存档里
			stalls.releaseAll();
		}
	}

	/** 重载配置：先把现有摊位全部还原，再按新配置重来。 */
	public void reloadAll() {
		stopRefresh();
		stalls.releaseAll();
		reloadConfig();
		config = BazaarConfig.load(getConfig());
		stalls = new BazaarStalls(this, config);
		distributor = new BazaarDistributor(stalls);
		selfTest = new BazaarSelfTest(this, stalls);
		stalls.sweepOrphans();
		stalls.buildFixed();
		startRefresh();
	}

	private void startRefresh() {
		stopRefresh();
		long ticks = config.refreshTicks();
		refreshTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, ticks);
	}

	private void stopRefresh() {
		if (refreshTask != null) {
			refreshTask.cancel();
			refreshTask = null;
		}
	}

	private void tick() {
		BazaarTenants tenants = readTenants();
		if (tenants == null) {
			return;
		}
		distributor.sync(tenants, config);
	}

	/** 现场向经济系统要一份当前租户快照；经济插件没装时返回 null。 */
	BazaarTenants currentTenants() {
		return readTenants();
	}

	private BazaarTenants readTenants() {
		TradeBoard board = tradeBoard();
		if (board == null) {
			return null;
		}
		return BazaarTenants.from(board.markets(), board.auctions(), board.orders());
	}

	/** 取经济系统的只读聚合视图；经济插件没装或还没启用时返回 null。 */
	private TradeBoard tradeBoard() {
		if (Bukkit.getPluginManager().getPlugin("economy") == null) {
			return null;
		}
		RegisteredServiceProvider<TradeBoard> provider =
			Bukkit.getServicesManager().getRegistration(TradeBoard.class);
		return provider == null ? null : provider.getProvider();
	}

	BazaarConfig config() {
		return config;
	}

	BazaarStalls stalls() {
		return stalls;
	}

	BazaarDistributor distributor() {
		return distributor;
	}

	BazaarSelfTest selfTest() {
		return selfTest;
	}
}
