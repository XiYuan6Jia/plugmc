package com.xiyuan.plugmc.economy;

import com.xiyuan.plugmc.economy.api.EconomyService;
import com.xiyuan.plugmc.economy.gui.EconomyMenuHolder;
import com.xiyuan.plugmc.economy.gui.MailGui;
import com.xiyuan.plugmc.economy.gui.MarketGui;
import com.xiyuan.plugmc.economy.gui.MenuListener;
import com.xiyuan.plugmc.economy.gui.ShopGui;
import com.xiyuan.plugmc.economy.internal.EconomyConfig;
import com.xiyuan.plugmc.economy.internal.EconomyServiceImpl;
import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import com.xiyuan.plugmc.economy.mail.MailCommand;
import com.xiyuan.plugmc.economy.mail.MailConfig;
import com.xiyuan.plugmc.economy.mail.MailService;
import com.xiyuan.plugmc.economy.market.MarketCommand;
import com.xiyuan.plugmc.economy.market.MarketConfig;
import com.xiyuan.plugmc.economy.market.MarketService;
import com.xiyuan.plugmc.economy.order.OrderCommand;
import com.xiyuan.plugmc.economy.order.OrderConfig;
import com.xiyuan.plugmc.economy.order.OrderService;
import com.xiyuan.plugmc.economy.shop.GoodsConfig;
import com.xiyuan.plugmc.economy.shop.ShopCommand;
import com.xiyuan.plugmc.economy.shop.ShopConfig;
import com.xiyuan.plugmc.economy.shop.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public final class EconomyPlugin extends JavaPlugin implements Listener {
	private EconomyConfig config;
	private EconomyStore store;
	private LedgerService ledger;
	private EconomyServiceImpl economyService;
	private MarketService marketService;
	private MailService mailService;
	private OrderService orderService;
	private BukkitTask snapshotTask;
	private BukkitTask marketTask;
	private BukkitTask mailTask;
	private BukkitTask orderTask;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		try {
			config = EconomyConfig.load(getConfig());
			store = new EconomyStore(this, config);
			ledger = new LedgerService(store, config);
			economyService = new EconomyServiceImpl(ledger, config);
			Bukkit.getServicesManager().register(
				EconomyService.class,
				economyService,
				this,
				ServicePriority.Normal
			);
			getServer().getPluginManager().registerEvents(this, this);

			EconomyCommand command = new EconomyCommand(this, economyService, ledger, store, config);
			registerCommand("money", command);
			registerCommand("pay", command);
			registerCommand("eco", command);

			ShopConfig shopConfig = ShopConfig.load(getConfig());
			GoodsConfig goodsConfig = GoodsConfig.load(this, shopConfig, config.decimalPlaces());
			ShopService shopService = new ShopService(this, ledger, store, shopConfig, goodsConfig);
			ShopGui shopGui = new ShopGui(shopService);
			getServer().getPluginManager().registerEvents(new MenuListener(), this);
			registerCommand("shop", new ShopCommand(shopService, shopGui));

			MailConfig mailConfig = MailConfig.load(getConfig());
			mailService = new MailService(this, store, ledger, mailConfig);
			MarketConfig marketConfig = MarketConfig.load(getConfig(), config.decimalPlaces());
			marketService = new MarketService(this, ledger, store, mailService, marketConfig);
			registerCommand("market", new MarketCommand(marketService, new MarketGui(marketService)));
			registerCommand("mail", new MailCommand(mailService, new MailGui(mailService)));
			orderService = new OrderService(this, ledger, store, OrderConfig.load(getConfig(), config.decimalPlaces()));
			registerCommand("order", new OrderCommand(orderService));
			marketTask = marketService.startExpiryTask();
			mailTask = mailService.startExpiryTask();
			orderTask = orderService.startExpiryTask();

			long interval = config.snapshotIntervalSeconds() * 20L;
			snapshotTask = getServer().getScheduler().runTaskTimer(this, () -> {
				try {
					store.snapshot();
				} catch (RuntimeException exception) {
					getLogger().log(Level.SEVERE, "定时保存经济数据失败", exception);
				}
			}, interval, interval);

			getLogger().info("经济系统 已启用（账本 + 快照模式，数据目录：" + getDataFolder() + "）");
		} catch (RuntimeException exception) {
			getLogger().log(Level.SEVERE, "经济系统启动失败，插件将被禁用", exception);
			getServer().getPluginManager().disablePlugin(this);
		}
	}

	@Override
	public void onDisable() {
		if (snapshotTask != null) {
			snapshotTask.cancel();
			snapshotTask = null;
		}
		if (marketTask != null) {
			marketTask.cancel();
			marketTask = null;
		}
		if (mailTask != null) {
			mailTask.cancel();
			mailTask = null;
		}
		if (orderTask != null) {
			orderTask.cancel();
			orderTask = null;
		}
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof EconomyMenuHolder) {
				player.closeInventory();
			}
		}
		if (store != null) {
			try {
				store.close();
			} catch (RuntimeException exception) {
				getLogger().log(Level.SEVERE, "关闭经济数据存储失败", exception);
			}
			store = null;
		}
		if (economyService != null) {
			Bukkit.getServicesManager().unregister(EconomyService.class, economyService);
			economyService = null;
		}
		getLogger().info("经济系统 已停用");
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		if (economyService == null || store == null) {
			return;
		}
		economyService.createAccount(event.getPlayer().getUniqueId());
		store.touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
		if (marketService != null) {
			marketService.reconcile(event.getPlayer());
		}
		if (mailService != null) {
			mailService.notify(event.getPlayer());
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		if (store != null) {
			store.touch(event.getPlayer().getUniqueId(), event.getPlayer().getName());
		}
	}

	private void registerCommand(String name, CommandExecutor executor) {
		var pluginCommand = getCommand(name);
		if (pluginCommand == null) {
			getLogger().warning("plugin.yml 缺少命令定义：" + name);
			return;
		}
		pluginCommand.setExecutor(executor);
		if (executor instanceof TabCompleter completer) {
			pluginCommand.setTabCompleter(completer);
		}
	}
}
