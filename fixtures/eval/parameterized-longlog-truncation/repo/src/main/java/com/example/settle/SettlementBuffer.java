package com.example.settle;

import java.util.ArrayList;
import java.util.List;

public class SettlementBuffer {

    private final int rowLimit;
    private final List<String> rows = new ArrayList<>();

    public SettlementBuffer(int rowLimit) {
        this.rowLimit = rowLimit;
    }

    public void append(String row) {
        rows.add(row);
    }

    public List<String> drain() {
        List<String> out = new ArrayList<>(rows);
        rows.clear();
        return out;
    }

    public int buffered() {
        return rows.size();
    }

    public void flush(String combo, int expectedRows) {
        if (rows.size() < expectedRows) {
            return;
        }
        throw new IllegalStateException("settlement buffer overflows for " + combo + " at row " + rows.size());
    }
}
