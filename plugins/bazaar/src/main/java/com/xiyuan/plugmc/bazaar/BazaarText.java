package com.xiyuan.plugmc.bazaar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** 招牌与提示文字的排版工具。 */
final class BazaarText {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	private BazaarText() {
	}

	static Component component(String text) {
		return LEGACY.deserialize(text == null ? "" : text);
	}

	static String plain(Component component) {
		return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
			.serialize(component);
	}

	/**
	 * 把摊主信息渲染成招牌文本。
	 *
	 * <p>第一行玩家名、第二行种类概览、之后逐条列出交易，超过 {@code sign-max-deals} 条时
	 * 最后一行改成「…还有 N 项」。行数上限来自配置而不是招牌实体的高度 —— 招牌是
	 * {@link org.bukkit.entity.TextDisplay}，行数变化时以自身中心为基准上下伸展，
	 * 只要不超过这个上限就不会压到人偶头上。</p>
	 *
	 * @param config   配置，取 {@code stalls.sign-max-deals}
	 * @param tenant   摊主
	 */
	static List<String> signLines(BazaarConfig config, BazaarTenants.Tenant tenant) {
		List<String> out = new ArrayList<>();
		int limit = Math.max(1, config.maxDeals());
		int shown = 0;
		for (BazaarDeal deal : tenant.deals()) {
			if (shown >= limit) {
				break;
			}
			out.add("&7· &f" + deal.kind().label() + " &e" + deal.itemLine());
			shown++;
		}
		int rest = tenant.deals().size() - shown;
		if (rest > 0) {
			String more = "&7…还有 &f" + rest + " &7项";
			if (out.size() >= limit) {
				out.set(out.size() - 1, more);
			} else {
				out.add(more);
			}
		}
		return out;
	}

	/** 招牌最上面两行：名字 + 种类概览。 */
	static String headerLine(BazaarTenants.Tenant tenant) {
		return "&f&l" + tenant.name();
	}

	static String kindLine(BazaarTenants.Tenant tenant) {
		return "&7" + tenant.kindSummary();
	}

	/** 一条交易的完整描述，用于点击摊位后的聊天提示。 */
	static String dealLine(BazaarDeal deal) {
		return "&7" + deal.kind().label() + " &f" + deal.itemLine() + " &7· &e" + deal.priceLine();
	}

	/** 摊位上点一下之后给出的操作提示。 */
	static String hintFor(BazaarDeal.Kind kind) {
		return switch (kind) {
			case MARKET -> "&7输入 &f/market buy <编号> &7购买";
			case AUCTION -> "&7输入 &f/auction bid <编号> <金额> &7出价";
			case ORDER -> "&7输入 &f/order fill <编号> &7交货";
		};
	}
}
