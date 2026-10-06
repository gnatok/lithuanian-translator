package com.gnatok.translator.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Bounded PCM16 mono turn. Rejects reordered frames and audible gaps instead of hiding them. */
public final class PcmTurn {
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_SECONDS = 20;
    private final float[] samples = new float[SAMPLE_RATE * MAX_SECONDS];
    private int count;
    private long expectedUs = -1;
    public synchronized boolean append(ByteBuffer input, long presentationUs) {
        ByteBuffer frame = input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if ((frame.remaining() & 1) != 0) throw new IllegalArgumentException("Incomplete PCM16 sample");
        if (expectedUs >= 0 && (presentationUs < expectedUs - 2000 || presentationUs > expectedUs + 100000))
            throw new IllegalArgumentException("Audio frames reordered or missing; repeat this turn");
        int incoming = frame.remaining() / 2;
        expectedUs = presentationUs + incoming * 1000000L / SAMPLE_RATE;
        int accepted = Math.min(incoming, samples.length - count);
        for (int i = 0; i < accepted; i++) samples[count++] = frame.getShort() / 32768f;
        return count == samples.length;
    }
    public synchronized float[] finish() {
        if (count < SAMPLE_RATE / 4) throw new IllegalArgumentException("Not enough audio; speak for at least a quarter second");
        return Arrays.copyOf(samples, count);
    }
    public synchronized int size() { return count; }
}
