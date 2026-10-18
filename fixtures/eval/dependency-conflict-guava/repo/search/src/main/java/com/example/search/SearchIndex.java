package com.example.search;

import com.google.common.collect.ImmutableList;

import java.util.List;

public class SearchIndex {
    public List<String> load(List<String> rows) {
        return rows.stream().map(this::norm).collect(ImmutableList.toImmutableList());
    }

    private String norm(String value) {
        return value.strip();
    }
}
