package com.gnatok.translator.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class TurnGateTest {
    @Test public void outOfOrderResultCannotReplaceNewTurn() {
        TurnGate gate = new TurnGate();
        long slowLithuanian = gate.next();
        long englishReply = gate.next();
        assertTrue(gate.accepts(englishReply));
        assertFalse(gate.accepts(slowLithuanian));
    }
    @Test public void cancelInvalidatesInFlightResult() {
        TurnGate gate = new TurnGate();
        long pending = gate.next();
        gate.next();
        assertFalse(gate.accepts(pending));
    }
}
