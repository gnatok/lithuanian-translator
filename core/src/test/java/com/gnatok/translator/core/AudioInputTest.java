package com.gnatok.translator.core;

import java.io.*;
import java.nio.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioInputTest {
    @Test public void pcmReadsOnlyRemainingBytesAndPreservesCallerPosition() {
        ByteBuffer bytes = ByteBuffer.allocate(8002).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putShort((short)123); bytes.putShort(Short.MIN_VALUE); bytes.putShort(Short.MAX_VALUE);
        bytes.position(2); bytes.limit(8002);
        PcmTurn turn = new PcmTurn(); turn.append(bytes, 0);
        float[] result = turn.finish();
        assertEquals(2, bytes.position()); assertEquals(-1f, result[0],0f); assertEquals(32767f/32768,result[1],0f);
    }
    @Test public void capsLongTurnsAtTwentySeconds() {
        PcmTurn turn = new PcmTurn(); assertTrue(turn.append(ByteBuffer.allocate(700000),0));
        assertEquals(320000, turn.finish().length);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsAudioGaps() {
        PcmTurn turn = new PcmTurn(); turn.append(ByteBuffer.allocate(3200),0); turn.append(ByteBuffer.allocate(3200),500000);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsReorderedAudio() {
        PcmTurn turn = new PcmTurn(); turn.append(ByteBuffer.allocate(3200),100000); turn.append(ByteBuffer.allocate(3200),0);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsIncompleteSample() { new PcmTurn().append(ByteBuffer.allocate(3),0); }
    @Test public void acceptsPaddedUnknownWavChunk() throws Exception {
        float[] audio = WavReader.read(new ByteArrayInputStream(wav(16000, true)));
        assertEquals(4000,audio.length);
    }
    @Test(expected=IOException.class) public void rejectsWrongSampleRate() throws Exception { WavReader.read(new ByteArrayInputStream(wav(48000,false))); }
    @Test(expected=IOException.class) public void rejectsTruncatedData() throws Exception {
        byte[] data = wav(16000,false); WavReader.read(new ByteArrayInputStream(java.util.Arrays.copyOf(data,data.length-10)));
    }
    @Test(expected=IOException.class) public void rejectsOversizedContainerBeforeAllocating() throws Exception {
        byte[] data = wav(16000,false); ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(4,Integer.MAX_VALUE);
        WavReader.read(new ByteArrayInputStream(data));
    }
    private byte[] wav(int sampleRate, boolean junk) {
        int extra = junk ? 10 : 0;
        ByteBuffer b = ByteBuffer.allocate(8044+extra).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(b.capacity()-8).put("WAVE".getBytes());
        if(junk) b.put("JUNK".getBytes()).putInt(1).put((byte)42).put((byte)0);
        b.put("fmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(sampleRate).putInt(sampleRate*2).putShort((short)2).putShort((short)16);
        b.put("data".getBytes()).putInt(8000); return b.array();
    }
}
