package com.sitemonitor.service.dbquery;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Testler için en küçük sahte {@link ResultSet} (Proxy): verilen satırları sırayla döner, okunan kolonları kaydeder. */
final class FakeJdbc {

    private FakeJdbc() {}

    /** @param typeName sürücünün tür adı; null = "T&lt;tür&gt;" */
    record Col(String name, int type, int precision, String typeName) {
        Col(String name, int type, int precision) {
            this(name, type, precision, null);
        }
    }

    /** Aynı hücrenin metni ({@code getString}) ve nesnesi ({@code getObject} / {@code getTimestamp}) ayrı (sürücü taklidi). */
    record Dual(String text, Object object) {}

    /** @param readColumns değeri okunan kolon numaraları (1 tabanlı) */
    record Rs(ResultSet resultSet, Set<Integer> readColumns) {}

    static Rs resultSet(List<Col> cols, List<Object[]> rows) {
        ResultSetMetaData md = (ResultSetMetaData) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[] {ResultSetMetaData.class}, (p, m, a) -> switch (m.getName()) {
                    case "getColumnCount" -> cols.size();
                    case "getColumnLabel", "getColumnName" -> cols.get((int) a[0] - 1).name();
                    case "getColumnTypeName" -> cols.get((int) a[0] - 1).typeName() != null
                            ? cols.get((int) a[0] - 1).typeName() : "T" + cols.get((int) a[0] - 1).type();
                    case "getColumnType" -> cols.get((int) a[0] - 1).type();
                    case "getPrecision" -> cols.get((int) a[0] - 1).precision();
                    default -> null;
                });
        int[] cursor = {-1};
        boolean[] wasNull = {false};
        Set<Integer> read = ConcurrentHashMap.newKeySet();
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (p, m, a) -> {
                    String n = m.getName();
                    switch (n) {
                        case "next": return ++cursor[0] < rows.size();
                        case "getMetaData": return md;
                        case "wasNull": return wasNull[0];
                        case "isClosed": return false;
                        case "close": return null;
                        default: break;
                    }
                    if (a != null && a.length >= 1 && a[0] instanceof Integer idx) {
                        read.add(idx);
                        Object v = rows.get(cursor[0])[idx - 1];
                        wasNull[0] = v == null;
                        if (v instanceof Dual d) {
                            return switch (n) {
                                case "getString" -> d.text();
                                case "getTimestamp", "getObject" -> d.object();
                                default -> d.text();
                            };
                        }
                        return switch (n) {
                            case "getString" -> v == null ? null : String.valueOf(v);
                            case "getLong" -> v == null ? 0L : ((Number) v).longValue();
                            case "getDouble" -> v == null ? 0d : ((Number) v).doubleValue();
                            case "getBoolean" -> v != null && (Boolean) v;
                            case "getBigDecimal" -> v == null ? null : new BigDecimal(String.valueOf(v));
                            case "getTimestamp" -> (Timestamp) v;
                            case "getCharacterStream" -> v == null ? null : new StringReader(String.valueOf(v));
                            case "getBinaryStream" -> v == null ? null : new ByteArrayInputStream((byte[]) v);
                            default -> v;
                        };
                    }
                    return null;
                });
        return new Rs(rs, read);
    }
}
