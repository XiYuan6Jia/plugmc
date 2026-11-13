package com.xiyuan.plugmc.economy.vault;

import com.xiyuan.plugmc.economy.api.EconomyService;
import com.xiyuan.plugmc.economy.bank.BankService;
import com.xiyuan.plugmc.economy.shop.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 回收箱：把普通箱子/木桶变成「投进去就自动结算」的特殊容器。
 *
 * <p>绑定关系写在方块自己的 PDC 上（{@code plugmc:vault-owner}），不额外维护坐标索引 ——
 * 箱子被拆、被复制、被 /setblock 覆盖时，绑定跟着方块走，不会留悬空记录。</p>
 *
 * <p><b>写 PDC 和读 PDC 必须用同一个 state 对象。</b>
 * {@code block.getState()} 每次都会 new 一个新的快照，改 A 的快照、再 update B 的快照
 * 等于什么也没写回去 —— 曾经因此出现「提示绑定成功，但下次再来读就成了未绑定」。</p>
 *
 * <p>结算顺序是「先进建材银行、再卖官方商店」：建材银行是给建材准备的专用通道，
 * 存进去换的是可直接在商店抵扣的额度；先卖商店的话材料就变成普通金币，
 * 银行这条设计意图就落空了。两道都不收的物品原样留在箱子里，不会被吞掉。</p>
 */
public final class VaultService {
	private final JavaPlugin plugin;
	private final ShopService shop;
	private final BankService bank;
	private final EconomyService economy;
	private final NamespacedKey ownerKey;
	private final NamespacedKey nameKey;
	/** 已经排在下一 tick 要结算的方块，避免同一批投递被拆成十几次结算。 */
	private final java.util.Set<String> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** 结算延后 2 tick，给漏斗这一轮的搬运留出收尾时间。 */
	private static final long SETTLE_DELAY_TICKS = 2L;

	public VaultService(
		JavaPlugin plugin,
		ShopService shop,
		BankService bank,
		EconomyService economy
	) {
		this.plugin = plugin;
		this.shop = shop;
		this.bank = bank;
		this.economy = economy;
		this.ownerKey = new NamespacedKey(plugin, "vault-owner");
		this.nameKey = new NamespacedKey(plugin, "vault-owner-name");
	}

	/** 方块是不是回收箱（带绑定标记的容器）。 */
	public boolean isVault(Block block) {
		return ownerOf(block) != null;
	}

