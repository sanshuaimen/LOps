package com.example.search;

import java.sql.Connection;
import java.sql.SQLException;

public class IndexRefresher {

    private final Connection connection;

    public IndexRefresher(Connection connection) {
        this.connection = connection;
    }

    public void refresh() throws SQLException {
        connection.createStatement().execute("ANALYZE documents");
    }
}
