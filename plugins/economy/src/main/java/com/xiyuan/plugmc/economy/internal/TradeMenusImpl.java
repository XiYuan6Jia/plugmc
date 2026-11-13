package com.xiyuan.plugmc.economy.internal;

import com.xiyuan.plugmc.economy.api.TradeMenus;
import com.xiyuan.plugmc.economy.gui.AuctionGui;
import com.xiyuan.plugmc.economy.gui.MarketGui;
import com.xiyuan.plugmc.economy.gui.OrderGui;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * {@link TradeMenus} 的实现：把请求转给经济系统自己的三个界面。
 *
 * <p>这里刻意不做任何过滤逻辑 —— 只判断「这个人还在不在卖/卖过」，真正把列表
 * 按卖家切出来的是 {@code MarketGui#openSeller} 等入口。这样界面里的翻页、详情、
 * 购买/出价/交付全部还是原来那套代码，商业街不会因为「按摊位区分」而多出一份
 * 容易跟主界面跑偏的副本。</p>
 */
public final class TradeMenusImpl implements TradeMenus {
	private final MarketGui marketGui;
	private final AuctionGui auctionGui;
	private final OrderGui orderGui;

	public TradeMenusImpl(MarketGui marketGui, AuctionGui auctionGui, OrderGui orderGui) {
		this.marketGui = marketGui;
		this.auctionGui = auctionGui;
		this.orderGui = orderGui;
	}

	@Override
	public boolean openMarketOf(Player viewer, UUID seller) {
		return marketGui != null && seller != null && marketGui.openSeller(viewer, seller);
	}

	@Override
	public boolean openAuctionOf(Player viewer, UUID seller) {
		return auctionGui != null && seller != null && auctionGui.openSeller(viewer, seller);
	}

	@Override
	public boolean openOrderOf(Player viewer, UUID seller) {
		return orderGui != null && seller != null && orderGui.openBuyer(viewer, seller);
	}
}
