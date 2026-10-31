package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hesap yetki denetimi (r177) — sahte bağlantıyla: PostgreSQL {@code public} şemasının yalnız PUBLIC kaynaklı CREATE'i
 * BİLGİdir (PUBLIC_SCHEMA_CREATE, salt-okur kalır, BLOCK'ta engellemez — ikinci tur O-5); hesaba / rolüne verilmiş ya da
 * başka şemadaki CREATE gerçek yetki (CAN_CREATE); okunamayan sorgu CHECK_FAILED ve tek başına engellemez.
 */
class DbAccountPrivilegesTest {

    private static final FakeJdbc.Col B = new FakeJdbc.Col("b", Types.BOOLEAN, 1);
    private static final FakeJdbc.Col S = new FakeJdbc.Col("s", Types.VARCHAR, 64);

    /** PG_SCHEMA_CREATE satırları {@code [şema, direct, via_public]}; diğer sorgular boş / yetkisiz. */
    private static Connection pg(List<Object[]> schemaRows, boolean failTables) throws SQLException {
        Connection c = mock(Connection.class);
        when(c.setSavepoint()).thenReturn(mock(Savepoint.class));
        Statement st = mock(Statement.class);
        when(c.createStatement()).thenReturn(st);
        Map<String, Object> answers = new java.util.HashMap<>();
        answers.put(DbAccountPrivileges.PG_ROLE, List.<Object[]>of(new Object[] {false, false, false, false}));
        answers.put(DbAccountPrivileges.PG_WRITE_ALL, List.<Object[]>of());
        answers.put(DbAccountPrivileges.PG_SCHEMA_CREATE, schemaRows);
        answers.put(DbAccountPrivileges.PG_DB_CREATE, List.<Object[]>of(new Object[] {false}));
        when(st.executeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.equals(DbAccountPrivileges.PG_TABLE_WRITE)) {
                if (failTables) throw new SQLException("izin yok");
                return FakeJdbc.resultSet(List.of(S), List.of()).resultSet();
            }
            @SuppressWarnings("unchecked") List<Object[]> rows = (List<Object[]>) answers.get(sql);
            if (sql.equals(DbAccountPrivileges.PG_SCHEMA_CREATE))
                return FakeJdbc.resultSet(List.of(S, B, B), rows).resultSet();
            if (sql.equals(DbAccountPrivileges.PG_ROLE)) return FakeJdbc.resultSet(List.of(B, B, B, B), rows).resultSet();
            return FakeJdbc.resultSet(List.of(B), rows).resultSet();
        });
        return c;
    }

    @Test
    void publicSchemaCreateOnlyViaPublicIsInformation() throws Exception {
        DbAccountPrivileges.Result r = DbAccountPrivileges.check(pg(List.<Object[]>of(new Object[] {"public", false, true}), false),
                DbType.POSTGRESQL);
        assertThat(r.checked()).isTrue();
        assertThat(r.readOnly()).isTrue();
        assertThat(r.writable()).isFalse();
        assertThat(r.findings()).extracting(DbAccountPrivileges.Finding::code).containsExactly(DbAccountPrivileges.PUBLIC_SCHEMA_CREATE);
        assertThat(DbAccountPrivileges.storedWritable(DbAccountPrivileges.toJson(r, "2026-10-31T10:00:00"))).isFalse();
        assertThat(DbAccountPrivileges.CODES).contains(DbAccountPrivileges.PUBLIC_SCHEMA_CREATE);
        assertThat(DbAccountPrivileges.INFO_CODES).containsExactly(DbAccountPrivileges.PUBLIC_SCHEMA_CREATE);
    }

    @Test
    void directOrOtherSchemaCreateIsAWritePrivilege() throws Exception {
        // public ama CREATE hesaba / rolüne de verilmiş (ya da sahip)
        DbAccountPrivileges.Result direct = DbAccountPrivileges.check(pg(List.<Object[]>of(new Object[] {"public", true, true}), false),
                DbType.POSTGRESQL);
        assertThat(direct.readOnly()).isFalse();
        assertThat(direct.findings()).extracting(DbAccountPrivileges.Finding::code).containsExactly(DbAccountPrivileges.CAN_CREATE);
        // başka şema PUBLIC'e açık olsa da gerçek yetki
        DbAccountPrivileges.Result other = DbAccountPrivileges.check(pg(List.<Object[]>of(new Object[] {"rapor", false, true},
                new Object[] {"public", false, true}), false), DbType.POSTGRESQL);
        assertThat(other.readOnly()).isFalse();
        assertThat(other.findings()).extracting(DbAccountPrivileges.Finding::detail).containsExactly("schema rapor", "schema public");
        assertThat(other.findings()).extracting(DbAccountPrivileges.Finding::code)
                .containsExactly(DbAccountPrivileges.CAN_CREATE, DbAccountPrivileges.PUBLIC_SCHEMA_CREATE);
    }

    @Test
    void unreadableQueryWithOnlyInformationIsUnknownNotWritable() throws Exception {
        DbAccountPrivileges.Result r = DbAccountPrivileges.check(pg(List.<Object[]>of(new Object[] {"public", false, true}), true),
                DbType.POSTGRESQL);
        assertThat(r.readOnly()).isNull();
        assertThat(r.writable()).isFalse();
        assertThat(r.findings()).extracting(DbAccountPrivileges.Finding::code)
                .containsExactly(DbAccountPrivileges.PUBLIC_SCHEMA_CREATE, DbAccountPrivileges.CHECK_FAILED);
    }
}
