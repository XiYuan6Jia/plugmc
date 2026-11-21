package com.xiyuan.plugmc.lowhp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertTest {
	@Test
	void soundKeysAndPaths() {
		assertEquals("lowhp:alert.a", Alert.A.soundKey("lowhp"));
		assertEquals("alert/a", Alert.A.soundPath());
		assertEquals("alert_a.ogg", Alert.A.fileName());
	}

	@Test
	void resolvesIdsInAnyCase() {
		assertEquals(Alert.A, Alert.byId("a"));
		assertEquals(Alert.B, Alert.byId("B"));
		assertEquals(Alert.B, Alert.byId("b"));
		assertNull(Alert.byId("c"));
		assertNull(Alert.byId(null));
	}

	@Test
	void bundledAudioIsValidOgg() throws Exception {
		for (Alert alert : Alert.values()) {
			Path file = Path.of("src", "main", "resources", "pack", alert.fileName());
			assertTrue(Files.isRegularFile(file), "缺少内置音频 " + file);
			OggDuration.Reader info = OggDuration.probe(Files.readAllBytes(file));
			assertNotNull(info, alert.fileName() + " 不是有效的 OGG");
			assertEquals(44100, info.sampleRate());
			assertEquals(1, info.channels());
			assertTrue(info.seconds() > 30f, "时长异常：" + info.seconds());
		}
	}
}
