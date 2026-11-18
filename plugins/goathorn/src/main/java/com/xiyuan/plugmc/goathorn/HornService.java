package com.xiyuan.plugmc.goathorn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.Particle;

import net.kyori.adventure.text.Component;

/**
 * 绑定/吹奏的业务逻辑，全部在主线程执行。
 *
 * <p>绑定条件（全部满足才成功）：手持山羊角 → 目标是 Tameable 且已驯服 →
 * 主人是玩家本人 → 动物没绑过任何羊角（每只动物只能绑一次）→ 羊角没绑这只动物 →
 * 没超过单角上限。</p>
 */
final class HornService {
	private final GoatHornPlugin plugin;
	private final HornBindings bindings;
	private final Map<UUID, Long> cooldowns = new HashMap<>();

	HornService(GoatHornPlugin plugin, HornBindings bindings) {
		this.plugin = plugin;
		this.bindings = bindings;
	}

	/** 对被 shift 右键的动物尝试绑定。 */
	void bind(Player player, Entity clicked) {
		HornConfig config = plugin.settings();
		if (!config.enabled()) {
			player.sendMessage(HornText.component("&7山羊角宠物哨当前已关闭。"));
			return;
		}
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType() != Material.GOAT_HORN) {
			player.sendMessage(HornText.component("&c你要先手持山羊角。"));
			return;
		}
		if (!(clicked instanceof Tameable animal)) {
			player.sendMessage(HornText.component("&c这只动物不能被驯服，绑不了。"));
			return;
		}
		if (!animal.isTamed()) {
			player.sendMessage(HornText.component("&c要先驯服它，才能绑到羊角上。"));
			return;
		}
		UUID ownerId = player.getUniqueId();
		if (!ownerId.equals(animal.getOwnerUniqueId())) {
			player.sendMessage(HornText.component("&c这不是你驯服的动物。"));
			return;
		}
		PersistentDataContainer animalData = animal.getPersistentDataContainer();
		if (bindings.hornIdOf(animalData) != null) {
			player.sendMessage(HornText.component("&c这只宠物已经被绑定过了。"));
			return;
		}
		if (bindings.containsItem(hand, animal.getUniqueId())) {
			player.sendMessage(HornText.component("&c这只宠物已经在这只羊角上了。"));
			return;
		}
		if (config.maxAnimalsPerHorn() > 0
			&& bindings.animalsOfItem(hand).size() >= config.maxAnimalsPerHorn()) {
			player.sendMessage(HornText.component("&c这只羊角已经绑满了（上限 "
				+ config.maxAnimalsPerHorn() + " 只）。"));
			return;
		}
		// 羊角稳定 key：第一次绑定时生成，之后复用；动物侧的 bound-horn 指向它
		final UUID hornKey;
		UUID existing = bindings.hornKeyOfItem(hand);
		if (existing != null) {
			hornKey = existing;
		} else {
			hornKey = UUID.randomUUID();
		}
		List<UUID> animals = new ArrayList<>(bindings.animalsOfItem(hand));
		animals.add(animal.getUniqueId());
		// 物品侧写必须走 editMeta（26.3 的 ItemStack PDC 是只读视图）。
		// 注意：editMeta 改的是 ItemStack 上那份 meta 的副本，写完要 setItemStack
		// 才会落回玩家背包 —— 不回写的话改动会跟着这次调用一起丢掉。
		boolean edited = hand.editMeta(meta -> {
			bindings.writeHornIdMeta(meta, hornKey);
			bindings.writeAnimalsMeta(meta, animals);
			if (!meta.hasEnchants()) {
				// 附魔特效：addEnchant 不校验 Material 的可附魔性，羊角可以直接挂
				meta.addEnchant(Enchantment.LURE, 1, true);
			}
			meta.lore(buildLore(animals, null));
		});
		if (edited) {
			player.getInventory().setItemInMainHand(hand);
		}
		// 动物侧：bound-horn + bound-owner（实体 PDC 可直接写）
		bindings.setHorn(animalData, hornKey);
		bindings.setOwner(animalData, ownerId);
		String display = describe(animal);
		player.sendMessage(HornText.component("&a已把 " + display + " &a绑到山羊角上。吹奏羊角就能把它召回。"));
	}

	/** 吹奏：把这只羊角绑的所有动物传送到玩家身边（不限距离、不限区块加载）。 */
	void blow(Player player, ItemStack hand) {
		HornConfig config = plugin.settings();
		List<UUID> animals = bindings.animalsOfItem(hand);
		if (animals.isEmpty()) {
			player.sendActionBar(HornText.component("&7这只山羊角还没有绑定任何宠物。"));
			return;
		}
		Long last = cooldowns.get(player.getUniqueId());
		long now = System.currentTimeMillis();
		if (last != null && now - last < config.cooldownSeconds() * 1000L) {
			return;
		}
		cooldowns.put(player.getUniqueId(), now);
		Location target = player.getLocation();
		World world = player.getWorld();
		int moved = 0;
		int missing = 0;
		List<UUID> remaining = new ArrayList<>();
		for (UUID animalId : animals) {
			Entity entity = Bukkit.getEntity(animalId);
			if (entity == null || !entity.isValid()) {
				// 动物已死亡或被清除：从羊角上摘掉，下次吹奏不再扫它
				missing++;
				continue;
			}
			remaining.add(animalId);
			// 区块未加载时实体对象也能拿到，但传送前要先确认区块活着
			if (!entity.getLocation().getChunk().isLoaded()) {
				entity.getLocation().getChunk().load();
			}
			if (entity.teleport(target)) {
				moved++;
				if (config.particles()) {
					world.spawnParticle(Particle.PORTAL, entity.getLocation(), 20, 0.3, 0.6, 0.3, 0.5);
				}
			}
		}
		if (config.playSound()) {
			world.playSound(target, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f);
		}
		if (missing > 0) {
			// 清掉已消失的动物并刷新 lore（editMeta 同样要回写背包）
			boolean edited = hand.editMeta(meta -> {
				bindings.writeAnimalsMeta(meta, remaining);
				meta.lore(buildLore(remaining, null));
			});
			if (edited) {
				player.getInventory().setItemInMainHand(hand);
			}
		}
		if (moved > 0) {
			player.sendActionBar(HornText.component("&a羊角召回：&f" + moved + " &a只宠物已传送到你身边"
				+ (missing > 0 ? " &7（" + missing + " 只已不在，自动解绑）" : "")));
		} else if (missing > 0) {
			player.sendActionBar(HornText.component("&7羊角上的 " + missing + " 只宠物已不在，自动解绑。"));
		} else {
			player.sendActionBar(HornText.component("&7召回没有成功，再试一次。"));
		}
	}

	/** 羊角 lore：标题 + 绑定动物清单。 */
	private List<Component> buildLore(List<UUID> animals, Void unused) {
		List<Component> lore = new ArrayList<>();
		lore.add(HornText.component("&8-- 宠物哨 --"));
		lore.add(HornText.component("&7shift+右键驯服的动物：绑定"));
		lore.add(HornText.component("&7吹奏：召回全部绑定宠物"));
		for (UUID animalId : animals) {
			Entity entity = Bukkit.getEntity(animalId);
			if (entity instanceof Tameable animal) {
				lore.add(HornText.component("&b· " + describe(animal)));
			} else {
				lore.add(HornText.component("&8· " + HornBindings.shortId(animalId) + "（当前不在场）"));
			}
		}
		return lore;
	}

	/** 动物显示名：有名字用「名字（类型）」，没名字只报类型。 */
	private String describe(Entity entity) {
		String type = entity.getType().getKey().getKey().replace('_', ' ');
		Component custom = entity.customName();
		if (custom != null && !HornText.plain(custom).isBlank()) {
			return HornText.plain(custom) + "（" + type + "）";
		}
		return type;
	}
}
