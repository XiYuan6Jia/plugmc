package com.xiyuan.plugmc.hub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HubReturnsTest {
	private static final Logger LOGGER = Logger.getLogger("hub-test");

	@Test
	void savesAndReloadsReturnPoints() throws IOException {
		Path folder = Files.createTempDirectory("hub-returns");
		UUID player = UUID.fromString("0ea66886-09b8-410a-a075-d03708fb7635");

		HubReturns first = new HubReturns(folder.toFile(), LOGGER);
		first.load();
		assertEquals(0, first.size());
		first.put(player, new HubReturns.Point("world", 12.5, 64.0, -3.25, 90.0f, 12.0f));
		first.save();

		HubReturns second = new HubReturns(folder.toFile(), LOGGER);
		second.load();
		assertEquals(1, second.size());
		HubReturns.Point point = second.point(player);
		assertNotNull(point);
		assertEquals("world", point.world());
		assertEquals(12.5, point.x(), 1e-9);
		assertEquals(64.0, point.y(), 1e-9);
		assertEquals(-3.25, point.z(), 1e-9);
		assertEquals(90.0f, point.yaw(), 1e-4);
		assertEquals(12.0f, point.pitch(), 1e-4);
	}

	@Test
	void forgetDropsPointAndPersists() throws IOException {
		Path folder = Files.createTempDirectory("hub-returns");
		UUID player = UUID.randomUUID();

		HubReturns returns = new HubReturns(folder.toFile(), LOGGER);
		returns.put(player, new HubReturns.Point("world", 1, 2, 3, 0, 0));
		returns.save();
		returns.forget(player);

		HubReturns reloaded = new HubReturns(folder.toFile(), LOGGER);
		reloaded.load();
		assertNull(reloaded.point(player));
		assertEquals(0, reloaded.size());
	}

	@Test
	void ignoresBrokenEntries() throws IOException {
		Path folder = Files.createTempDirectory("hub-returns");
		Path file = folder.resolve("returns.yml");
		Files.writeString(file, "players:\n  not-a-uuid:\n    world: world\n    x: 1\n    y: 2\n    z: 3\n");

		HubReturns returns = new HubReturns(folder.toFile(), LOGGER);
		returns.load();
		assertEquals(0, returns.size());
	}
}
