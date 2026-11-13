package com.xiyuan.plugmc.bazaar;

import org.bukkit.inventory.ItemStack;

/**
 * 三类交易在摊位上的统一视图。
 *
 * <p>经济系统里三类交易的记录字段完全不一样（拍卖有起拍价/一口价/最高出价，
 * 挂单有单价 + 物品数量，收购有单价 + 还差多少），但摊位上只有一行位置可写，
 * 所以这里先归一成「谁、卖还是买、卖什么、什么价、还剩多少」，再由
 * {@link BazaarText} 决定怎么排版。</p>
 *
 * @param tradeId 经济系统里的编号，用于点摊位时直接提示对应的命令
 * @param ownerId 摊主的 UUID，点摊位时用它把界面过滤到这个人（可能为 null，见下）
 * @param icon    摆在货台上的实物展示（数量固定为 1，否则客户端会画出堆叠角标）
 */
public record BazaarDeal(
	Kind kind,
	long tradeId,
	String itemName,
	int amount,
	String priceLine,
	String ownerName,
	java.util.UUID ownerId,
	ItemStack icon
) {
	public enum Kind {
		/** 市场挂单：玩家手里有货在卖。 */
		MARKET("售卖"),
		/** 拍卖：有起拍价/一口价，可能已有人出价。 */
		AUCTION("拍卖"),
		/** 收购订单：玩家出钱求货。 */
		ORDER("求购");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public BazaarDeal {
		// 展示用图标统一成 1 个：数量在招牌文字里已经写了
		ItemStack copy = icon == null ? new ItemStack(org.bukkit.Material.PAPER) : icon.clone();
		copy.setAmount(1);
		icon = copy;
	}

	/** 摊位招牌上那一行「物品 × 数量」。 */
	public String itemLine() {
		if (amount <= 1) {
			return itemName;
		}
		return itemName + " ×" + amount;
	}
}
