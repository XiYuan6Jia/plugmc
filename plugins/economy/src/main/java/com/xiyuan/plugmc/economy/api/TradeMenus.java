package com.xiyuan.plugmc.economy.api;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 让外部插件（主城商业街）直接打开「某一个摊主的交易界面」。
 *
 * <p>和 {@link TradeBoard} 的分工：{@code TradeBoard} 只读数据，这边负责把人送进
 * 经济系统自己的界面。三个方法打开的仍然是 {@code MarketGui} / {@code AuctionGui} /
 * {@code OrderGui}，只是列表预先按卖家过滤过 —— 玩家在里面点的每一步还是走原有的
 * 余额、税费、托管、限购校验，这里不复制任何交易逻辑。</p>
 *
 * <p>返回 false 表示「这个人没有对应的在售项」，调用方应给玩家一句提示。</p>
 *
 * <p>通过 {@code Bukkit.getServicesManager().load(TradeMenus.class)} 取用；
 * economy 未加载或尚未完成注册时返回 null。</p>
 */
public interface TradeMenus {

	/** 打开某人的市场挂单列表。 */
	boolean openMarketOf(Player viewer, UUID seller);

	/** 打开某人的拍卖列表。 */
	boolean openAuctionOf(Player viewer, UUID seller);

	/** 打开某人的收购订单列表。 */
	boolean openOrderOf(Player viewer, UUID seller);
}
