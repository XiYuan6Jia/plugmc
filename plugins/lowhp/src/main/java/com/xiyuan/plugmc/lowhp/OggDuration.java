package com.xiyuan.plugmc.lowhp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 从 OGG Vorbis 字节里读出采样率与时长（最后一个 Ogg 页的 granule position ÷ 采样率）。
 */
final class OggDuration {
	private static final byte[] MAGIC = {'O', 'g', 'g', 'S'};
	private static final int MAX_TAIL = 65536;

	private OggDuration() {
	}

	static Reader probe(byte[] data) {
		if (data == null || data.length < 32 || !startsWithOgg(data, 0)) {
			return null;
		}
		int headerLength = 27 + (data[26] & 0xFF);
		if (data.length < headerLength + 16) {
			return null;
		}
		int packet = headerLength;
		if ((data[packet] & 0xFF) != 0x01 || data[packet + 1] != 'v'
			|| data[packet + 2] != 'o' || data[packet + 3] != 'r'
			|| data[packet + 4] != 'b' || data[packet + 5] != 'i'
			|| data[packet + 6] != 's') {
			return null;
		}
		int channels = data[packet + 11] & 0xFF;
		int sampleRate = ByteBuffer.wrap(data, packet + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
		if (sampleRate < 8000 || sampleRate > 192000 || channels < 1) {
			return null;
		}
		long granule = lastGranule(data);
		float seconds = granule <= 0L ? 0f : granule / (float) sampleRate;
		return new Reader(sampleRate, channels, seconds);
	}

	/**
	 * 只取头尾信息，播放时用来判断片段长度。
	 */
	record Reader(int sampleRate, int channels, float seconds) {
	}

	private static long lastGranule(byte[] data) {
		int start = Math.max(0, data.length - MAX_TAIL);
		for (int index = data.length - 4; index >= start; index--) {
			if (!startsWithOgg(data, index) || index + 14 > data.length) {
				continue;
			}
			long granule = 0L;
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
}
