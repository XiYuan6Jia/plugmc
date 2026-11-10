package com.xiyuan.plugmc.economy.bank;

import com.xiyuan.plugmc.economy.internal.InventoryUtil;
import com.xiyuan.plugmc.economy.shop.GoodsConfig;
import com.xiyuan.plugmc.economy.shop.ShopCategory;
import com.xiyuan.plugmc.economy.shop.ShopItem;
import com.xiyuan.plugmc.economy.shop.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 建材银行：把建材按合成表估值折算成额度，存入后可以取出同类或其他同类建材，
 * 也可以在系统商店结账时代替金币扣款。
 *
 * <p>防刷物资要点：</p>
 * <ul>
 *   <li>只收商店目录内的干净物品（无自定义名称/Lore/附魔/损坏/容器数据）；</li>
 *   <li>存入先扣物品再加额度，取出先扣额度再加物品，放不下立即退回额度；</li>
 *   <li>估值与额度全部使用整数最小单位并向下取整，估值不超过商店买价与任何合成成本；</li>
 *   <li>所有操作在服务端主线程同步完成，每次变动立即原子写盘。</li>
 * </ul>
 */
public final class BankService {
	private static final String ROOT = "players.";

	private final JavaPlugin plugin;
	private final ShopService shop;
	private final int decimalPlaces;
	private final File file;
	private final Map<UUID, Long> credit = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Set<Material> acceptedMaterials = new HashSet<>();
	private volatile BankConfig config;
	private volatile Map<String, Long> values = Map.of();

	public BankService(JavaPlugin plugin, ShopService shop, BankConfig config, int decimalPlaces) {
		this.plugin = plugin;
		this.shop = shop;
		this.config = config;
		this.decimalPlaces = decimalPlaces;
		this.file = new File(plugin.getDataFolder(), "bank.yml");
	}

	public BankConfig config() {
		return config;
	}

	public boolean enabled() {
		return config.enabled();
	}

	public boolean payShop() {
		return config.payShop();
	}

	public void load() {
		loadCredit();
		rebuildValues();
	}

	public void reload() {
		plugin.reloadConfig();
		config = BankConfig.load(plugin.getConfig());
		shop.reload();
	}

	private void loadCredit() {
		credit.clear();
		names.clear();
		if (!file.isFile()) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection players = yaml.getConfigurationSection("players");
		if (players == null) {
			return;
		}
		for (String key : players.getKeys(false)) {
			UUID id;
			try {
				id = UUID.fromString(key);
			} catch (IllegalArgumentException exception) {
				continue;
			}
			credit.put(id, Math.max(0L, players.getLong(key + ".credit", 0L)));
			String name = players.getString(key + ".name");
			if (name != null && !name.isBlank()) {
				names.put(id, name);
			}
		}
		plugin.getLogger().info("建材银行数据已加载：" + credit.size() + " 名玩家");
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		credit.forEach((id, points) -> {
			String base = ROOT + id;
			yaml.set(base + ".credit", points);
			String name = names.get(id);
			if (name != null && !name.isBlank()) {
				yaml.set(base + ".name", name);
			}
		});
		File parent = file.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			plugin.getLogger().warning("无法创建建材银行数据目录：" + parent);
			return;
		}
		File temp = new File(parent, file.getName() + ".tmp");
		try {
			yaml.save(temp);
			try {
				Files.move(
					temp.toPath(),
					file.toPath(),
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE
				);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException exception) {
			plugin.getLogger().log(Level.SEVERE, "保存建材银行数据失败", exception);
		}
	}

	/**
	 * 用商店买价做上限、用原版合成/烧炼配方做下限，迭代推导每种建材的估值。
	 */
	public void rebuildValues() {
		GoodsConfig goods = shop.goods();
		Map<String, Long> base = new LinkedHashMap<>();
		for (ShopItem item : goods.items()) {
			long points = item.buyPrice().movePointRight(decimalPlaces).setScale(0, RoundingMode.DOWN)
				.longValueExact();
			if (points <= 0) {
				continue;
			}
			base.merge(item.material().name(), points, Math::min);
		}
		values = BankValueTable.solve(base, collectRecipes(base));
		if (plugin.getLogger().isLoggable(Level.FINE)) {
			plugin.getLogger().fine("建材银行估值参与配方：" + values.size() + " 项");
		}
		acceptedMaterials.clear();
		for (ShopItem item : goods.items()) {
			if (config.includes(item.category()) && valueOf(item) > 0) {
				acceptedMaterials.add(item.material());
			}
		}
		plugin.getLogger().info("建材银行估值完成：" + acceptedMaterials.size() + " 种建材，"
			+ values.size() + " 种商品参与换算（示例：石头 " + shop.format(money(valueOf("stone")))
			+ "，石砖台阶 " + shop.format(money(valueOf("stone_bricks_slab"))) + "）");
	}

