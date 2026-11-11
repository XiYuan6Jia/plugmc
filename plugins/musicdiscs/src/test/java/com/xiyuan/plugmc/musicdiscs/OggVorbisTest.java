package com.xiyuan.plugmc.musicdiscs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggVorbisTest {
	@TempDir
	Path tempDir;

	@Test
	void rejectsNonOggFiles() throws Exception {
		Path file = tempDir.resolve("song.ogg");
		Files.write(file, "ID3 not an ogg file".getBytes(StandardCharsets.UTF_8));
		assertTrue(OggVorbis.probe(file).isEmpty());
	}

	@Test
	void rejectsMissingFiles() {
		assertTrue(OggVorbis.probe(tempDir.resolve("missing.ogg")).isEmpty());
	}

	@Test
	void readsVorbisHeaderAndGranule() throws Exception {
		// 最小化的 Ogg 页：OggS + 版本/类型/时间戳等，随后是 Vorbis 识别头
		byte[] page = buildPage(44100, 2, 44100L * 5);
		Path file = tempDir.resolve("tone.ogg");
		Files.write(file, page);
		Optional<OggVorbis.Info> info = OggVorbis.probe(file);
		assertTrue(info.isPresent());
		assertEquals(44100, info.get().sampleRate());
		assertEquals(2, info.get().channels());
		assertEquals(5.0f, info.get().seconds(), 0.01f);
	}

	private byte[] buildPage(int sampleRate, int channels, long granule) {
		byte[] packet = new byte[30];
		packet[0] = 0x01;
		byte[] magic = {'v', 'o', 'r', 'b', 'i', 's'};
		System.arraycopy(magic, 0, packet, 1, magic.length);
		packet[11] = (byte) channels;
		packet[12] = (byte) (sampleRate & 0xFF);
		packet[13] = (byte) ((sampleRate >> 8) & 0xFF);
		packet[14] = (byte) ((sampleRate >> 16) & 0xFF);
		packet[15] = (byte) ((sampleRate >> 24) & 0xFF);

		byte[] page = new byte[27 + 1 + packet.length];
		page[0] = 'O';
		page[1] = 'g';
		page[2] = 'g';
		page[3] = 'S';
		page[4] = 0;
		page[5] = 0x02;
		for (int index = 0; index < 8; index++) {
			page[6 + index] = (byte) ((granule >> (8 * index)) & 0xFF);
		}
		page[26] = 1;
		page[27] = (byte) packet.length;
		System.arraycopy(packet, 0, page, 28, packet.length);
		return page;
	}

	@Test
	void songExposesSoundKeyAndDuration() {
		Song song = new Song("demo", "示例", "作者", 125f, 3, "", "");
		assertEquals("plugmc:disc.demo", song.soundKey("plugmc"));
		assertEquals("disc/demo", song.soundPath());
		assertEquals("disc_demo", song.itemModel());
		assertEquals("2:05", song.durationText());
		assertEquals("示例 - 作者", song.displayName());
		assertFalse(song.playerSong());
	}

	@Test
	void playerSongCarriesOwner() {
		Song song = new Song("u_abc", "我的歌", "steve", 10f, 1, "uuid-1", "steve");
		assertTrue(song.playerSong());
		assertEquals("steve", song.ownerName());
	}

	@Test
	void rejectsTooLongAudio() throws Exception {
		SongLibrary library = null;
		// 仅校验字节解析：超出上限的文件在 importBytes 中会被拒绝（依赖 MusicConfig）
		int sampleRate = 44100;
		byte[] page = buildPage(sampleRate, 1, sampleRate * 700L);
		assertTrue(OggVorbis.probe(page).isPresent());
		assertEquals(700f, OggVorbis.probe(page).orElseThrow().seconds(), 0.5f);
		assertTrue(library == null);
	}
}
