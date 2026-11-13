package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.model.SqlSavedQuery;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import com.sitemonitor.repository.SqlSavedQueryRepository;
import com.sitemonitor.service.SqlPlaygroundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL Playground'un gerçek şema üstündeki sorguları (H2, PostgreSQL kipi): sayfalı geçmiş araması (A4, JPQL + LIKE kaçışı +
 * gün sınırları) ve kayıtlı sorgular (D1: görünürlük, sahiplik, başlık tekilliği, sürüm, değişmeyen güncelleme, kişi sınırı,
 * hassas kolon reddi).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:sqlpgjpa;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
    "spring.datasource.driver-class-name=org.h2.Driver"
})
class SqlPlaygroundJpaTest {

    @Autowired DataSource dataSource;
    @Autowired SqlQueryHistoryRepository historyRepo;
    @Autowired SqlSavedQueryRepository savedRepo;

    private SqlPlaygroundService playground;
    private SqlSavedQueryService saved;

    @BeforeEach
    void setUp() {
        playground = new SqlPlaygroundService(new JdbcTemplate(dataSource), historyRepo);
        saved = new SqlSavedQueryService(savedRepo, playground);
    }

    private void hist(String user, String sql, boolean ok, long ms, String at, Boolean cancelled) {
        SqlQueryHistory h = new SqlQueryHistory();
        h.setExecutedBy(user);
        h.setSqlText(sql);
        h.setSuccess(ok);
        h.setDurationMs(ms);
        h.setExecutedAt(at);
        h.setCancelled(cancelled);
        historyRepo.save(h);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("data");
    }

    // ── A4: sayfalı geçmiş ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A4: kapsam, durum (ok / error / slow), arama (büyük/küçük harf, LIKE kaçışı), İstanbul gün sınırı, sıralama, sayfa")
    void historyPage() {
        // İstanbul 2026-11-12 günü = UTC 2026-11-11T21:00 – 2026-11-12T21:00
        hist("alice", "SELECT * FROM teams", true, 10, "2026-11-11T20:59:59", false);         // İstanbul 11'i
        hist("alice", "SELECT 100% FROM Teams", true, 6000, "2026-11-11T21:00:00", false);   // İstanbul 12'si, yavaş
        hist("alice", "SELECT x_y FROM a", false, 5, "2026-11-12T20:59:59", true);             // İstanbul 12'si, hatalı + durduruldu
        hist("bob", "SELECT 1 FROM teams", true, 1, "2026-11-12T10:00:00", false);

        Map<String, Object> mine = playground.historyPage("alice", null, null, null, null, null, 0, 25);
        assertThat(mine.get("total")).isEqualTo(3L);
        assertThat(data(mine)).extracting(m -> m.get("executed_at"))
                .containsExactly("2026-11-12T20:59:59", "2026-11-11T21:00:00", "2026-11-11T20:59:59");
        assertThat(data(mine).get(0)).containsEntry("cancelled", true).containsEntry("success", false);
        assertThat(mine.get("slow_ms")).isEqualTo(SqlPlaygroundService.SLOW_MS);

        assertThat(playground.historyPage("alice", null, null, "all", null, null, 0, 25).get("total")).isEqualTo(4L);
        assertThat(playground.historyPage("alice", "TEAMS", null, null, null, null, 0, 25).get("total")).isEqualTo(2L);
        assertThat(playground.historyPage("alice", "100%", null, null, null, null, 0, 25).get("total")).isEqualTo(1L);
        assertThat(playground.historyPage("alice", "x_y", null, null, null, null, 0, 25).get("total")).isEqualTo(1L);
        assertThat(playground.historyPage("alice", "x%y", null, null, null, null, 0, 25).get("total")).isEqualTo(0L);
        assertThat(playground.historyPage("alice", null, "ok", null, null, null, 0, 25).get("total")).isEqualTo(2L);
        assertThat(playground.historyPage("alice", null, "error", null, null, null, 0, 25).get("total")).isEqualTo(1L);
        assertThat(playground.historyPage("alice", null, "slow", null, null, null, 0, 25).get("total")).isEqualTo(1L);
        assertThat(playground.historyPage("alice", null, null, null, "2026-11-12", "2026-11-12", 0, 25).get("total")).isEqualTo(2L);
        assertThat(playground.historyPage("alice", null, null, null, "2026-11-11", "2026-11-11", 0, 25).get("total")).isEqualTo(1L);

        Map<String, Object> p1 = playground.historyPage("alice", null, null, null, null, null, 1, 2);
        assertThat(data(p1)).hasSize(1);
        assertThat(p1.get("page")).isEqualTo(1);
        assertThat(playground.historyPage("alice", null, null, null, null, null, 0, 5000).get("size"))
                .isEqualTo(SqlPlaygroundService.HISTORY_MAX_SIZE);
    }