	public long valueOf(ShopItem item) {
		return item == null ? 0L : values.getOrDefault(item.material().name(), 0L);
	}

	private long valueOf(String itemId) {
		return shop.goods().find(itemId).filter(this::accepts).map(this::valueOf).orElse(0L);
	}

	public boolean accepts(ShopItem item) {
		return item != null && enabled() && acceptedMaterials.contains(item.material());
	}

	public Optional<ShopItem> find(String idOrAlias) {
		return shop.goods().find(idOrAlias).filter(this::accepts);
	}

	public Optional<ShopItem> match(ItemStack stack) {
		if (stack == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(shop.goods().match(stack, false)).filter(this::accepts);
	}

	public List<ShopCategory> categories() {
		List<ShopCategory> result = new ArrayList<>();
		for (ShopCategory category : shop.goods().categories()) {
			if (config.includes(category.id())) {
				result.add(category);
			}
		}
		return result;
	}

	public List<ShopItem> itemsForCategory(String categoryId) {
		List<ShopItem> result = new ArrayList<>();
		for (ShopItem item : shop.goods().itemsForCategory(categoryId)) {
			if (accepts(item)) {
				result.add(item);
			}
		}
		return result;
	}

	public List<ShopItem> itemsForAll() {
		List<ShopItem> result = new ArrayList<>();
		for (ShopItem item : shop.goods().items()) {
			if (accepts(item)) {
				result.add(item);
			}
		}
		return result;
	}

	public long creditOf(UUID id) {
		return credit.getOrDefault(id, 0L);
	}

	public BigDecimal creditMoney(Player player) {
		return money(creditOf(player.getUniqueId()));
	}

	public BigDecimal money(long points) {
		return BigDecimal.valueOf(points).movePointLeft(decimalPlaces);
	}

	public String format(long points) {
		return shop.format(money(points));
	}

	private void addCredit(Player player, long points) {
		if (points <= 0) {
			return;
		}
		names.put(player.getUniqueId(), player.getName());
		credit.merge(player.getUniqueId(), points, Long::sum);
	}

	private void setCredit(UUID id, long points) {
		credit.put(id, Math.max(0L, points));
	}

	/**
	 * 商店结账时优先用银行额度抵扣，返回实际抵扣的金额（0 表示没有抵扣）。
	 */
	public BigDecimal debitForShop(Player player, ShopItem item, BigDecimal charge) {
		if (!enabled() || !config.payShop() || !accepts(item) || charge == null
			|| charge.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		long required = charge.movePointRight(decimalPlaces).setScale(0, RoundingMode.DOWN)
			.longValueExact();
		if (required <= 0) {
			return BigDecimal.ZERO;
		}
		long available = creditOf(player.getUniqueId());
		long used = Math.min(available, required);
		if (used <= 0) {
			return BigDecimal.ZERO;
		}
		names.put(player.getUniqueId(), player.getName());
		setCredit(player.getUniqueId(), available - used);
		save();
		return money(used);
	}

	/**
	 * 取消交易时把已经抵扣的额度退回。
	 */
	public void refund(Player player, BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			return;
		}
		long points = amount.movePointRight(decimalPlaces).setScale(0, RoundingMode.DOWN)
			.longValueExact();
		if (points <= 0) {
			return;
		}
		addCredit(player, points);
		save();
	}

	/**
	 * 把背包里符合条件的同类物品全部存入（最多 maxPerOperation 个）。
	 */
	public int deposit(Player player, ShopItem item, int requested) {
		if (!enabled()) {
			player.sendMessage("建材银行未开启。");
			return 0;
		}
		if (!accepts(item)) {
			player.sendMessage("该物品不能存入建材银行。");
			return 0;
		}
		long unit = valueOf(item);
		if (unit <= 0) {
			player.sendMessage("该物品没有可用估值，无法存入。");
			return 0;
		}
		int limit = requested <= 0 ? Integer.MAX_VALUE : requested;
		limit = Math.min(limit, config.maxPerOperation());
		int collected = 0;
		ItemStack[] contents = player.getInventory().getStorageContents();
		for (int slot = 0; slot < contents.length && collected < limit; slot++) {
			ItemStack stack = contents[slot];
			if (!BankItems.isPlain(item, stack)) {
				continue;
			}
			int take = Math.min(stack.getAmount(), limit - collected);
			if (take >= stack.getAmount()) {
				player.getInventory().setItem(slot, null);
			} else {
				ItemStack left = stack.clone();
				left.setAmount(stack.getAmount() - take);
				player.getInventory().setItem(slot, left);
			}
			collected += take;
		}
		if (collected == 0) {
			player.sendMessage("背包里没有可存入的 " + item.id() + "。");
			return 0;
		}
		long gain = gain(unit, collected);
		if (gain <= 0) {
			player.sendMessage("本次存入的估值不足 1 点，已取消。");
			return 0;
		}
		addCredit(player, gain);
		save();
		player.sendMessage("已存入 " + collected + " 个 " + item.id() + "，获得额度 "
			+ format(gain) + "，当前额度 " + format(creditOf(player.getUniqueId())) + "。");
		return collected;
	}

	/**
	 * 把主手物品存入银行；requested 为 0 表示整组。
	 */
	public int depositHand(Player player, int requested) {
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir()) {
			player.sendMessage("请把要存入的建材拿在主手。");
			return 0;
		}
		Optional<ShopItem> item = match(hand);
		if (item.isEmpty()) {
			player.sendMessage("主手物品不在建材银行的收取范围内。");
			return 0;
		}
		return deposit(player, item.get(), requested <= 0 ? hand.getAmount() : requested);
	}

