package com.xiyuan.plugmc.servermenu;

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
		"balance", "toggle_hud", "message", "player_command", "console_command", "plugin_list"
	);
	private static final int PLUGIN_PAGE_CAPACITY = 45;
	private static final int PREVIOUS_PAGE_SLOT = 45;
	private static final int MAIN_PAGE_SLOT = 49;
	private static final int NEXT_PAGE_SLOT = 53;

	private final ServerMenuPlugin plugin;
	private final EconomyService economy;
	private Map<Integer, MenuButton> buttons = Map.of();
	private int size;
	private String title;

	public MenuManager(ServerMenuPlugin plugin, EconomyService economy) {
		this.plugin = plugin;
		this.economy = economy;
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

	public void open(Player player) {
		MenuHolder holder = new MenuHolder(size, title, buttons, MenuHolder.Page.MAIN, 0);
		for (Map.Entry<Integer, MenuButton> entry : buttons.entrySet()) {
			holder.setButtonItem(entry.getKey(), createItem(player, entry.getValue()));
		}
		player.openInventory(holder.getInventory());
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
		if (holder.getPage() == MenuHolder.Page.PLUGINS) {
			switch (slot) {
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
			case "plugin_list" -> openPluginList(player, 0);
			case "player_command" -> player.performCommand(stripSlash(value));
			case "console_command" -> plugin.getServer().dispatchCommand(
				plugin.getServer().getConsoleSender(),
				stripSlash(value)
			);
			default -> throw new IllegalStateException("未处理的菜单动作：" + button.action());
		}
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
		return text
			.replace("{player}", player.getName())
			.replace("{balance}", economy.format(economy.getBalance(player.getUniqueId())))
			.replace("{hud_status}", status);
	}

	private String stripSlash(String command) {
		return command.startsWith("/") ? command.substring(1) : command;
	}
}
