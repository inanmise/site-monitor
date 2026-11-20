package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.TeamRepository;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * "Sağlam Kayıt" paketi (2026-11-20) — olay kaydı yazımının sunucu kuralları, H2'de gerçek servis + depo ile:
 * alan adlı doğrulama (yalnız DEĞİŞEN değer), zaman sırası, katı sayılar, kısmi güncelleme, süre hesabı, takım adının
 * veritabanından okunması, arama kalıbının kaçışı, tarih süzgeci doğrulaması, sürüm denetiminin saf kuralı.
 * Eşzamanlılık (gerçek işlem sınırları) {@link IncidentServiceVersionTest}'te.
 */
@DataJpaTest
@Import(IncidentService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IncidentServiceRobustTest {

    @Autowired IncidentService service;
    @Autowired IncidentRecordRepository repo;
    @Autowired TeamRepository teams;
    @Autowired jakarta.persistence.EntityManager em;

    private static Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "Ödeme gecikmesi");
        m.put("occurred_at", "2026-11-18T10:00:00");
        m.put("severity", "HIGH");
        m.put("status", "OPEN");
        m.put("category", "APPLICATION");
        return m;
    }

    private static Map<String, Object> with(Object... kv) {
        Map<String, Object> m = base();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private IncidentRecord create(Object... kv) {
        return service.create(with(kv), "sre1", 1L, 1L);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getFields()).as("alan adı").containsOnlyKeys(field);
                    assertThat(ex.getMessage()).as("ne · neden · ne yapmalı").hasSizeGreaterThan(40);
                });
    }

    // ── Kolon sınırları = entity ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("uzunluk sınırları entity @Column(length) ile aynı (kolona sığmayan değer 400, veritabanı hatası değil)")
    void lengthLimitsMatchEntityColumns() throws Exception {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("title", IncidentService.MAX_TITLE);
        expected.put("errorCode", IncidentService.MAX_CODE);
        expected.put("functionCode", IncidentService.MAX_CODE);
        expected.put("channelCode", IncidentService.MAX_CODE);
        expected.put("service", IncidentService.MAX_LIST);
        expected.put("channel", IncidentService.MAX_LIST);
        expected.put("problemTypes", IncidentService.MAX_LIST);
        expected.put("affectedSystems", IncidentService.MAX_LIST);
        expected.put("runbookUrl", IncidentService.MAX_LIST);
        expected.put("affectedServices", IncidentService.MAX_SHORT);
        expected.put("affectedApp", IncidentService.MAX_SHORT);
        expected.put("tags", IncidentService.MAX_SHORT);
        for (var en : expected.entrySet()) {
            Column c = IncidentRecord.class.getDeclaredField(en.getKey()).getAnnotation(Column.class);
            assertThat(c).as(en.getKey()).isNotNull();
            assertThat(c.length()).as(en.getKey()).isEqualTo(en.getValue());
        }
        assertThat(IncidentService.MAX_MARKDOWN_CHARS)
                .isEqualTo(com.sitemonitor.service.incident.IncidentPostmortemService.MAX_SECTION_CHARS);
    }

    // ── Doğrulama tablosu (yeni kayıt) ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("doğrulama tablosu: her geçersiz değer KENDİ alan adıyla 400; kayıt oluşmaz")
    void validationTable() {
        List<Object[]> bad = new ArrayList<>(Arrays.asList(
                new Object[]{"title", "   ", "title"},
                new Object[]{"title", "x".repeat(256), "title"},
                new Object[]{"title", List.of("a"), "title"},
                new Object[]{"occurred_at", "dün", "occurred_at"},
                new Object[]{"occurred_at", "2026-13-01T00:00:00", "occurred_at"},
                new Object[]{"occurred_at", "1960-01-01T00:00:00", "occurred_at"},
                new Object[]{"occurred_at", "2026-11-18", "occurred_at"},
                new Object[]{"occurred_at", null, "occurred_at"},
                new Object[]{"detected_at", "sonra", "detected_at"},
                new Object[]{"resolved_at", "2101-01-01T00:00:00", "resolved_at"},
                new Object[]{"severity", "URGENT", "severity"},
                new Object[]{"severity", "", "severity"},
                new Object[]{"status", "WAT", "status"},
                new Object[]{"category", "X", "category"},
                new Object[]{"error_code", "E".repeat(101), "error_code"},
                new Object[]{"function_code", "F".repeat(101), "function_code"},
                new Object[]{"channel_code", "C".repeat(101), "channel_code"},
                new Object[]{"service", "s".repeat(501), "service"},
                new Object[]{"channel", "c".repeat(501), "channel"},
                new Object[]{"problem_types", "p".repeat(501), "problem_types"},
                new Object[]{"affected_systems", "a".repeat(501), "affected_systems"},
                new Object[]{"runbook_url", "u".repeat(501), "runbook_url"},
                new Object[]{"affected_services", "a".repeat(256), "affected_services"},
                new Object[]{"affected_app", "a".repeat(256), "affected_app"},
                new Object[]{"tags", "t".repeat(256), "tags"},
                new Object[]{"rca_summary", "r".repeat(20_001), "rca_summary"},
                new Object[]{"description", "d".repeat(20_001), "description"},
                new Object[]{"resolution_steps", "s".repeat(20_001), "resolution_steps"},
                new Object[]{"business_impact", "b".repeat(20_001), "business_impact"},
                new Object[]{"affected_customers", "abc", "affected_customers"},
                new Object[]{"affected_customers", 12.7d, "affected_customers"},
                new Object[]{"affected_customers", -1, "affected_customers"},
                new Object[]{"affected_customers", "12.0", "affected_customers"},
                new Object[]{"affected_customers", "1,250", "affected_customers"},
                new Object[]{"affected_customers", 3_000_000_000L, "affected_customers"},
                new Object[]{"affected_customers", Boolean.TRUE, "affected_customers"},
                new Object[]{"affected_transactions", "1e3", "affected_transactions"},
                new Object[]{"duration_minutes", -5, "duration_minutes"},
                new Object[]{"duration_minutes", "x", "duration_minutes"},
                new Object[]{"error_budget_burn_pct", "abc", "error_budget_burn_pct"},
                new Object[]{"error_budget_burn_pct", -1, "error_budget_burn_pct"},
                new Object[]{"error_budget_burn_pct", "1e3", "error_budget_burn_pct"},
                new Object[]{"error_budget_burn_pct", 2_000_000, "error_budget_burn_pct"},
                new Object[]{"error_budget_burn_pct", Double.NaN, "error_budget_burn_pct"},
                new Object[]{"sla_breached", "yes", "sla_breached"},
                new Object[]{"sla_breached", 1, "sla_breached"},
                new Object[]{"team_id", "abc", "team_id"},
                new Object[]{"team_id", -3, "team_id"},
                new Object[]{"team_id", 0, "team_id"},
                new Object[]{"team_id", 1.5d, "team_id"}));
        long before = repo.count();
        for (Object[] c : bad) {
            Map<String, Object> b = with(c[0], c[1]);
            assertField(() -> service.create(b, "sre1", 1L, 1L), (String) c[2]);
        }
        assertThat(repo.count()).as("hiçbir geçersiz istek kayıt bırakmadı").isEqualTo(before);
    }

    @Test
    @DisplayName("zaman sırası: tespit < oluş → detected_at; çözülme < tespit / oluş → resolved_at (her zaman SONRAKİ alan)")
    void timeOrder() {
        assertField(() -> create("detected_at", "2026-11-18T09:59:00"), "detected_at");
        assertField(() -> create("resolved_at", "2026-11-18T09:00:00"), "resolved_at");
        assertField(() -> create("detected_at", "2026-11-18T10:30:00", "resolved_at", "2026-11-18T10:10:00"), "resolved_at");
        // eşit zamanlar geçerli; ofsetli zaman UTC'ye çevrilip karşılaştırılır
        IncidentRecord ok = create("detected_at", "2026-11-18T10:00:00", "resolved_at", "2026-11-18T13:30:00+03:00");
        assertThat(ok.getResolvedAt()).isEqualTo("2026-11-18T10:30:00");

        // güncellemede oluş zamanını tespitten SONRAYA almak: hata tespit alanına yazılır (istemciyle aynı alan)
        IncidentRecord r = create("detected_at", "2026-11-18T10:05:00");
        assertField(() -> service.update(r.getId(), body("occurred_at", "2026-11-18T10:10:00"), "sre1"), "detected_at");
    }

    @Test
    @DisplayName("kabul edilenler: rakam metni / 12.0 / boş → null; nokta ondalıklı bütçe; TRUE metni; Z / ofset UTC'ye normalleşir")
    void acceptedValues() {
        IncidentRecord e = create("affected_customers", "120", "affected_transactions", 12.0d, "duration_minutes", "",
                "error_budget_burn_pct", "12.5", "sla_breached", "TRUE", "occurred_at", "2026-11-18T10:00:00+03:00",
                "detected_at", "2026-11-18T07:05:00Z", "severity", "high", "title", "  boşluklu  ");
        assertThat(e.getAffectedCustomers()).isEqualTo(120);
        assertThat(e.getAffectedTransactions()).isEqualTo(12);
        assertThat(e.getDurationMinutes()).isNull();
        assertThat(e.getErrorBudgetBurnPct()).isEqualTo(12.5d);
        assertThat(e.getSlaBreached()).isTrue();
        assertThat(e.getOccurredAt()).isEqualTo("2026-11-18T07:00:00");
        assertThat(e.getDetectedAt()).isEqualTo("2026-11-18T07:05:00");
        assertThat(e.getSeverity()).isEqualTo("HIGH");
        assertThat(e.getTitle()).isEqualTo("boşluklu");
    }

    // ── Değişmeyen (eski) değer yeniden doğrulanmaz ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("değişmeyen GEÇERSİZ eski değer yeniden doğrulanmaz: eski istemci tüm alanları geri gönderse de başlık düzenlenir")
    void unchangedInvalidStoredValueIsNotRevalidated() {
        IncidentRecord legacy = new IncidentRecord();
        legacy.setTitle("Eski kayıt");
        legacy.setOccurredAt("eski-biçim");                      // okunamayan eski zaman damgası
        legacy.setDetectedAt("2026-01-01T09:00:00");
        legacy.setResolvedAt("2025-12-31T09:00:00");             // tespitten önce (tutarsız)
        legacy.setSeverity("HIGH");
        legacy.setStatus("OPEN");
        legacy.setCategory("OTHER");
        legacy.setAffectedCustomers(-5);
        legacy.setErrorBudgetBurnPct(-1d);
        legacy.setSlaBreached(false);
        legacy.setDescription("d".repeat(25_000));                 // bugünkü 20 000 sınırından uzun eski metin
        legacy = repo.saveAndFlush(legacy);
        Long id = legacy.getId();

        Map<String, Object> echo = body("title", "Yeni başlık", "occurred_at", "eski-biçim", "detected_at", "2026-01-01T09:00:00",
                "resolved_at", "2025-12-31T09:00:00", "affected_customers", -5, "error_budget_burn_pct", "-1",
                "severity", "HIGH", "status", "OPEN", "category", "OTHER");
        echo.put("description", "d".repeat(25_000));
        IncidentRecord saved = service.update(id, echo, "sre1");
        assertThat(saved.getTitle()).isEqualTo("Yeni başlık");
        assertThat(saved.getOccurredAt()).isEqualTo("eski-biçim");
        assertThat(saved.getAffectedCustomers()).isEqualTo(-5);
        assertThat(saved.getErrorBudgetBurnPct()).isEqualTo(-1d);
        assertThat(saved.getResolvedAt()).isEqualTo("2025-12-31T09:00:00");
        assertThat(saved.getDescription()).hasSize(25_000);

        // aynı eski değeri DEĞİŞTİRMEK doğrulanır
        assertField(() -> service.update(id, body("affected_customers", -6), "sre1"), "affected_customers");
        assertField(() -> service.update(id, body("occurred_at", "yine-bozuk"), "sre1"), "occurred_at");
        // değişen çözülme zamanı tutarsız tespit zamanıyla karşılaştırılır
        assertField(() -> service.update(id, body("resolved_at", "2025-12-31T10:00:00"), "sre1"), "resolved_at");
    }

    @Test
    @DisplayName("geçersiz değerde kayıt HİÇ değişmez (doğrulama yazımdan önce) — önceki alanlar da eski kalır")
    void invalidUpdateChangesNothing() {
        IncidentRecord e = create("affected_customers", 10);
        Map<String, Object> upd = body("title", "yeni başlık", "affected_customers", 20, "tags", "t".repeat(300));
        assertField(() -> service.update(e.getId(), upd, "sre2"), "tags");
        em.flush();
        em.clear();
        IncidentRecord after = repo.findById(e.getId()).orElseThrow();
        assertThat(after.getTitle()).isEqualTo("Ödeme gecikmesi");
        assertThat(after.getAffectedCustomers()).isEqualTo(10);
        assertThat(after.getUpdatedBy()).isEqualTo("sre1");
    }

    // ── Kısmi güncelleme ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kısmi güncelleme: gönderilmeyen anahtar dokunulmaz, açık null temizler, zorunlu alan boşaltılamaz")
    void partialUpdate() {
        IncidentRecord e = create("detected_at", "2026-11-18T10:05:00", "affected_customers", 7, "rca_summary", "neden",
                "tags", "db,pool", "error_budget_burn_pct", 3.5d);
        IncidentRecord a = service.update(e.getId(), body("status", "MITIGATED"), "sre2");
        assertThat(a.getStatus()).isEqualTo("MITIGATED");
        assertThat(a.getDetectedAt()).isEqualTo("2026-11-18T10:05:00");
        assertThat(a.getAffectedCustomers()).isEqualTo(7);
        assertThat(a.getRcaSummary()).isEqualTo("neden");
        assertThat(a.getTags()).isEqualTo("db,pool");

        Map<String, Object> clear = new HashMap<>();
        clear.put("detected_at", null);
        clear.put("affected_customers", null);
        clear.put("rca_summary", null);
        clear.put("error_budget_burn_pct", "");
        IncidentRecord b = service.update(e.getId(), clear, "sre2");
        assertThat(b.getDetectedAt()).isNull();
        assertThat(b.getAffectedCustomers()).isNull();
        assertThat(b.getRcaSummary()).isNull();
        assertThat(b.getErrorBudgetBurnPct()).isNull();
        assertThat(b.getTags()).as("gönderilmedi").isEqualTo("db,pool");

        assertField(() -> service.update(e.getId(), body("title", ""), "sre2"), "title");
        Map<String, Object> nullOccurred = new HashMap<>();
        nullOccurred.put("occurred_at", null);
        assertField(() -> service.update(e.getId(), nullOccurred, "sre2"), "occurred_at");
        assertField(() -> service.update(e.getId(), body("severity", " "), "sre2"), "severity");
    }

    // ── Süre ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("süre: gönderilmezse iki zamandan aşağı yuvarlanmış dakika; açık süre ezilmez; yeniden açılınca hesaplanan süre silinir")
    void durationRules() {
        IncidentRecord a = create("resolved_at", "2026-11-18T10:02:40", "status", "RESOLVED");
        assertThat(a.getDurationMinutes()).as("2 dk 40 sn → 2 (floor)").isEqualTo(2);

        IncidentRecord manual = create("resolved_at", "2026-11-18T11:00:00", "duration_minutes", 99);
        assertThat(manual.getDurationMinutes()).as("açık süre ezilmez").isEqualTo(99);

        IncidentRecord b = service.update(a.getId(), body("resolved_at", "2026-11-18T11:30:00"), "sre1");
        assertThat(b.getDurationMinutes()).as("çözülme değişti → yeniden hesap").isEqualTo(90);
        IncidentRecord c = service.update(a.getId(), body("resolved_at", "2026-11-18T12:00:00", "duration_minutes", 45), "sre1");
        assertThat(c.getDurationMinutes()).as("aynı istekte açık süre → o yazılır").isEqualTo(45);
        IncidentRecord d = service.update(a.getId(), body("title", "yalnız başlık"), "sre1");
        assertThat(d.getDurationMinutes()).as("zamanlar değişmedi → kayıtlı süre kalır").isEqualTo(45);

        // yeniden açma: hesaplanmış süre silinir (istemcinin yuvarladığı değer de hesaplanmış sayılır)
        IncidentRecord r = create("resolved_at", "2026-11-18T10:02:40", "duration_minutes", 3);
        Map<String, Object> reopen = new HashMap<>();
        reopen.put("status", "INVESTIGATING");
        reopen.put("resolved_at", null);
        assertThat(service.update(r.getId(), reopen, "sre1").getDurationMinutes()).isNull();
        // elle girilmiş (hesaptan farklı) süre yeniden açmada KALIR
        IncidentRecord m = create("resolved_at", "2026-11-18T10:30:00", "duration_minutes", 120);
        assertThat(service.update(m.getId(), reopen, "sre1").getDurationMinutes()).isEqualTo(120);

        // eski kayıt: iki zaman var ama süre yok → ilgisiz düzenlemede doldurulur
        IncidentRecord legacy = new IncidentRecord();
        legacy.setTitle("eski");
        legacy.setOccurredAt("2026-11-18T10:00:00");
        legacy.setResolvedAt("2026-11-18T10:45:59");
        legacy.setSeverity("LOW");
        legacy.setStatus("RESOLVED");
        legacy.setCategory("OTHER");
        legacy = repo.saveAndFlush(legacy);
        assertThat(service.update(legacy.getId(), body("title", "eski*"), "sre1").getDurationMinutes()).isEqualTo(45);
    }

    // ── Takım adı veritabanından ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("team_name istemciden ALINMAZ: team_id'nin takımından okunur; takım yoksa null; ad tek başına yok sayılır")
    void teamNameFromDatabase() {
        Team t = new Team();
        t.setName("Ödeme Sistemleri");
        t = teams.saveAndFlush(t);
        IncidentRecord e = create("team_id", t.getId(), "team_name", "Sahte Ad");
        assertThat(e.getTeamId()).isEqualTo(t.getId());
        assertThat(e.getTeamName()).isEqualTo("Ödeme Sistemleri");

        IncidentRecord n = service.update(e.getId(), body("team_name", "Başka Ad"), "sre1");
        assertThat(n.getTeamName()).as("team_id olmadan ad yok sayılır").isEqualTo("Ödeme Sistemleri");

        t.setName("Ödeme Sistemleri (yeni)");
        teams.saveAndFlush(t);
        IncidentRecord renamed = service.update(e.getId(), body("team_id", String.valueOf(t.getId())), "sre1");
        assertThat(renamed.getTeamName()).as("aynı takım → ad tazelenir").isEqualTo("Ödeme Sistemleri (yeni)");

        IncidentRecord missing = service.update(e.getId(), body("team_id", 987_654L, "team_name", "Hayalet"), "sre1");
        assertThat(missing.getTeamId()).isEqualTo(987_654L);
        assertThat(missing.getTeamName()).as("takım satırı yok → ad yok").isNull();

        Map<String, Object> none = new HashMap<>();
        none.put("team_id", "");
        IncidentRecord cleared = service.update(e.getId(), none, "sre1");
        assertThat(cleared.getTeamId()).isNull();
        assertThat(cleared.getTeamName()).isNull();
    }

    @Test
    @DisplayName("toplu aktarım: ad veritabanındaki takımdan; takım satırı yoksa null (PUT ile aynı) — istemcinin adı yazılmaz")
    void transferTeamName() {
        Team t = new Team();
        t.setName("Kart");
        t = teams.saveAndFlush(t);
        IncidentRecord a = create();
        service.transfer(List.of(a.getId()), t.getId(), "Yanlış", "admin");
        assertThat(service.get(a.getId()).getTeamName()).isEqualTo("Kart");
        service.transfer(List.of(a.getId()), 4242L, "Yedek Ad", "admin");
        assertThat(service.get(a.getId()).getTeamName()).isNull();
    }

    // ── Arama kalıbı + tarih süzgeci ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("arama: % _ ! harfiyen aranır (ESCAPE), küçük harf Locale.ROOT; servis / kanal süzgeci de kaçışlı")
    void searchIsEscaped() {
        create("title", "Kapasite %100 doldu");
        create("title", "kuyruk a_b taştı");
        create("title", "kuyruk axb taştı");
        create("title", "Uyarı! disk");
        create("title", "Sade kayıt", "service", "svc_1", "channel", "IVR%");
        var all = PageRequest.of(0, 50);
        // q başlık / servis / kök neden / açıklamada arar (kanalda değil): "%" yalnız başlığında % geçen kaydı bulur
        assertThat(service.list("%", null, null, null, null, null, null, null, null, null, null, null, all).getTotalElements())
                .as("% joker DEĞİL").isEqualTo(1);
        assertThat(service.list("a_b", null, null, null, null, null, null, null, null, null, null, null, all).getTotalElements())
                .as("_ tek karakter joker DEĞİL (axb bulunmaz)").isEqualTo(1);
        assertThat(service.list("!", null, null, null, null, null, null, null, null, null, null, null, all).getTotalElements())
                .isEqualTo(1);
        assertThat(service.list("KAPASITE", null, null, null, null, null, null, null, null, null, null, null, all).getTotalElements())
                .as("büyük harfli arama (Locale.ROOT: I → i)").isEqualTo(1);
        assertThat(service.list(null, null, null, null, "svc_", null, null, null, null, null, null, null, all).getTotalElements())
                .isEqualTo(1);
        assertThat(service.list(null, null, null, null, "svcx", null, null, null, null, null, null, null, all).getTotalElements())
                .isZero();
        assertThat(service.list(null, null, null, null, null, "%", null, null, null, null, null, null, all).getTotalElements())
                .as("kanal süzgecinde % harfiyen").isEqualTo(1);

        assertThat(IncidentService.likePattern(" 100%_a!b ")).isEqualTo("%100!%!_a!!b%");
        assertThat(IncidentService.likePattern("   ")).isNull();
        assertThat(IncidentService.likePattern("TITLE")).isEqualTo("%title%");
    }

    @Test
    @DisplayName("tarih süzgeci: okunamayan since / until → alan adlı 400 (liste ve trend); gün ve Z'li biçim kabul")
    void filterStampValidation() {
        var page = PageRequest.of(0, 10);
        assertField(() -> service.list(null, null, null, null, null, null, "dün", null, null, null, null, null, page), "since");
        assertField(() -> service.list(null, null, null, null, null, null, null, "2026-99-99T00:00:00", null, null, null, null, page), "until");
        assertField(() -> service.trends("x", null, null), "since");
        assertField(() -> service.trends(null, "1960-01-01T00:00:00", null), "until");
        assertThat(IncidentService.filterStamp("2026-06-15", "since")).isEqualTo("2026-06-15");
        assertThat(IncidentService.filterStamp("2026-06-15T10:00:00Z", "since")).isEqualTo("2026-06-15T10:00:00");
        assertThat(IncidentService.filterStamp("2026-06-15T13:00:00+03:00", "until")).isEqualTo("2026-06-15T10:00:00");
        assertThat(IncidentService.filterStamp(" ", "since")).isNull();
        create("occurred_at", "2026-06-15T10:00:00Z");
        assertThat(service.list(null, null, null, null, null, null, "2026-06-15T09:00:00Z", "2026-06-15T11:00:00Z", null, null, null,
                null, page).getTotalElements()).isEqualTo(1);
    }

    // ── Sürüm denetiminin saf kuralı ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("requireVersion: yok / null / boş → denetim yok; aynı → geçer; farklı → 409 + kim / ne zaman; bozuk → alan adlı 400")
    void requireVersionRule() {
        IncidentRecord e = new IncidentRecord();
        e.setVersion(4L);
        e.setUpdatedBy("ayse");
        e.setUpdatedAt("2026-11-20T09:00:00");
        IncidentService.requireVersion(e, body());
        IncidentService.requireVersion(e, body("version", null));
        IncidentService.requireVersion(e, body("version", " "));
        IncidentService.requireVersion(e, body("version", 4));
        IncidentService.requireVersion(e, body("version", "4"));
        IncidentService.requireVersion(e, body("version", 4.0d));
        assertThatThrownBy(() -> IncidentService.requireVersion(e, body("version", 3)))
                .isInstanceOfSatisfying(CodedConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(ex.getExtra()).containsEntry("current_version", 4L)
                            .containsEntry("updated_by", "ayse").containsEntry("updated_at", "2026-11-20T09:00:00");
                    assertThat(ex.getMessage()).contains("ayse");
                });
        for (Object badV : List.<Object>of("abc", -1, 1.5d, Boolean.TRUE, List.of(4))) {
            assertField(() -> IncidentService.requireVersion(e, body("version", badV)), "version");
        }
        // NULL sürümlü (beklenmeyen) kayıt 0 sayılır
        IncidentRecord legacy = new IncidentRecord();
        IncidentService.requireVersion(legacy, body("version", 0));
    }

    @Test
    @DisplayName("minutesBetween: floor / round, negatif ve okunamayan → null")
    void minutesBetweenRule() {
        assertThat(IncidentService.minutesBetween("2026-11-18T10:00:00", "2026-11-18T10:02:40", false)).isEqualTo(2);
        assertThat(IncidentService.minutesBetween("2026-11-18T10:00:00", "2026-11-18T10:02:40", true)).isEqualTo(3);
        assertThat(IncidentService.minutesBetween("2026-11-18T10:00:00", "2026-11-18T09:00:00", false)).isNull();
        assertThat(IncidentService.minutesBetween("bozuk", "2026-11-18T09:00:00", false)).isNull();
        assertThat(IncidentService.minutesBetween(null, "2026-11-18T09:00:00", false)).isNull();
    }
}