	/**
	 * 用额度取出指定建材。取出前先扣额度，背包放不下的部分立即退回额度。
	 */
	public int withdraw(Player player, ShopItem item, int requested) {
		if (!enabled()) {
			player.sendMessage("建材银行未开启。");
			return 0;
		}
		if (!accepts(item)) {
			player.sendMessage("该物品不在建材银行的兑换范围内。");
			return 0;
		}
		long unit = valueOf(item);
		if (unit <= 0) {
			player.sendMessage("该物品没有可用估值，无法兑换。");
			return 0;
		}
		int limit = Math.max(1, Math.min(requested, config.maxPerOperation()));
		long available = creditOf(player.getUniqueId());
		int affordable = (int) Math.min(limit, available / unit);
		if (affordable <= 0) {
			player.sendMessage("额度不足，兑换 1 个 " + item.id() + " 需要 " + format(unit)
				+ "，当前额度 " + format(available) + "。");
			return 0;
		}
		List<ItemStack> stacks = split(item, affordable);
		if (!InventoryUtil.canFit(player, stacks)) {
			player.sendMessage("背包空间不足，请先清理背包。");
			return 0;
		}
		long cost = unit * affordable;
		setCredit(player.getUniqueId(), available - cost);
		Map<Integer, ItemStack> leftovers = player.getInventory()
			.addItem(stacks.toArray(ItemStack[]::new));
		int leftoverCount = InventoryUtil.count(leftovers);
		if (leftoverCount > 0) {
			long back = unit * leftoverCount;
			addCredit(player, back);
			player.sendMessage("背包放不下，已退回额度 " + format(back) + "。");
		}
		int given = affordable - leftoverCount;
		save();
		if (given > 0) {
			names.put(player.getUniqueId(), player.getName());
			player.sendMessage("已取出 " + given + " 个 " + item.id() + "，消耗额度 "
				+ format(cost - unit * leftoverCount) + "，当前额度 "
				+ format(creditOf(player.getUniqueId())) + "。");
		}
		return given;
	}

	private long gain(long unit, int amount) {
		BigDecimal raw = BigDecimal.valueOf(unit)
			.multiply(BigDecimal.valueOf(amount))
			.multiply(BigDecimal.valueOf(config.depositRate()))
			.setScale(0, RoundingMode.FLOOR);
		return raw.longValueExact();
	}

	private List<ItemStack> split(ShopItem item, int amount) {
		List<ItemStack> stacks = new ArrayList<>();
		int remaining = amount;
		int maxStack = Math.max(1, item.material().getMaxStackSize());
		while (remaining > 0) {
			int size = Math.min(remaining, maxStack);
			stacks.add(item.createStack(size));
			remaining -= size;
		}
		return stacks;
	}

	private List<BankValueTable.RecipeSpec> collectRecipes(Map<String, Long> base) {
		List<BankValueTable.RecipeSpec> specs = new ArrayList<>();
		Iterator<Recipe> iterator = Bukkit.recipeIterator();
		while (iterator.hasNext()) {
			Recipe recipe;
			try {
				recipe = iterator.next();
			} catch (RuntimeException exception) {
				continue;
			}
			try {
				if (recipe instanceof ShapedRecipe shaped) {
					addShaped(specs, shaped, base);
				} else if (recipe instanceof ShapelessRecipe shapeless) {
					addShapeless(specs, shapeless, base);
				} else if (recipe instanceof CookingRecipe<?> cooking) {
					addCooking(specs, cooking, base);
				}
			} catch (RuntimeException ignored) {
				// 自定义配方数据异常时跳过，不影响其他配方
			}
		}
		plugin.getLogger().info("建材银行配方统计：" + specs.size() + " 条（合成 + 烧炼）");
		return specs;
	}

