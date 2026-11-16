package com.xiyuan.plugmc.megastructures;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * 观景台实体交互点：每个观景点旁边放一个隐形 Interaction 命中框、
 * 一个景观名称悬浮字和一个图标。右键命中框打开巨构传送列表。
 */
public final class MegaInteractions {
	static final String ROLE_INTERACTION = "interaction";
	static final String ROLE_LABEL = "label";
	static final String ROLE_ICON = "icon";

	private final MegaPlugin plugin;
	private final NamespacedKey roleKey;
	private final NamespacedKey viewpointKey;

	public MegaInteractions(MegaPlugin plugin) {
		this.plugin = plugin;
		this.roleKey = new NamespacedKey(plugin, "role");
		this.viewpointKey = new NamespacedKey(plugin, "viewpoint");
	}

	public void ensureAllLoaded() {
		if (!plugin.megaWorld().ready()) {
			return;
		}
		for (Chunk chunk : plugin.megaWorld().world().getLoadedChunks()) {
			ensureChunk(chunk);
		}
	}

	public void ensureChunk(Chunk chunk) {
		if (!plugin.megaWorld().ready() || !plugin.megaWorld().world().equals(chunk.getWorld())) {
			return;
		}
		for (MegaViewpoint viewpoint : MegaStructures.viewpoints()) {
			if (Math.floorDiv((int) Math.floor(viewpoint.interactX()), 16) == chunk.getX()
				&& Math.floorDiv((int) Math.floor(viewpoint.interactZ()), 16) == chunk.getZ()) {
				ensureViewpoint(viewpoint);
			}
		}
	}

	public void ensureViewpoint(MegaViewpoint viewpoint) {
		if (!plugin.megaWorld().ready()) {
			return;
		}
		World world = plugin.megaWorld().world();
		Location location = new Location(
			world, viewpoint.interactX(), viewpoint.interactY(), viewpoint.interactZ()
		);
		if (!location.getChunk().isLoaded()) {
			return;
		}
		List<Entity> existing = world.getNearbyEntities(location, 8.0, 8.0, 8.0,
			entity -> isFor(entity, viewpoint.id())).stream().toList();
		boolean hasInteraction = existing.stream().anyMatch(entity -> roleOf(entity, ROLE_INTERACTION));
		boolean hasLabel = existing.stream().anyMatch(entity -> roleOf(entity, ROLE_LABEL));
		boolean hasIcon = existing.stream().anyMatch(entity -> roleOf(entity, ROLE_ICON));
		if (hasInteraction && hasLabel && hasIcon) {
			for (Entity entity : existing) {
				if (entity instanceof TextDisplay label) {
					label.text(labelText(viewpoint));
				}
			}
			return;
		}
		for (Entity entity : existing) {
			entity.remove();
		}
		spawn(world, viewpoint, location);
	}

	private void spawn(World world, MegaViewpoint viewpoint, Location location) {
		world.spawn(location.clone().add(0.0, 1.0, 0.0), Interaction.class,
			CreatureSpawnEvent.SpawnReason.CUSTOM, interaction -> {
				interaction.setInteractionWidth(1.6f);
				interaction.setInteractionHeight(2.2f);
				interaction.setResponsive(true);
				interaction.setPersistent(true);
				interaction.setInvulnerable(true);
				interaction.setSilent(true);
				interaction.setGravity(false);
				interaction.setCustomNameVisible(false);
				tag(interaction, ROLE_INTERACTION, viewpoint.id());
			});
		world.spawn(location.clone().add(0.0, 2.8, 0.0), TextDisplay.class,
			CreatureSpawnEvent.SpawnReason.CUSTOM, display -> {
				display.text(labelText(viewpoint));
				display.setBillboard(Display.Billboard.CENTER);
				display.setSeeThrough(false);
				display.setShadowed(true);
				display.setAlignment(TextDisplay.TextAlignment.CENTER);
				display.setLineWidth(200);
				display.setDefaultBackground(false);
				display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
				display.setPersistent(true);
				display.setInvulnerable(true);
				display.setSilent(true);
				display.setGravity(false);
				tag(display, ROLE_LABEL, viewpoint.id());
			});
		world.spawn(location.clone().add(0.0, 1.25, 0.0), ItemDisplay.class,
			CreatureSpawnEvent.SpawnReason.CUSTOM, display -> {
				display.setItemStack(new ItemStack(viewpoint.icon()));
				display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
				display.setBillboard(Display.Billboard.CENTER);
				display.setPersistent(true);
				display.setInvulnerable(true);
				display.setSilent(true);
				display.setGravity(false);
				tag(display, ROLE_ICON, viewpoint.id());
			});
	}

	private net.kyori.adventure.text.Component labelText(MegaViewpoint viewpoint) {
		return MegaText.component("&6" + viewpoint.label() + "\n&7右键打开巨构传送");
	}

	private void tag(Entity entity, String role, String viewpointId) {
		entity.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role);
		entity.getPersistentDataContainer().set(viewpointKey, PersistentDataType.STRING, viewpointId);
	}

	public boolean isTagged(Entity entity) {
		return entity.getPersistentDataContainer().has(roleKey, PersistentDataType.STRING)
			&& entity.getPersistentDataContainer().has(viewpointKey, PersistentDataType.STRING);
	}

	public boolean isInteraction(Entity entity) {
		return roleOf(entity, ROLE_INTERACTION);
	}

	public MegaViewpoint viewpointOf(Entity entity) {
		String id = entity.getPersistentDataContainer().get(viewpointKey, PersistentDataType.STRING);
		return id == null ? null : MegaStructures.viewpoint(id);
	}

	private boolean isFor(Entity entity, String viewpointId) {
		return viewpointId.equals(entity.getPersistentDataContainer().get(viewpointKey, PersistentDataType.STRING));
	}

	private boolean roleOf(Entity entity, String role) {
		return role.equals(entity.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING));
	}
}
