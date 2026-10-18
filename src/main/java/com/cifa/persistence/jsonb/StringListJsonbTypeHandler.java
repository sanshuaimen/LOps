package com.cifa.persistence.jsonb;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.json.JSONArray;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps List&lt;String&gt; to a PostgreSQL JSONB column. setObject with Types.OTHER
 * is what the PG driver requires for jsonb; setString alone would need a
 * stringtype=unspecified URL flag, which we do not want to mandate.
 */
@MappedTypes(List.class)
public class StringListJsonbTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, new JSONArray(parameter).toString(), Types.OTHER);
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private static List<String> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JSONArray array = new JSONArray(json);
            List<String> values = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                values.add(array.getString(i));
            }
            return List.copyOf(values);
        } catch (org.json.JSONException e) {
            throw new SQLException("invalid JSONB content: " + e.getMessage(), e);
        }
    }
}
