package com.fason.app.core.transfer;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Binary wire framing for socket payload transfer.
 *
 * Replaces base64-encoded JSON blobs with length-prefixed binary frames:
 *   [4B magic "FS01"][1B flags][4B typeLen][4B payloadLen][type UTF-8][payload]
 *
 * Flags bit 0 = payload is zlib-compressed.
 * Compression is applied only when it saves >= 15% and payload > 512 bytes.
 */
public final class BinaryFrame {
    public static final int MAGIC = 0x46533031; // "FS01"
    public static final int FLAG_COMPRESSED = 0x01;
    public static final int HEADER_SIZE = 13;
    private static final int COMPRESS_THRESHOLD = 512;
    private static final double COMPRESS_MIN_SAVING = 0.15;

    private BinaryFrame() {}

    public static byte[] encode(String type, byte[] payload) {
        return encode(type, payload, true);
    }

    public static byte[] encode(String type, byte[] payload, boolean allowCompress) {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        byte[] body = payload != null ? payload : new byte[0];
        int flags = 0;
        if (allowCompress && body.length > COMPRESS_THRESHOLD) {
            byte[] compressed = compress(body);
            if (compressed != null
                    && compressed.length < (int) (body.length * (1.0 - COMPRESS_MIN_SAVING))) {
                body = compressed;
                flags |= FLAG_COMPRESSED;
            }
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + typeBytes.length + body.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(MAGIC);
        buf.put((byte) flags);
        buf.putInt(typeBytes.length);
        buf.putInt(body.length);
        buf.put(typeBytes);
        buf.put(body);
        return buf.array();
    }

    /** Read one frame from a blocking stream. Returns null on clean EOF. */
    public static Frame read(InputStream in) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        int off = 0;
        while (off < HEADER_SIZE) {
            int n = in.read(header, off, HEADER_SIZE - off);
            if (n < 0) {
                if (off == 0) return null;
                throw new EOFException("truncated frame header");
            }
            off += n;
        }
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int magic = buf.getInt();
        if (magic != MAGIC) throw new IOException("bad frame magic: " + Integer.toHexString(magic));
        int flags = buf.get() & 0xFF;
        int typeLen = buf.getInt();
        int bodyLen = buf.getInt();
        if (typeLen < 0 || typeLen > 256 || bodyLen < 0 || bodyLen > 256 * 1024 * 1024) {
            throw new IOException("frame size out of bounds: type=" + typeLen + " body=" + bodyLen);
        }
        byte[] typeBytes = readFully(in, typeLen);
        byte[] body = readFully(in, bodyLen);
        if ((flags & FLAG_COMPRESSED) != 0) {
            body = decompress(body);
        }
        return new Frame(new String(typeBytes, StandardCharsets.UTF_8), body);
    }

    public static void write(OutputStream out, String type, byte[] payload) throws IOException {
        out.write(encode(type, payload));
        out.flush();
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(data, off, len - off);
            if (n < 0) throw new EOFException("truncated frame body");
            off += n;
        }
        return data;
    }

    private static byte[] compress(byte[] data) {
        try {
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2);
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                bos.write(chunk, 0, n);
            }
            deflater.end();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decompress(byte[] data) throws IOException {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3);
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0 && inflater.needsInput()) break;
                bos.write(chunk, 0, n);
            }
            inflater.end();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IOException("frame decompression failed", e);
        }
    }

    public static final class Frame {
        public final String type;
        public final byte[] payload;
        public Frame(String type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
        public String payloadAsString() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }
}
