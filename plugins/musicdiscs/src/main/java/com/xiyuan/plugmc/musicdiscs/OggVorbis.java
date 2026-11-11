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

	private OggVorbis() {
	}

	public record Info(int sampleRate, int channels, float seconds) {
	}

	public static Optional<Info> probe(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return Optional.empty();
		}
		long size;
		try {
			size = Files.size(file);
		} catch (IOException exception) {
			return Optional.empty();
		}
		if (size < 32) {
			return Optional.empty();
		}
		try (InputStream input = Files.newInputStream(file)) {
			byte[] head = input.readNBytes((int) Math.min(8192, size));
			if (!startsWithOgg(head, 0)) {
				return Optional.empty();
			}
			int headerLength = 27 + (head[26] & 0xFF);
			if (head.length < headerLength + 16) {
				return Optional.empty();
			}
			int packet = headerLength;
			// 0x01 'vorbis'
			if ((head[packet] & 0xFF) != 0x01 || head[packet + 1] != 'v'
				|| head[packet + 2] != 'o' || head[packet + 3] != 'r'
				|| head[packet + 4] != 'b' || head[packet + 5] != 'i'
				|| head[packet + 6] != 's') {
				return Optional.empty();
			}
			int channels = head[packet + 11] & 0xFF;
			int sampleRate = readIntLE(head, packet + 12);
			if (sampleRate < 8000 || sampleRate > 192000 || channels < 1 || channels > 8) {
				return Optional.empty();
			}
			long granule = lastGranule(file, size);
			float seconds = granule <= 0 ? 0f : granule / (float) sampleRate;
			return Optional.of(new Info(sampleRate, channels, seconds));
		} catch (IOException exception) {
			return Optional.empty();
		}
	}

	private static long lastGranule(Path file, long size) throws IOException {
		int window = (int) Math.min(65536, size);
		byte[] tail = new byte[window];
		try (var channel = Files.newByteChannel(file)) {
			channel.position(size - window);
			var buffer = java.nio.ByteBuffer.wrap(tail);
			while (buffer.hasRemaining() && channel.read(buffer) > 0) {
				// 读取尾部窗口
			}
		}
		for (int index = window - 4; index >= 0; index--) {
			if (startsWithOgg(tail, index) && index + 14 <= window) {
				long granule = 0;
				for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
					granule |= (long) (tail[index + 6 + byteIndex] & 0xFF) << (8 * byteIndex);
				}
				return granule;
			}
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