	private void addShaped(
		List<BankValueTable.RecipeSpec> specs,
		ShapedRecipe recipe,
		Map<String, Long> base
	) {
		ItemStack result = recipe.getResult();
		String resultKey = result.getType().name();
		if (!base.containsKey(resultKey)) {
			return;
		}
		Map<Character, RecipeChoice> choices = recipe.getChoiceMap();
		Map<String, Integer> ingredients = new LinkedHashMap<>();
		for (String row : recipe.getShape()) {
			for (int index = 0; index < row.length(); index++) {
				char symbol = row.charAt(index);
				if (symbol == ' ') {
					continue;
				}
				RecipeChoice choice = choices.get(symbol);
				if (isEmpty(choice)) {
					// 3×3 配方会用 a~i 占位，空槽必须跳过而不是整条配方作废
					continue;
				}
				String key = cheapest(choice, base);
				if (key == null) {
					return;
				}
				ingredients.merge(key, 1, Integer::sum);
			}
		}
		if (!ingredients.isEmpty()) {
			specs.add(new BankValueTable.RecipeSpec(
				resultKey,
				Math.max(1, result.getAmount()),
				ingredients
			));
		}
	}

	private void addShapeless(
		List<BankValueTable.RecipeSpec> specs,
		ShapelessRecipe recipe,
		Map<String, Long> base
	) {
		ItemStack result = recipe.getResult();
		String resultKey = result.getType().name();
		if (!base.containsKey(resultKey)) {
			return;
		}
		Map<String, Integer> ingredients = new LinkedHashMap<>();
		for (RecipeChoice choice : recipe.getChoiceList()) {
			if (isEmpty(choice)) {
				continue;
			}
			String key = cheapest(choice, base);
			if (key == null) {
				return;
			}
			ingredients.merge(key, 1, Integer::sum);
		}
		if (!ingredients.isEmpty()) {
			specs.add(new BankValueTable.RecipeSpec(
				resultKey,
				Math.max(1, result.getAmount()),
				ingredients
			));
		}
	}

	private void addCooking(
		List<BankValueTable.RecipeSpec> specs,
		CookingRecipe<?> recipe,
		Map<String, Long> base
	) {
		ItemStack result = recipe.getResult();
		String resultKey = result.getType().name();
		if (!base.containsKey(resultKey)) {
			return;
		}
		String input = cheapest(recipe.getInputChoice(), base);
		if (input == null) {
			return;
		}
		specs.add(new BankValueTable.RecipeSpec(
			resultKey,
			Math.max(1, result.getAmount()),
			Map.of(input, 1)
		));
	}

	private String cheapest(RecipeChoice choice, Map<String, Long> base) {
		if (choice == null) {
			return null;
		}
		if (choice instanceof RecipeChoice.MaterialChoice materials) {
			String best = null;
			long bestValue = Long.MAX_VALUE;
			for (Material material : materials.getChoices()) {
				if (material.isAir()) {
					continue;
				}
				String key = material.name();
				Long value = base.get(key);
				if (value == null) {
					continue;
				}
				if (value < bestValue) {
					bestValue = value;
					best = key;
				}
			}
			return best;
		}
		try {
			ItemStack stack = choice.getItemStack();
			if (stack != null && !stack.getType().isAir()) {
				String key = stack.getType().name();
				return base.containsKey(key) ? key : null;
			}
		} catch (RuntimeException ignored) {
			// 部分自定义 RecipeChoice 不支持取物品，直接跳过
		}
		return null;
	}

	private boolean isEmpty(RecipeChoice choice) {
		if (choice == null) {
			return true;
		}
		if (choice instanceof RecipeChoice.MaterialChoice materials) {
			for (Material material : materials.getChoices()) {
				if (!material.isAir()) {
					return false;
				}
			}
			return true;
		}
		try {
			ItemStack stack = choice.getItemStack();
			return stack == null || stack.getType().isAir();
		} catch (RuntimeException exception) {
			return false;
		}
	}

	public String describe(ShopItem item) {
		return item.id() + " @ " + format(valueOf(item)) + "（商店买价 "
			+ shop.format(item.buyPrice()) + "）";
	}

	public Set<String> acceptedCategoryIds() {
		return Set.copyOf(config.categories().stream().map(id -> id.toLowerCase(Locale.ROOT)).toList());
	}
}
