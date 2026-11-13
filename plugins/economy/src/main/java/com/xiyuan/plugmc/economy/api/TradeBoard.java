package com.xiyuan.plugmc.economy.api;

import com.xiyuan.plugmc.economy.auction.AuctionListing;
import com.xiyuan.plugmc.economy.market.MarketListing;
import com.xiyuan.plugmc.economy.order.BuyOrder;

import java.util.List;

/**
 * 玩家三类交易的只读聚合视图，供外部插件（例如主城商业街）做实时展示。
 *
 * <p>三个方法每次调用都重新读取当前挂单，不做缓存 —— 展示方自己决定刷新频率。
 * 返回的都是不可变快照，修改它们不会影响经济数据。</p>
 *
 * <p>通过 {@code Bukkit.getServicesManager().load(TradeBoard.class)} 取用；
 * economy 未加载或尚未完成注册时返回 null。</p>
 */
public interface TradeBoard {

	/** 当前全部拍卖项（含已结束的，调用方按 {@link AuctionListing#active()} 过滤）。 */
	List<AuctionListing> auctions();

	/** 当前全部市场挂单（含已售出/过期的，按 {@link MarketListing#active()} 过滤）。 */
	List<MarketListing> markets();

	/** 当前全部收购订单（含已完成/过期的，按 {@link BuyOrder#active()} 过滤）。 */
	List<BuyOrder> orders();
}
