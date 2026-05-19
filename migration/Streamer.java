package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.model.Codec;

import android.media.MediaCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Streamer {

    private static final long PACKET_FLAG_SESSION = 1L << 63;
    private static final long PACKET_FLAG_CONFIG = 1L << 62;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 61;

    private final OutputStream outputStream;
    private final Codec codec;
    private final boolean sendStreamMeta;
    private final boolean sendFrameMeta;

    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    public Streamer(OutputStream outputStream, Codec codec, boolean sendCodecMeta, boolean sendFrameMeta) {
        this.outputStream = outputStream;
        this.codec = codec;
        this.sendStreamMeta = sendCodecMeta;
        this.sendFrameMeta = sendFrameMeta;
    }

    public Codec getCodec() {
        return codec;
    }

    public void writeAudioHeader() throws IOException {
        if (sendStreamMeta) {
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(codec.getId());
            buffer.flip();
            writeFully(buffer);
        }
    }

    public void writeVideoHeader() throws IOException {
        if (sendStreamMeta) {
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(codec.getId());
            buffer.flip();
            writeFully(buffer);
        }
    }

    public void writeDisableStream(boolean error) throws IOException {
        byte[] code = new byte[4];
        if (error) {
            code[3] = 1;
        }
        outputStream.write(code, 0, code.length);
        outputStream.flush();
    }

    public void writePacket(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) throws IOException {
        if (config) {
            if (codec == AudioCodec.OPUS) {
                fixOpusConfigPacket(buffer);
            } else if (codec == AudioCodec.FLAC) {
                fixFlacConfigPacket(buffer);
            }
        }

        if (sendFrameMeta) {
            writeFrameMeta(buffer.remaining(), pts, config, keyFrame);
        }

        writeFully(buffer);
    }

    public void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        long pts = bufferInfo.presentationTimeUs;
        boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        writePacket(codecBuffer, pts, config, keyFrame);
    }

    public void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException {
        if (sendStreamMeta) {
            headerBuffer.clear();

            int flags = (int) (PACKET_FLAG_SESSION >> 32);
            if (isClientResize) {
                flags |= 1;
            }
            headerBuffer.putInt(flags);
            headerBuffer.putInt(width);
            headerBuffer.putInt(height);
            headerBuffer.flip();
            writeFully(headerBuffer);
        }
    }

    private void writeFrameMeta(int packetSize, long pts, boolean config, boolean keyFrame) throws IOException {
        headerBuffer.clear();

        long ptsAndFlags;
        if (config) {
            ptsAndFlags = PACKET_FLAG_CONFIG;
        } else {
            ptsAndFlags = pts;
            if (keyFrame) {
                ptsAndFlags |= PACKET_FLAG_KEY_FRAME;
            }
        }

        headerBuffer.putLong(ptsAndFlags);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        writeFully(headerBuffer);
    }

    private void writeFully(ByteBuffer from) throws IOException {
        if (from.hasArray()) {
            outputStream.write(from.array(), from.arrayOffset() + from.position(), from.remaining());
            from.position(from.limit());
        } else {
            byte[] tmp = new byte[from.remaining()];
            from.get(tmp);
            outputStream.write(tmp);
        }
        outputStream.flush();
    }

    private static void fixOpusConfigPacket(ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < 16) {
            throw new IOException("Not enough data in OPUS config packet");
        }

        final byte[] opusHeaderId = {'A', 'O', 'P', 'U', 'S', 'H', 'D', 'R'};
        byte[] idBuffer = new byte[8];
        buffer.get(idBuffer);
        if (!Arrays.equals(idBuffer, opusHeaderId)) {
            throw new IOException("OPUS header not found");
        }

        long sizeLong = buffer.getLong();
        if (sizeLong < 0 || sizeLong >= 0x7FFFFFFF) {
            throw new IOException("Invalid block size in OPUS header: " + sizeLong);
        }

        int size = (int) sizeLong;
        if (buffer.remaining() < size) {
            throw new IOException("Not enough data in OPUS header (invalid size: " + size + ")");
        }

        buffer.limit(buffer.position() + size);
    }

    private static void fixFlacConfigPacket(ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < 8) {
            throw new IOException("Not enough data in FLAC config packet");
        }

        final byte[] flacHeaderId = {'f', 'L', 'a', 'C'};
        byte[] idBuffer = new byte[4];
        buffer.get(idBuffer);
        if (!Arrays.equals(idBuffer, flacHeaderId)) {
            throw new IOException("FLAC header not found");
        }

        buffer.order(ByteOrder.BIG_ENDIAN);

        int size = buffer.getInt();
        if (buffer.remaining() < size) {
            throw new IOException("Not enough data in FLAC header (invalid size: " + size + ")");
        }

        buffer.limit(buffer.position() + size);
    }
}
