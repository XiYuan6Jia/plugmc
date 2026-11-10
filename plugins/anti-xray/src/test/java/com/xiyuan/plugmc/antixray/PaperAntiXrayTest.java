package com.xiyuan.plugmc.antixray;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperAntiXrayTest {
	private static final List<String> SAMPLE = List.of(
		"# paper world defaults",
		"anti-xray:",
		"    enabled: false",
		"    engine-mode: 1",
		"    hidden-blocks:",
		"    - diamond_ore",
		"chunks:",
		"    auto-save-interval: default"
	);

	private static final List<String> NESTED = List.of(
		"world-settings:",
		"  default:",
		"    anti-xray:",
		"      enabled: false",
		"      engine-mode: 2",
		"      hidden-blocks:",
		"      - diamond_ore",
		"    chunks:",
		"      auto-save-interval: default"
	);

	@Test
	void parsesAntiXraySection() {
		PaperAntiXray.Status status = PaperAntiXray.parse(SAMPLE);
		assertTrue(status.known());
		assertFalse(status.enabled());
		assertEquals(1, status.engineMode());
	}

	@Test
	void parsesNestedWorldSettings() {
		PaperAntiXray.Status status = PaperAntiXray.parse(NESTED);
		assertTrue(status.known());
		assertFalse(status.enabled());
		assertEquals(2, status.engineMode());
	}

	@Test
	void applyWorksForNestedSection(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("paper-world-defaults.yml");
		Files.write(file, NESTED, StandardCharsets.UTF_8);

		PaperAntiXray.apply(file, 1);

		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		assertTrue(lines.contains("      enabled: true"));
		assertTrue(lines.contains("      engine-mode: 1"));
		assertTrue(lines.contains("      - diamond_ore"));
	}

	@Test
	void applyEnablesAndUpdatesEngineMode(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("paper-world-defaults.yml");
		Files.write(file, SAMPLE, StandardCharsets.UTF_8);

		PaperAntiXray.apply(file, 2);

		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		assertTrue(lines.contains("    enabled: true"));
		assertTrue(lines.contains("    engine-mode: 2"));
		assertTrue(lines.contains("    - diamond_ore"));
		assertTrue(lines.contains("# paper world defaults"));
		PaperAntiXray.Status status = PaperAntiXray.status(file);
		assertTrue(status.enabled());
		assertEquals(2, status.engineMode());
	}
}
