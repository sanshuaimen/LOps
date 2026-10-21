package com.example.settle;

public class SettlementBufferTest {

    public void drainsSettlement(String combo, int expected) {
        SettlementBuffer buffer = new SettlementBuffer(expected);
        for (int i = 0; i < expected + 10; i++) {
            buffer.append("row-" + i);
        }
        if (buffer.buffered() != expected + 10) {
            throw new AssertionError("buffer lost rows");
        }
        buffer.drain();
        // flush must not overflow after a drain
        buffer.flush(combo, expected);
    }
}