    @Test
    @DisplayName("A4: geçersiz süzgeçler alan hatasıyla 400")
    void historyPageValidation() {
        assertThatThrownBy(() -> playground.historyPage("a", null, "weird", null, null, null, 0, 25))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("status"));
        assertThatThrownBy(() -> playground.historyPage("a", null, null, "everyone", null, null, 0, 25))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("scope"));
        assertThatThrownBy(() -> playground.historyPage("a", null, null, null, "13-11-2026", null, 0, 25))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("from"));
        assertThatThrownBy(() -> playground.historyPage("a", null, null, null, "2026-11-13", "2026-11-12", 0, 25))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("from"));
        assertThatThrownBy(() -> playground.historyPage("a", "x".repeat(201), null, null, null, null, 0, 25))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("q"));
    }

    // ── D1: kayıtlı sorgular ───────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("D1: oluşturma + satır biçimi (etiketler, parametre TÜRLERİ — değer saklanmaz)")
    void createAndRow() {
        SqlSavedQueryService.Saved s = saved.create(body("title", "  Açık   alarmlar ", "sql_text", "SELECT :team AS t",
                "description", "takım", "tags", List.of("Alarm", "alarm", "rapor"), "shared", true,
                "params", Map.of("team", Map.of("type", "number", "value", "gizli-42"))), "alice", "Alice A.");
        SqlSavedQuery e = s.entity();
        assertThat(e.getTitle()).isEqualTo("Açık alarmlar");
        assertThat(e.getParamsJson()).doesNotContain("gizli-42");
        Map<String, Object> row = saved.row(e, "alice");
        assertThat(row).containsEntry("mine", true).containsEntry("can_edit", true).containsEntry("shared", true)
                .containsEntry("owner_name", "Alice A.").containsEntry("version", 0L);
        assertThat(row.get("tags")).isEqualTo(List.of("Alarm", "rapor"));
        assertThat(row.get("params")).isEqualTo(Map.of("team", Map.of("type", "number")));
        assertThat(saved.row(e, "bob")).containsEntry("mine", false).containsEntry("can_edit", false);
    }

    @Test
    @DisplayName("D1: görünürlük — başkasının paylaşılmamışı 404, paylaşılanı okunur ama düzenlenemez / silinemez (403)")
    void visibility() {
        long priv = saved.create(body("title", "Özel", "sql_text", "SELECT 1"), "alice", null).entity().getId();
        long pub = saved.create(body("title", "Ortak", "sql_text", "SELECT 2", "shared", true), "alice", null).entity().getId();
        assertThatThrownBy(() -> saved.get(priv, "bob")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> saved.update(priv, body("title", "x", "version", 0), "bob")).isInstanceOf(NoSuchElementException.class);
        assertThat(saved.get(pub, "bob").getTitle()).isEqualTo("Ortak");
        assertThatThrownBy(() -> saved.update(pub, body("title", "x", "version", 0), "bob")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> saved.delete(pub, "bob")).isInstanceOf(SecurityException.class);

        assertThat(data(saved.list("bob", "all", null, null, 0, 25))).extracting(m -> m.get("title")).containsExactly("Ortak");
        assertThat(data(saved.list("alice", "mine", null, null, 0, 25))).hasSize(2);
        assertThat(data(saved.list("alice", "shared", null, null, 0, 25))).hasSize(1);
        assertThat(data(saved.list("alice", "all", "ORTAK", null, 0, 25))).hasSize(1);
        assertThat(data(saved.list("alice", "all", "SELECT 2", null, 0, 25))).hasSize(1);
        assertThatThrownBy(() -> saved.list("alice", "nobody", null, null, 0, 25)).isInstanceOf(FieldValidationException.class);
        saved.delete(priv, "alice");
        assertThatThrownBy(() -> saved.get(priv, "alice")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("D1: etiket süzgeci tam etiketle eşleşir (alt dize değil)")
    void tagFilter() {
        saved.create(body("title", "A", "sql_text", "SELECT 1", "tags", "rapor,alarm"), "alice", null);
        saved.create(body("title", "B", "sql_text", "SELECT 1", "tags", "raporlama"), "alice", null);
        assertThat(data(saved.list("alice", "mine", null, "rapor", 0, 25))).extracting(m -> m.get("title")).containsExactly("A");
        assertThat(data(saved.list("alice", "mine", null, "RAPOR", 0, 25))).hasSize(1);
    }

    @Test
    @DisplayName("D1: başlık sahip içinde tekil (büyük/küçük harf + boşluk katlanır) → 409; başkasında aynı başlık serbest")
    void titleUnique() {
        saved.create(body("title", "Rapor", "sql_text", "SELECT 1"), "alice", null);
        assertThatThrownBy(() -> saved.create(body("title", " rapor ", "sql_text", "SELECT 1"), "alice", null))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SQL_SAVED_TITLE_EXISTS");
                    assertThat(e.getExtra()).containsEntry("field", "title");
                });
        saved.create(body("title", "Rapor", "sql_text", "SELECT 1"), "bob", null);
    }

    @Test
    @DisplayName("D1: güncelleme — yalnız gönderilen alanlar, değişmeyen gönderim yazmaz, eski sürüm 409, sürümsüz 400")
    void update() {
        long id = saved.create(body("title", "T", "sql_text", "SELECT 1", "description", "d"), "alice", null).entity().getId();
        SqlSavedQueryService.Saved same = saved.update(id, body("title", "T", "description", "d", "version", 0), "alice");
        assertThat(same.changed()).isFalse();
        assertThat(same.entity().getVersion()).isEqualTo(0L);

        SqlSavedQueryService.Saved ch = saved.update(id, body("description", "yeni", "version", 0), "alice");
        assertThat(ch.changed()).isTrue();
        assertThat(ch.fields()).containsExactly("description");
        assertThat(ch.entity().getTitle()).isEqualTo("T");
        assertThat(ch.entity().getSqlText()).isEqualTo("SELECT 1");
        long v = ch.entity().getVersion();
        assertThat(v).isEqualTo(1L);

        assertThatThrownBy(() -> saved.update(id, body("description", "x", "version", 0), "alice"))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("CONCURRENT_MODIFICATION"));
        assertThatThrownBy(() -> saved.update(id, body("description", "x"), "alice"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("version"));
    }

    @Test
    @DisplayName("D1: SQL çalıştırma ile aynı doğrulamadan geçer — yazma / hassas kolon → 400 alan sql_text")
    void sqlValidated() {
        assertThatThrownBy(() -> saved.create(body("title", "x", "sql_text", "DELETE FROM teams"), "alice", null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("sql_text"));
        assertThatThrownBy(() -> saved.create(body("title", "x", "sql_text",
                        "SELECT username FROM app_users WHERE password_hash IS NOT NULL"), "alice", null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getFields()).containsKey("sql_text");
                    assertThat(e.getCode()).isEqualTo("SQL_SENSITIVE_COLUMN");
                });
        assertThatThrownBy(() -> saved.create(body("title", "", "sql_text", "SELECT 1"), "alice", null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("title"));
        assertThatThrownBy(() -> saved.create(body("title", "x".repeat(121), "sql_text", "SELECT 1"), "alice", null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("title"));
        assertThatThrownBy(() -> saved.create(body("title", "x", "sql_text", "SELECT 1", "tags", "a,b,c,d,e,f,g,h,i,j,k"), "alice", null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("tags"));
        // Parametre değeri kayıtta gerekmez (yalnız tür): eksik değer reddedilmez.
        assertThat(saved.create(body("title", "p", "sql_text", "SELECT :x"), "alice", null).changed()).isTrue();
    }

    @Test
    @DisplayName("D1: kişi başına en çok 200 kayıt → 409 SQL_SAVED_LIMIT")
    void perUserLimit() {
        for (int i = 0; i < SqlSavedQueryService.PER_USER_MAX; i++) {
            saved.create(body("title", "q" + i, "sql_text", "SELECT 1"), "heavy", null);
        }
        assertThatThrownBy(() -> saved.create(body("title", "one more", "sql_text", "SELECT 1"), "heavy", null))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("SQL_SAVED_LIMIT"));
    }
}
