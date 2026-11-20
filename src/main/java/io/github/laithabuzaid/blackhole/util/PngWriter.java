package io.github.laithabuzaid.blackhole.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Self-contained, zero-dependency 8-bit RGB PNG encoder using only
 * {@link Deflater} and {@link CRC32} (no {@code java.desktop} / AWT dependency).
 *
 * <p>Accepts raw bottom-up OpenGL {@code GL_RGB} / {@code GL_UNSIGNED_BYTE}
 * pixel arrays from {@code glReadPixels} and flips scanlines vertically on the
 * fly while emitting standard PNG chunks ({@code IHDR}, {@code IDAT}, {@code IEND}).</p>
 */
public final class PngWriter {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    private PngWriter() {
    }

    /**
     * Encodes an OpenGL bottom-up RGB framebuffer byte array as a standard 8-bit RGB PNG.
     *
     * @param outputPath target file path (parent directories created automatically)
     * @param width      image width in pixels
     * @param height     image height in pixels
     * @param bottomUpRgb tightly packed RGB bytes ({@code width * height * 3} bytes, OpenGL origin at bottom-left)
     */
    public static void writeRgbPng(Path outputPath, int width, int height, byte[] bottomUpRgb) throws IOException {
        int stride = width * 3;
        if (bottomUpRgb.length < stride * height) {
            throw new IllegalArgumentException("RGB buffer too small for " + width + "x" + height);
        }

        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Flip scanlines vertically (OpenGL bottom-left -> PNG top-left) and prepend filter byte 0 (None)
        byte[] rawScanlines = new byte[(stride + 1) * height];
        for (int y = 0; y < height; y++) {
            int srcOffset = (height - 1 - y) * stride;
            int dstOffset = y * (stride + 1);
            rawScanlines[dstOffset] = 0; // PNG filter type 0 (None)
            System.arraycopy(bottomUpRgb, srcOffset, rawScanlines, dstOffset + 1, stride);
        }

        byte[] compressedData = deflate(rawScanlines);

        ByteArrayOutputStream out = new ByteArrayOutputStream(compressedData.length + 128);
        out.write(PNG_SIGNATURE);

        // IHDR chunk (13 bytes): width, height, bitDepth=8, colorType=2 (RGB), comp=0, filter=0, interlace=0
        byte[] ihdr = new byte[13];
        putIntBE(ihdr, 0, width);
        putIntBE(ihdr, 4, height);
        ihdr[8] = 8; // 8-bit per channel
        ihdr[9] = 2; // Truecolor RGB
        ihdr[10] = 0;
        ihdr[11] = 0;
        ihdr[12] = 0;
        writeChunk(out, "IHDR", ihdr);

        // IDAT chunk
        writeChunk(out, "IDAT", compressedData);

        // IEND chunk
        writeChunk(out, "IEND", new byte[0]);

        Files.write(outputPath, out.toByteArray());
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(6);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(input.length / 2);
            byte[] buf = new byte[16384];
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                if (count > 0) {
                    compressed.write(buf, 0, count);
                }
            }
            return compressed.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] lenBytes = new byte[4];
        putIntBE(lenBytes, 0, data.length);
        out.write(lenBytes);

        byte[] typeBytes = new byte[] {
                (byte) type.charAt(0),
                (byte) type.charAt(1),
                (byte) type.charAt(2),
                (byte) type.charAt(3)
        };
        out.write(typeBytes);
        if (data.length > 0) {
            out.write(data);
        }

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        if (data.length > 0) {
            crc.update(data);
        }
        byte[] crcBytes = new byte[4];
        putIntBE(crcBytes, 0, (int) crc.getValue());
        out.write(crcBytes);
    }

    private static void putIntBE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >>> 24);
        buf[offset + 1] = (byte) (value >>> 16);
        buf[offset + 2] = (byte) (value >>> 8);
        buf[offset + 3] = (byte) value;
    }
}
