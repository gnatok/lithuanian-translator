package com.gnatok.translator.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Strict bounded WAV input for repeatable ASR fixtures; no implicit resampling. */
public final class WavReader {
    public static float[] read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        if (!id(in).equals("RIFF")) throw new IOException("Expected RIFF WAV");
        long remaining = uint(in);
        if (remaining < 4 || remaining > 2_000_000) throw new IOException("WAV container too large or invalid");
        if (!id(in).equals("WAVE")) throw new IOException("Expected WAVE");
        remaining -= 4;
        boolean format = false;
        while (remaining >= 8) {
            String chunk = id(in); long length = uint(in); remaining -= 8;
            long padded = length + (length & 1);
            if (padded > remaining) throw new IOException("Truncated WAV chunk");
            if (chunk.equals("fmt ")) {
                if (length < 16 || length > 1024) throw new IOException("Invalid WAV format");
                byte[] bytes = new byte[(int)length]; in.readFully(bytes);
                ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                if (b.getShort() != 1 || b.getShort() != 1 || b.getInt() != 16000 || b.getInt() != 32000 || b.getShort() != 2 || b.getShort() != 16)
                    throw new IOException("Use 16 kHz mono PCM16 WAV, at most 20 seconds");
                format = true;
            } else if (chunk.equals("data")) {
                if (!format || length < 8000 || length > 640000 || (length & 1) != 0) throw new IOException("Invalid audio length or missing format; use 0.25–20 seconds");
                byte[] bytes = new byte[(int)length]; in.readFully(bytes);
                PcmTurn turn = new PcmTurn(); turn.append(ByteBuffer.wrap(bytes), 0); return turn.finish();
            } else {
                long left = length;
                while (left > 0) { int skipped = in.skipBytes((int)left); if (skipped == 0) { in.readByte(); skipped = 1; } left -= skipped; }
            }
            if ((length & 1) != 0) in.readByte();
            remaining -= padded;
        }
        throw new IOException("Missing audio data");
    }
    private static String id(DataInputStream in) throws IOException { byte[] b = new byte[4]; in.readFully(b); return new String(b, StandardCharsets.US_ASCII); }
    private static long uint(DataInputStream in) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt())); }
}
