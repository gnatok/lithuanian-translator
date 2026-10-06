package com.gnatok.translator.core;

/** Prevents an old asynchronous translation replacing a newer turn or direction. */
public final class TurnGate {
    private long generation;
    public long next() { return ++generation; }
    public boolean accepts(long ticket) { return ticket == generation; }
}
