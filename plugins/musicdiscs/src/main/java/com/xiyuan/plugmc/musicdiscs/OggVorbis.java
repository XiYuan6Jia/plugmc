package com.xiyuan.plugmc.musicdiscs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 只读取 Ogg 容器与 Vorbis 识别头，用来校验文件格式并算出时长。
 *
 * <p>Minecraft 只接受 OGG Vorbis（推荐单声道 44.1kHz），因此导入时直接拒绝 mp3/wav，
 * 避免把客户端无法播放的文件塞进资源包。</p>
 */
public final class OggVorbis {
	private static final byte[] MAGIC = {'O', 'g', 'g', 'S'};
	private static final int MAX_TAIL = 65536;

	private OggVorbis() {
	}

	public record Info(int sampleRate, int channels, float seconds) {
	}

	public static Optional<Info> probe(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			return probe(Files.readAllBytes(file));
		} catch (IOException exception) {
			return Optional.empty();
		}
	}

	public static Optional<Info> probe(InputStream input) {
		try {
			return probe(input.readAllBytes());
		} catch (IOException exception) {
			return Optional.empty();
		}
	}

	/**
	 * 直接在内存里解析：第一页取 Vorbis 识别头，最后一页取 granule position 算时长。
	 */
	public static Optional<Info> probe(byte[] data) {
		if (data == null || data.length < 32) {
			return Optional.empty();
		}
		if (!startsWithOgg(data, 0)) {
			return Optional.empty();
		}
		int headerLength = 27 + (data[26] & 0xFF);
		if (data.length < headerLength + 16) {
			return Optional.empty();
		}
		int packet = headerLength;
		if ((data[packet] & 0xFF) != 0x01 || data[packet + 1] != 'v'
			|| data[packet + 2] != 'o' || data[packet + 3] != 'r'
			|| data[packet + 4] != 'b' || data[packet + 5] != 'i'
			|| data[packet + 6] != 's') {
			return Optional.empty();
		}
		int channels = data[packet + 11] & 0xFF;
		int sampleRate = readIntLE(data, packet + 12);
		if (sampleRate < 8000 || sampleRate > 192000 || channels < 1 || channels > 8) {
			return Optional.empty();
		}
		long granule = lastGranule(data);
		float seconds = granule <= 0 ? 0f : granule / (float) sampleRate;
		return Optional.of(new Info(sampleRate, channels, seconds));
	}

	private static long lastGranule(byte[] data) {
		int start = Math.max(0, data.length - MAX_TAIL);
		for (int index = data.length - 4; index >= start; index--) {
			if (!startsWithOgg(data, index) || index + 14 > data.length) {
				continue;
			}
			long granule = 0;
			for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
				granule |= (long) (data[index + 6 + byteIndex] & 0xFF) << (8 * byteIndex);
			}
			return granule;
		}
		return 0L;
	}

	private static boolean startsWithOgg(byte[] data, int offset) {
		if (data.length < offset + 4) {
			return false;
		}
		for (int index = 0; index < 4; index++) {
			if (data[offset + index] != MAGIC[index]) {
				return false;
			}
		}
		return true;
	}

	private static int readIntLE(byte[] data, int offset) {
		return (data[offset] & 0xFF)
			| ((data[offset + 1] & 0xFF) << 8)
			| ((data[offset + 2] & 0xFF) << 16)
			| ((data[offset + 3] & 0xFF) << 24);
	}
}
