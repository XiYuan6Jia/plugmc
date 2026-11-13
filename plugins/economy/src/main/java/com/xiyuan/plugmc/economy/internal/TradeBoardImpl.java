package com.xiyuan.plugmc.economy.internal;

import com.xiyuan.plugmc.economy.api.TradeBoard;
import com.xiyuan.plugmc.economy.auction.AuctionListing;
import com.xiyuan.plugmc.economy.auction.AuctionService;
import com.xiyuan.plugmc.economy.market.MarketListing;
import com.xiyuan.plugmc.economy.market.MarketService;
import com.xiyuan.plugmc.economy.order.BuyOrder;
import com.xiyuan.plugmc.economy.order.OrderService;

import java.util.List;

/**
 * {@link TradeBoard} 的实现：把三个交易服务的 listings()/orders() 串起来。
 *
 * <p>三个服务在 onDisable 时会被置空，这里每次调用都做空值兜底，
 * 避免插件关闭过程中还在刷新的展示方抛空指针。</p>
 */
public final class TradeBoardImpl implements TradeBoard {
	private final MarketService market;
	private final OrderService order;
	private final AuctionService auction;

	public TradeBoardImpl(MarketService market, OrderService order, AuctionService auction) {
		this.market = market;
		this.order = order;
		this.auction = auction;
	}

	@Override
	public List<AuctionListing> auctions() {
		return auction == null ? List.of() : auction.listings();
	}

	@Override
	public List<MarketListing> markets() {
		return market == null ? List.of() : market.listings();
	}

	@Override
	public List<BuyOrder> orders() {
		return order == null ? List.of() : order.orders();
	}
}
