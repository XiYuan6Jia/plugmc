package com.xiyuan.plugmc.servermenu;

import com.xiyuan.plugmc.economy.api.EconomyAuditService;
import com.xiyuan.plugmc.economy.api.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MenuManager {
	private static final Set<String> ACTIONS = Set.of(
		"balance", "toggle_hud", "message", "player_command", "console_command", "plugin_list",
		"economy_admin", "hub_panel", "prompt_command", "daily", "none", "close"
	);
	private static final int PLUGIN_PAGE_CAPACITY = 45;
	private static final int CONFIG_VERSION = 14;
	private static final int PREVIOUS_PAGE_SLOT = 45;
	private static final int MAIN_PAGE_SLOT = 49;
	private static final int NEXT_PAGE_SLOT = 53;

	private final ServerMenuPlugin plugin;
	private final EconomyService economy;
	private final ChatPrompt chatPrompt;
	private Map<Integer, MenuButton> buttons = Map.of();
	private ItemStack filler;
	private int size;
	private String title;

	public MenuManager(ServerMenuPlugin plugin, EconomyService economy, ChatPrompt chatPrompt) {
		this.plugin = plugin;
		this.economy = economy;
		this.chatPrompt = chatPrompt;
	}

	public void reload() {
		plugin.reloadConfig();
		mergeDefaultConfig();
		plugin.reloadConfig();
		int rows = plugin.getConfig().getInt("menu.rows", 3);
		if (rows < 1 || rows > 6) {
			throw new IllegalStateException("menu.rows 必须在 1 到 6 之间");
		}
		int loadedSize = rows * 9;
		String loadedTitle = plugin.getConfig().getString("menu.title", "&8服务器菜单");
		ConfigurationSection configuredButtons = plugin.getConfig().getConfigurationSection("menu.items");
		if (configuredButtons == null) {
			throw new IllegalStateException("config.yml 缺少 menu.items 配置");
		}

		Map<Integer, MenuButton> loadedButtons = new LinkedHashMap<>();
		for (String key : configuredButtons.getKeys(false)) {
			int slot;
			try {
				slot = Integer.parseInt(key);
			} catch (NumberFormatException exception) {
				throw new IllegalStateException("菜单按钮位置必须是数字：" + key, exception);
			}
			if (slot < 0 || slot >= loadedSize) {
				throw new IllegalStateException("菜单按钮位置超出范围：" + key);
			}

			ConfigurationSection section = configuredButtons.getConfigurationSection(key);
			if (section == null) {
				throw new IllegalStateException("菜单按钮配置必须是对象：" + key);
			}
			String materialName = section.getString("material", "");
			Material material = Material.matchMaterial(materialName);
			if (material == null || material.isAir()) {
				throw new IllegalStateException("菜单按钮材质无效，位置：" + key + "，材质：" + materialName);
			}
			String action = section.getString("action", "").toLowerCase(Locale.ROOT);
			if (!ACTIONS.contains(action)) {
				throw new IllegalStateException("菜单按钮动作无效，位置 " + key + "：" + action);
			}
			loadedButtons.put(slot, new MenuButton(
				material,
				section.getString("name", "&f菜单按钮"),
				section.getStringList("lore"),
				action,
				section.getString("value", ""),
				section.getString("permission", "")
			));
		}
		size = loadedSize;
		title = loadedTitle;
		buttons = Map.copyOf(loadedButtons);
		filler = loadFiller();
	}

	private ItemStack loadFiller() {
		String materialName = plugin.getConfig().getString("menu.filler.material", "");
		if (materialName.isBlank()) {
			return null;
		}
		Material material = Material.matchMaterial(materialName);
		if (material == null || material.isAir()) {
			throw new IllegalStateException("menu.filler.material 无效：" + materialName);
		}
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component(plugin.getConfig().getString("menu.filler.name", " ")));
			item.setItemMeta(meta);
		}
		return item;
	}

	private void mergeDefaultConfig() {
		YamlConfiguration defaults = new YamlConfiguration();
		InputStream resource = plugin.getResource("config.yml");
		if (resource == null) {
			throw new IllegalStateException("插件 JAR 缺少默认 config.yml");
		}
		try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
			defaults.load(reader);
		} catch (IOException | InvalidConfigurationException exception) {
			throw new IllegalStateException("无法读取 JAR 内的默认 config.yml", exception);
		}

		ensurePluginListButton(defaults);
		migrateConfigVersion(defaults);
		plugin.saveConfig();
	}

	private void ensurePluginListButton(YamlConfiguration defaults) {
		ConfigurationSection configuredButtons = plugin.getConfig().getConfigurationSection("menu.items");
		if (configuredButtons == null) {
			throw new IllegalStateException("config.yml 缺少 menu.items 配置");
		}
		boolean hasPluginList = configuredButtons.getKeys(false).stream()
			.anyMatch(key -> "plugin_list".equalsIgnoreCase(
				plugin.getConfig().getString("menu.items." + key + ".action", "")
			));
		if (hasPluginList) {
			return;
		}

		ConfigurationSection defaultButtons = defaults.getConfigurationSection("menu.items");
		if (defaultButtons == null) {
			throw new IllegalStateException("JAR 内的默认 config.yml 缺少 menu.items 配置");
		}
		Map.Entry<String, ConfigurationSection> defaultButtonEntry = defaultButtons.getKeys(false).stream()
			.map(key -> Map.entry(key, defaultButtons.getConfigurationSection(key)))
			.filter(entry -> entry.getValue() != null
				&& "plugin_list".equalsIgnoreCase(entry.getValue().getString("action", "")))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("JAR 默认配置缺少 plugin_list 按钮"));
		ConfigurationSection pluginListButton = defaultButtonEntry.getValue();

		int rows = plugin.getConfig().getInt("menu.rows", 3);
		int slot = -1;
		int defaultSlot = Integer.parseInt(defaultButtonEntry.getKey());
		if (!configuredButtons.getKeys(false).contains(Integer.toString(defaultSlot)) && defaultSlot < rows * 9) {
			slot = defaultSlot;
		}
		for (int candidate = 0; candidate < rows * 9; candidate++) {
			if (slot >= 0) {
				break;
			}
			if (configuredButtons.getConfigurationSection(Integer.toString(candidate)) == null) {
				slot = candidate;
				break;
			}
		}
		if (slot < 0 && rows < 6) {
			slot = rows * 9;
			plugin.getConfig().set("menu.rows", rows + 1);
		}
		if (slot < 0) {
			throw new IllegalStateException("菜单已满，无法添加插件列表按钮；请在 menu.items 中留出一个空位置");
		}

		plugin.getConfig().set("menu.items." + slot, pluginListButton.getValues(false));
		plugin.getLogger().info("已将默认插件列表按钮合并到菜单位置 " + slot);
	}

	/**
	 * 旧版本配置里“即将开放”的占位按钮不会随插件更新自动生效，
	 * 这里直接把 v1 配置升级为当前默认布局（升级前备份原文件）。
	 */
	private void migrateConfigVersion(YamlConfiguration defaults) {
		int currentVersion = plugin.getConfig().getInt("config-version", 1);
		if (currentVersion >= CONFIG_VERSION) {
			return;
		}

		backupConfig(currentVersion);
		plugin.getConfig().set("menu.rows", defaults.getInt("menu.rows", 5));
		plugin.getConfig().set("menu.items", null);
		ConfigurationSection defaultButtons = defaults.getConfigurationSection("menu.items");
		if (defaultButtons != null) {
			for (String key : defaultButtons.getKeys(false)) {
				plugin.getConfig().set("menu.items." + key, defaultButtons.get(key));
			}
		}
		plugin.getConfig().set("config-version", CONFIG_VERSION);
		plugin.saveConfig();
		plugin.getLogger().info("菜单配置已从 v" + currentVersion + " 升级到 v" + CONFIG_VERSION
			+ "，原配置已备份");
	}

	private void backupConfig(int version) {
		java.io.File source = new java.io.File(plugin.getDataFolder(), "config.yml");
		if (!source.isFile()) {
			return;
		}
		java.io.File backup = new java.io.File(plugin.getDataFolder(), "config-v" + version + ".backup.yml");
		try {
			java.nio.file.Files.copy(
				source.toPath(),
				backup.toPath(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING
			);
		} catch (java.io.IOException exception) {
			plugin.getLogger().warning("备份菜单配置失败：" + exception.getMessage());
		}
	}

	public void open(Player player) {
		MenuHolder holder = new MenuHolder(size, title, buttons, MenuHolder.Page.MAIN, 0);
		if (filler != null) {
			for (int slot = 0; slot < size; slot++) {
				holder.setButtonItem(slot, filler.clone());
			}
		}
		for (Map.Entry<Integer, MenuButton> entry : buttons.entrySet()) {
			holder.setButtonItem(entry.getKey(), createItem(player, entry.getValue()));
		}
		player.openInventory(holder.getInventory());
	}

	public void openAdminPanel(Player player) {
		MenuHolder holder = new MenuHolder(54, "&8经济管理", Map.of(), MenuHolder.Page.ADMIN, 0);
		if (filler != null) {
			for (int slot = 0; slot < 54; slot++) {
				holder.setButtonItem(slot, filler.clone());
			}
		}
		EconomyAuditService audit = plugin.auditService();
		if (audit == null) {
			holder.setButtonItem(22, navigationItem(Material.BARRIER, "&c经济审计不可用（economy 未加载）"));
			holder.setButtonItem(45, navigationItem(Material.ARROW, "&e返回菜单"));
			holder.setButtonItem(49, navigationItem(Material.BARRIER, "&c关闭"));
			player.openInventory(holder.getInventory());
			return;
		}

		List<String> stats = audit.statsLines();
		holder.setButtonItem(4, adminItem(Material.PAPER, "&f经济统计", stats));

		List<EconomyAuditService.Check> doctor = audit.doctorChecks();
		boolean doctorOk = doctor.stream().allMatch(EconomyAuditService.Check::passed);
		holder.setButtonItem(20, adminItem(
			doctorOk ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
			doctorOk ? "&a账本自检通过" : "&c账本自检异常",
			doctor.stream().map(this::formatCheck).toList()
		));

		List<String> reminders = audit.reminders();
		holder.setButtonItem(24, adminItem(
			reminders.isEmpty() ? Material.CLOCK : Material.BELL,
			reminders.isEmpty() ? "&a无待处理事项" : "&e待处理事项",
			reminders.isEmpty() ? List.of("&7没有到期未结算的挂单") : reminders
		));

		List<EconomyAuditService.Check> selfTest = audit.selfTestChecks();
		long failed = selfTest.stream().filter(check -> !check.passed()).count();
		holder.setButtonItem(31, adminItem(
			failed == 0 ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK,
			failed == 0 ? "&a演练全部通过" : "&c演练存在失败项",
			List.of("&7共 " + selfTest.size() + " 项，失败 " + failed + " 项")
		));

		holder.setButtonItem(11, adminItem(Material.GOLD_BLOCK, "&6财政账户", List.of(
			"&7当前余额：&e" + economy.format(audit.treasuryBalance()),
			"&8税收与上架费的主要去向"
		)));
		holder.setButtonItem(13, adminItem(Material.DIAMOND, "&a财政注入", List.of(
			"&7向财政注入活动预算",
			"&e点击后输入金额"
		)));
		holder.setButtonItem(15, adminItem(Material.EMERALD, "&a财政发放", List.of(
			"&7从财政向玩家发放奖励",
			"&e点击后依次输入玩家名与金额"
		)));
		holder.setButtonItem(29, adminItem(Material.ICE, "&b冻结玩家", List.of(
			"&7冻结后该玩家无法支出",
			"&e点击后输入玩家名"
		)));
		holder.setButtonItem(33, adminItem(Material.PACKED_ICE, "&b解冻玩家", List.of(
			"&7恢复该玩家的支出",
			"&e点击后输入玩家名"
		)));
		holder.setButtonItem(37, adminItem(Material.WRITABLE_BOOK, "&e查询流水", List.of(
			"&7查看玩家最近账本流水",
			"&e点击后输入玩家名"
		)));

		holder.setButtonItem(45, navigationItem(Material.ARROW, "&e返回菜单"));
		holder.setButtonItem(49, navigationItem(Material.BARRIER, "&c关闭"));
		holder.setButtonItem(53, navigationItem(Material.COMPARATOR, "&b重新检测"));
		player.openInventory(holder.getInventory());
	}

	private void promptTreasuryFund(Player player) {
		chatPrompt.request(player, "&e请输入要注入财政的金额：", input -> {
			if (!player.isOnline()) {
				return;
			}
			player.performCommand("eco treasury fund " + input + " menu");
			openAdminPanel(player);
		});
	}

	private void promptTreasuryPay(Player player) {
		chatPrompt.request(player, "&e请输入要发放奖励的玩家名：", name -> {
			if (!player.isOnline()) {
				return;
			}
			chatPrompt.request(player, "&e请输入发放金额：", amount -> {
				if (!player.isOnline()) {
					return;
				}
				player.performCommand("eco treasury pay " + name + " " + amount + " menu");
				openAdminPanel(player);
			});
		});
	}

	private void promptFreeze(Player player, boolean frozen) {
		chatPrompt.request(player, "&e请输入要" + (frozen ? "冻结" : "解冻") + "的玩家名：", name -> {
			if (!player.isOnline()) {
				return;
			}
			player.performCommand("eco " + (frozen ? "freeze " : "unfreeze ") + name);
			openAdminPanel(player);
		});
	}

	private void promptAudit(Player player) {
		chatPrompt.request(player, "&e请输入要查询流水的玩家名：", name -> {
			if (!player.isOnline()) {
				return;
			}
			player.performCommand("eco audit " + name);
			openAdminPanel(player);
		});
	}

	private String formatCheck(EconomyAuditService.Check check) {
		return (check.passed() ? "&a✔ " : "&c✘ ") + check.name() + " &8" + check.detail();
	}

	private ItemStack adminItem(Material material, String name, List<String> lore) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component(name));
			meta.lore(lore.stream().map(MenuText::component).toList());
			item.setItemMeta(meta);
		}
		return item;
	}

	public void openPluginList(Player player, int pageNumber) {
		List<Plugin> plugins = java.util.Arrays.stream(Bukkit.getPluginManager().getPlugins())
			.filter(Plugin::isEnabled)
			.sorted((first, second) -> first.getName().compareToIgnoreCase(second.getName()))
			.toList();
		int pageCount = Math.max(1, (plugins.size() + PLUGIN_PAGE_CAPACITY - 1) / PLUGIN_PAGE_CAPACITY);
		int page = Math.max(0, Math.min(pageNumber, pageCount - 1));
		MenuHolder holder = new MenuHolder(
			54,
			"&8已启用插件 &7(" + (page + 1) + "/" + pageCount + ")",
			Map.of(),
			MenuHolder.Page.PLUGINS,
			page
		);
		if (filler != null) {
			for (int slot = 0; slot < 54; slot++) {
				holder.setButtonItem(slot, filler.clone());
			}
		}
		int start = page * PLUGIN_PAGE_CAPACITY;
		int end = Math.min(start + PLUGIN_PAGE_CAPACITY, plugins.size());
		for (int index = start; index < end; index++) {
			holder.setButtonItem(index - start, createPluginItem(plugins.get(index)));
		}
		if (page > 0) {
			holder.setButtonItem(PREVIOUS_PAGE_SLOT, navigationItem(Material.ARROW, "&e上一页"));
		}
		holder.setButtonItem(MAIN_PAGE_SLOT, navigationItem(Material.BARRIER, "&c返回菜单"));
		if (page + 1 < pageCount) {
			holder.setButtonItem(NEXT_PAGE_SLOT, navigationItem(Material.ARROW, "&e下一页"));
		}
		player.openInventory(holder.getInventory());
	}

	public void handleClick(Player player, MenuHolder holder, int slot) {
		if (holder.getPage() == MenuHolder.Page.HUB) {
			switch (slot) {
				case 20 -> runHubCommand(player, "hub");
				case 22 -> runHubCommand(player, "hub back");
				case 24 -> runHubCommand(player, "hub info");
				case 30 -> runHubCommand(player, "hub parkour");
				case 29 -> runHubCommand(player, "hub setspawn");
				case 31 -> runHubCommand(player, "hub build");
				case 33 -> runHubCommand(player, "hub check");
				case 45 -> open(player);
				case 49 -> player.closeInventory();
				default -> {
				}
			}
			return;
		}
		if (holder.getPage() == MenuHolder.Page.ADMIN) {
			switch (slot) {
				case 13 -> promptTreasuryFund(player);
				case 15 -> promptTreasuryPay(player);
				case 29 -> promptFreeze(player, true);
				case 33 -> promptFreeze(player, false);
				case 37 -> promptAudit(player);
				case 45 -> open(player);
				case 49 -> player.closeInventory();
				case 53 -> openAdminPanel(player);
				default -> {
				}
			}
			return;
		}
		if (holder.getPage() == MenuHolder.Page.PLUGINS) {			switch (slot) {
				case PREVIOUS_PAGE_SLOT -> openPluginList(player, holder.getPageNumber() - 1);
				case MAIN_PAGE_SLOT -> open(player);
				case NEXT_PAGE_SLOT -> openPluginList(player, holder.getPageNumber() + 1);
				default -> {
				}
			}
			return;
		}
		MenuButton button = holder.getButton(slot);
		if (button != null) {
			execute(player, button);
		}
	}

	public void execute(Player player, MenuButton button) {
		if (!button.permission().isBlank() && !player.hasPermission(button.permission())) {
			player.sendMessage(MenuText.component("&c你没有权限使用此功能。"));
			return;
		}

		String value = replacePlaceholders(player, button.value());
		switch (button.action()) {
			case "balance" -> {
				player.sendMessage(MenuText.component("&6你的余额：&e"
					+ economy.format(economy.getBalance(player.getUniqueId()))));
				open(player);
			}
			case "toggle_hud" -> {
				plugin.toggleHud(player);
				player.sendMessage(MenuText.component("&a金币 HUD 已"
					+ (plugin.isHudEnabled(player) ? "开启。" : "关闭。")));
				open(player);
			}
			case "message" -> player.sendMessage(MenuText.component(value));
			case "none" -> {
			}
			case "close" -> player.closeInventory();
			case "plugin_list" -> openPluginList(player, 0);
			case "economy_admin" -> openAdminPanel(player);
			case "hub_panel" -> openHubPanel(player);
			case "daily" -> {
				player.performCommand("daily");
				open(player);
			}
			case "prompt_command" -> {
				if (value.isBlank()) {
					player.sendMessage(MenuText.component("&c该按钮缺少命令模板。"));
					return;
				}
				chatPrompt.request(player, "&e请输入：", input -> {
					if (!player.isOnline()) {
						return;
					}
					if (input.isBlank()) {
						player.sendMessage(MenuText.component("&c输入不能为空。"));
						open(player);
						return;
					}
					if (value.contains("{input}")) {
						player.performCommand(stripSlash(value.replace("{input}", input)));
					} else {
						player.performCommand(stripSlash(value) + " " + input);
					}
					open(player);
				});
			}
			case "player_command" -> player.performCommand(stripSlash(value));
			case "console_command" -> plugin.getServer().dispatchCommand(
				plugin.getServer().getConsoleSender(),
				stripSlash(value)
			);
			default -> throw new IllegalStateException("未处理的菜单动作：" + button.action());
		}
	}

	private void runHubCommand(Player player, String command) {
		player.closeInventory();
		player.performCommand(command);
	}

	/**
	 * 大厅子页：进入/返回/信息 + 管理员工具（设置出生点、重建、自检）。
	 */
	public void openHubPanel(Player player) {
		MenuHolder holder = new MenuHolder(54, "&8主城大厅", Map.of(), MenuHolder.Page.HUB, 0);
		if (filler != null) {
			for (int slot = 0; slot < 54; slot++) {
				holder.setButtonItem(slot, filler.clone());
			}
		}
		boolean installed = plugin.getServer().getPluginManager().isPluginEnabled("hub");
		if (!installed) {
			holder.setButtonItem(22, navigationItem(Material.BARRIER, "&c主城插件未加载"));
			holder.setButtonItem(45, navigationItem(Material.ARROW, "&e返回菜单"));
			holder.setButtonItem(49, navigationItem(Material.BARRIER, "&c关闭"));
			player.openInventory(holder.getInventory());
			return;
		}

		holder.setButtonItem(4, adminItem(Material.NETHER_STAR, "&6主城大厅", List.of(
			"&7独立虚空维度，安全无怪",
			"&7进入后随时可以回到传送前的位置",
			"&8/hub 进入 · /hub back 返回"
		)));
		holder.setButtonItem(20, adminItem(Material.LODESTONE, "&a进入主城大厅", List.of(
			"&7传送到大厅出生点",
			"&8点击进入"
		)));
		holder.setButtonItem(22, adminItem(Material.COMPASS, "&e返回原位置", List.of(
			"&7回到进入主城前的位置",
			"&8位置已保存，重启后仍可用"
		)));
		holder.setButtonItem(24, adminItem(Material.MAP, "&b主城信息", List.of(
			"&7维度名、出生点与在线人数",
			"&8结果显示在聊天栏"
		)));
		holder.setButtonItem(30, adminItem(Material.FEATHER, "&a跑酷挑战", List.of(
			"&7东门外的 20 段跳台",
			"&7踩金色方块记录检查点，踩钻石块冲线",
			"&8点击开始计时"
		)));
		if (player.hasPermission("hub.admin")) {
			holder.setButtonItem(29, adminItem(Material.RESPAWN_ANCHOR, "&e设置出生点", List.of(
				"&7把当前位置设为大厅出生点",
				"&8站在大厅里点击"
			)));
			holder.setButtonItem(31, adminItem(Material.BRICKS, "&e重建大厅", List.of(
				"&7清空并重新生成大厅建筑",
				"&8会删除大厅范围内的方块"
			)));
			holder.setButtonItem(33, adminItem(Material.WRITABLE_BOOK, "&e结构自检", List.of(
				"&7检查喷泉、柱子、屋顶天窗与大门",
				"&8结果显示在聊天栏"
			)));
		} else {
			holder.setButtonItem(31, navigationItem(Material.GRAY_DYE, "&8管理员工具已隐藏"));
		}
		holder.setButtonItem(45, navigationItem(Material.ARROW, "&e返回菜单"));
		holder.setButtonItem(49, navigationItem(Material.BARRIER, "&c关闭"));
		player.openInventory(holder.getInventory());
	}

	private ItemStack createItem(Player player, MenuButton button) {
		ItemStack item = new ItemStack(button.material());
		ItemMeta meta = item.getItemMeta();
		meta.displayName(MenuText.component(replacePlaceholders(player, button.name())));
		List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
		for (String line : button.lore()) {
			lore.add(MenuText.component(replacePlaceholders(player, line)));
		}
		if (!lore.isEmpty()) {
			meta.lore(lore);
		}
		item.setItemMeta(meta);
		return item;
	}

	private ItemStack createPluginItem(Plugin serverPlugin) {
		PluginDescriptionFile description = serverPlugin.getDescription();
		ItemStack item = new ItemStack(Material.PAPER);
		ItemMeta meta = item.getItemMeta();
		meta.displayName(MenuText.component("&b" + description.getName() + " &7v" + description.getVersion()));
		List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
		String summary = description.getDescription();
		lore.add(MenuText.component("&7功能：&f"
			+ (summary == null || summary.isBlank() ? "暂无说明" : summary)));

		Map<String, Map<String, Object>> commands = description.getCommands();
		if (commands == null || commands.isEmpty()) {
			lore.add(MenuText.component("&7用法：&f暂无命令"));
		} else {
			for (Map.Entry<String, Map<String, Object>> entry : commands.entrySet()) {
				String usage = stringValue(entry.getValue().get("usage"), "/" + entry.getKey());
				String commandDescription = stringValue(entry.getValue().get("description"), "");
				lore.add(MenuText.component("&7用法：&e" + usage));
				if (!commandDescription.isBlank()) {
					lore.add(MenuText.component("&8" + commandDescription));
				}
			}
		}
		meta.lore(lore);
		item.setItemMeta(meta);
		return item;
	}

	private ItemStack navigationItem(Material material, String name) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		meta.displayName(MenuText.component(name));
		item.setItemMeta(meta);
		return item;
	}

	private String stringValue(Object value, String fallback) {
		return value instanceof String text && !text.isBlank() ? text : fallback;
	}

	private String replacePlaceholders(Player player, String text) {
		String status = plugin.isHudEnabled(player) ? "&a开启" : "&c关闭";
		EconomyAuditService audit = plugin.auditService();
		String daily = audit == null ? "&8不可用" : audit.dailyStatus(player.getUniqueId());
		String frozen = audit != null && audit.isFrozen(player.getUniqueId()) ? "&c已冻结" : "&a正常";
		String treasury = audit == null ? "&8不可用" : economy.format(audit.treasuryBalance());
		return text
			.replace("{player}", player.getName())
			.replace("{balance}", economy.format(economy.getBalance(player.getUniqueId())))
			.replace("{hud_status}", status)
			.replace("{daily_status}", daily)
			.replace("{frozen_status}", frozen)
			.replace("{treasury}", treasury);
	}

	private String stripSlash(String command) {
		return command.startsWith("/") ? command.substring(1) : command;
	}
}
