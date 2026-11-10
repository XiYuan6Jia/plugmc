package com.xiyuan.plugmc.minigames;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 21 点牌桌：庄家 17 点停牌，黑杰克按配置赔率结算，平局退注。
 * 这里只算牌与结果，资金由调用方通过 {@link EconomyBridge} 处理。
 */
public final class BlackjackTable {
	private static final String[] SUITS = {"♠", "♥", "♣", "♦"};
	private static final String[] RANKS = {
		"", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"
	};

	public record Card(int rank, int suit) {
		public String label() {
			return SUITS[suit] + RANKS[rank];
		}

		public int value() {
			return rank == 1 ? 11 : Math.min(rank, 10);
		}
	}

	public enum Phase {
		PLAYER,
		DONE
	}

	private final List<Card> player = new ArrayList<>();
	private final List<Card> dealer = new ArrayList<>();
	private final int bet;
	private final double blackjackPayout;
	private Phase phase = Phase.PLAYER;
	private String message = "";
	private BigDecimal payout = BigDecimal.ZERO;
	private boolean doubled;

	public BlackjackTable(int bet, double blackjackPayout) {
		this.bet = bet;
		this.blackjackPayout = blackjackPayout;
		player.add(draw());
		dealer.add(draw());
		player.add(draw());
		dealer.add(draw());
		if (playerValue() == 21 || dealerValue() == 21) {
			settleNatural();
		}
	}

	public int bet() {
		return bet;
	}

	public double blackjackPayout() {
		return blackjackPayout;
	}

	public List<Card> playerCards() {
		return List.copyOf(player);
	}

	public List<Card> dealerCards() {
		return List.copyOf(dealer);
	}

	public int playerValue() {
		return value(player);
	}

	public int dealerValue() {
		return value(dealer);
	}

	public Phase phase() {
		return phase;
	}

	public String message() {
		return message;
	}

	public BigDecimal payout() {
		return payout;
	}

	public boolean doubled() {
		return doubled;
	}

	public boolean canDouble() {
		return phase == Phase.PLAYER && player.size() == 2;
	}

	/** 要牌；爆点立即结算为输。 */
	public void hit() {
		if (phase != Phase.PLAYER) {
			return;
		}
		player.add(draw());
		if (playerValue() > 21) {
			finish("爆点（" + playerValue() + " 点），本局输了。", BigDecimal.ZERO);
		}
	}

	/** 停牌，庄家按 17 点停牌补牌后比较大小。 */
	public void stand() {
		if (phase != Phase.PLAYER) {
			return;
		}
		dealerPlay();
		compare();
	}

	/** 加倍：注额翻倍并只补一张牌；返回是否成功（余额校验由调用方完成）。 */
	public boolean doubleDown() {
		if (!canDouble()) {
			return false;
		}
		doubled = true;
		player.add(draw());
		if (playerValue() > 21) {
			finish("加倍后爆点（" + playerValue() + " 点），本局输了。", BigDecimal.ZERO);
			return true;
		}
		dealerPlay();
		compare();
		return true;
	}

	private void settleNatural() {
		boolean playerBlackjack = playerValue() == 21;
		boolean dealerBlackjack = dealerValue() == 21;
		if (playerBlackjack && dealerBlackjack) {
			finish("双方都是黑杰克，平局退注。", BigDecimal.valueOf(bet));
			return;
		}
		if (playerBlackjack) {
			finish("黑杰克！赢得 " + money(bet * (1 + blackjackPayout)) + "。",
				BigDecimal.valueOf(bet * (1 + blackjackPayout)));
			return;
		}
		finish("庄家黑杰克，本局输了。", BigDecimal.ZERO);
	}

	private void dealerPlay() {
		while (dealerValue() < 17) {
			dealer.add(draw());
		}
	}

	private void compare() {
		int mine = playerValue();
		int theirs = dealerValue();
		if (theirs > 21) {
			finish("庄家爆点（" + theirs + " 点），你赢了 " + money(bet * 2) + "。", BigDecimal.valueOf(bet * 2L));
			return;
		}
		if (mine > theirs) {
			finish("你 " + mine + " 点对庄家 " + theirs + " 点，赢了 " + money(bet * 2) + "。",
				BigDecimal.valueOf(bet * 2L));
			return;
		}
		if (mine == theirs) {
			finish("双方都是 " + mine + " 点，平局退注。", BigDecimal.valueOf(bet));
			return;
		}
		finish("你 " + mine + " 点对庄家 " + theirs + " 点，本局输了。", BigDecimal.ZERO);
	}

	private void finish(String text, BigDecimal amount) {
		this.phase = Phase.DONE;
		this.message = text;
		this.payout = amount.setScale(2, RoundingMode.DOWN);
	}

	private static String money(double value) {
		return BigDecimal.valueOf(value).setScale(2, RoundingMode.DOWN).toPlainString();
	}

	private static Card draw() {
		return new Card(
			ThreadLocalRandom.current().nextInt(1, 14),
			ThreadLocalRandom.current().nextInt(SUITS.length)
		);
	}

	private static int value(List<Card> cards) {
		int total = 0;
		int aces = 0;
		for (Card card : cards) {
			total += card.value();
			if (card.rank() == 1) {
				aces++;
			}
		}
		while (total > 21 && aces > 0) {
			total -= 10;
			aces--;
		}
		return total;
	}
}