	/** 绑定的玩家 UUID；没绑定时返回 null。 */
	public UUID ownerOf(Block block) {
		Container container = containerOf(block);
		if (container == null) {
			return null;
		}
		String raw = container.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException exception) {
			// 数据被手改坏了，当作没绑定，免得每次结算都抛异常
			return null;
		}
	}

	/** 绑定时记下的玩家名，用于离线时也能显示归属。 */
	public String ownerNameOf(Block block) {
		Container container = containerOf(block);
		if (container == null) {
			return null;
		}
		return container.getPersistentDataContainer().get(nameKey, PersistentDataType.STRING);
	}

	/**
	 * 把准星指着的箱子/木桶转成回收箱，并绑定到当前玩家。
	 *
	 * @return true 表示这次调用真的完成了绑定
	 */
	public boolean bind(Player player, Block block) {
		if (!supported(block)) {
			player.sendMessage("只能把箱子或木桶变成回收箱。");
			return false;
		}
		if (doubled(block)) {
			player.sendMessage("这个箱子是双箱，回收箱只支持单个箱子，请先拆成一个大箱子。");
			return false;
		}
		Container container = containerOf(block);
		if (container == null) {
			player.sendMessage("这个方块读不出数据，无法绑定。");
			return false;
		}
		PersistentDataContainer data = container.getPersistentDataContainer();
		String raw = data.get(ownerKey, PersistentDataType.STRING);
		if (raw != null && !raw.isBlank()) {
			if (raw.equals(player.getUniqueId().toString())) {
				player.sendMessage("这个箱子已经是你的回收箱了。");
			} else {
				player.sendMessage("这个箱子已经绑给别的玩家了。");
			}
			return false;
		}
		data.set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
		data.set(nameKey, PersistentDataType.STRING, player.getName());
		// 同一个 state 上改完再 update，才是真的写回方块实体
		container.update(true, false);
		player.sendMessage("已把这个" + label(block) + "变成你的回收箱："
			+ "别人往里放的物品会自动结算，收益直接进你的账户。");
		return true;
	}

	/** 解除绑定，箱子里现有的东西原样保留。 */
	public boolean unbind(Player player, Block block) {
		Container container = containerOf(block);
		if (container == null) {
			player.sendMessage("这个方块还不是回收箱。");
			return false;
		}
		PersistentDataContainer data = container.getPersistentDataContainer();
		String raw = data.get(ownerKey, PersistentDataType.STRING);
		if (raw == null || raw.isBlank()) {
			player.sendMessage("这个方块还不是回收箱。");
			return false;
		}
		boolean owner = raw.equals(player.getUniqueId().toString());
		if (!owner && !player.hasPermission("economy.vault.admin")) {
			player.sendMessage("这个回收箱不属于你，只有它的主人能解除。");
			return false;
		}
		data.remove(ownerKey);
		data.remove(nameKey);
		container.update(true, false);
		player.sendMessage("已把这个" + label(block) + "恢复成普通容器，里面的物品没有动。");
		return true;
	}

	/**
	 * 结算延后到下一 tick：事件回调里直接改容器容易与其他插件打架。
	 *
	 * <p>同一 tick 里漏斗可能连续搬十几件物品进来，每一件都会触发一次
	 * {@link InventoryMoveItemEvent}；如果每次都排一次结算，同一个物品会被重复处理。
	 * 所以按方块去重：已经排上队的方块再被触发就忽略，等这批搬完统一结算一次。</p>
	 */
	public void settleLater(Block block) {
		if (!isVault(block)) {
			return;
		}
		String key = keyOf(block);
		if (!pending.add(key)) {
			return;
		}
		Block snapshot = block.getLocation().clone().getBlock();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			pending.remove(key);
			settle(snapshot);
		}, SETTLE_DELAY_TICKS);
	}

	/** 方块在「已排队」集合里的键：世界 + 坐标。 */
	private String keyOf(Block block) {
		return block.getWorld().getUID() + "@" + block.getX() + "," + block.getY() + "," + block.getZ();
	}

	/**
	 * 把回收箱里的物品换成收益：能进建材银行的进额度，剩下的卖到官方商店，
	 * 两道都不收的留回箱子里。收益全部记在绑定玩家名下，他不在线也照记。
	 */
	public void settle(Block block) {
		if (doubled(block)) {
			return;
		}
		Container container = containerOf(block);
		if (container == null) {
			return;
		}
		UUID owner = ownerOf(block);
		if (owner == null) {
			return;
		}
		// 必须用快照 inventory 改内容再 update：直接改 Container.getInventory() 得到一个
		// 与方块实体脱钩的视图，清空动作不会落盘，同一个物品会被反复结算（相当于无限刷钱）
		Inventory inventory = container.getSnapshotInventory();
		List<ItemStack> contents = new ArrayList<>();
		for (ItemStack stack : inventory.getContents()) {
			if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
				continue;
			}
			contents.add(stack.clone());
		}
		if (contents.isEmpty()) {
			return;
		}

		String ownerName = ownerNameOf(block);
		List<ItemStack> afterBank = new ArrayList<>();
		BankService.DepositResult bankResult = bank.depositBatchFor(
			owner,
			ownerName,
			contents,
			afterBank
		);
		List<ItemStack> afterShop = new ArrayList<>();
		ShopService.SellResult shopResult = shop.sellBatchFor(owner, afterBank, afterShop);

		// 先把能转换的取走，再把两道都不收的放回去 —— 顺序反了会把自己刚放进去的又结算一遍
		inventory.clear();
		for (ItemStack leftover : afterShop) {
			inventory.addItem(leftover);
		}
		container.update(true, false);

		boolean nothing = bankResult.count() <= 0 && shopResult.soldCount() <= 0;
		if (nothing) {
			return;
		}
		String summary = describe(bankResult, shopResult);
		Player online = Bukkit.getPlayer(owner);
		if (online != null && online.isOnline()) {
			online.sendMessage(summary);
		}
	}

	private String describe(BankService.DepositResult bankResult, ShopService.SellResult shopResult) {
		StringBuilder builder = new StringBuilder("回收箱结算：");
		boolean first = true;
		if (bankResult.count() > 0) {
			builder.append("建材 ").append(bankResult.count()).append(" 个 → 额度 ")
				.append(bank.format(bankResult.points()));
			first = false;
		}
		if (shopResult.soldCount() > 0) {
			if (!first) {
				builder.append("；");
			}
			builder.append("卖出 ").append(shopResult.soldCount()).append(" 个 → ")
				.append(economy.format(shopResult.earned()));
			if (shopResult.tax().signum() > 0) {
				builder.append("（税 ").append(economy.format(shopResult.tax())).append("）");
			}
		}
		return builder.toString();
	}

	/** 方块是不是能当回收箱的容器类型。 */
	private boolean supported(Block block) {
		if (block == null) {
			return false;
		}
		Material type = block.getType();
		return type == Material.CHEST || type == Material.BARREL;
	}

	/** 双箱的绑定只会写到其中一半上，语义不清楚，干脆不支持。 */
	private boolean doubled(Block block) {
		Container container = containerOf(block);
		if (container == null) {
			return false;
		}
		return container.getInventory().getHolder() instanceof DoubleChest;
	}

	private Container containerOf(Block block) {
		if (!supported(block)) {
			return null;
		}
		return block.getState() instanceof Container container ? container : null;
	}

	private String label(Block block) {
		return block.getType() == Material.BARREL ? "木桶" : "箱子";
	}
}
