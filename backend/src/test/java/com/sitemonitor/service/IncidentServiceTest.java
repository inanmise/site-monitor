package com.sitemonitor.service;

import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.IncidentRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * IncidentService — H2'de gerçek filtre/trend sorguları (JPQL SUBSTRING gün gruplaması,
 * LOWER LIKE arama) + validation. @DataJpaTest + servisi @Import ile yükler.
 */
@DataJpaTest
@Import(IncidentService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IncidentServiceTest {

    @Autowired IncidentService service;
    @Autowired IncidentRecordRepository repo;
    @Autowired com.sitemonitor.repository.IncidentImageRepository imageRepo;

    private Map<String, Object> body(String title, String occurredAt, String sev, String cat) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("occurred_at", occurredAt);
        m.put("severity", sev);
        m.put("status", "RESOLVED");
        m.put("category", cat);
        return m;
    }

    @Test
    @DisplayName("create: zorunlu alan eksikse IllegalArgumentException")
    void create_missingRequired_throws() {
        Map<String, Object> b = new HashMap<>();
        b.put("title", "x"); // occurred_at/severity/status/category yok
        assertThatThrownBy(() -> service.create(b, "admin", 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create: geçersiz severity reddedilir")
    void create_invalidSeverity_throws() {
        Map<String, Object> b = body("t", "2026-06-14T10:00:00", "URGENT", "DATABASE");
        assertThatThrownBy(() -> service.create(b, "admin", 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create + filtre: severity / keyword / tarih aralığı")
    void create_and_filter() {
        service.create(body("DB pool tükendi", "2026-06-14T10:00:00", "CRITICAL", "DATABASE"), "admin", 1L, 1L);
        service.create(body("DNS gecikmesi", "2026-06-15T09:00:00", "HIGH", "NETWORK"), "admin", 1L, 1L);
        service.create(body("Sertifika uyarısı", "2026-06-16T08:00:00", "MEDIUM", "CERTIFICATE"), "admin", 1L, 1L);

        // severity filtresi
        assertThat(service.list(null, "CRITICAL", null, null, null, null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
        // keyword (LOWER LIKE — başlık)
        assertThat(service.list("pool", null, null, null, null, null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
        // tarih aralığı (ISO string >= / <=)
        assertThat(service.list(null, null, null, null, null, null, "2026-06-15T00:00:00", "2026-06-16T23:59:59", null, null, null,
                null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        // sla_breached = false (varsayılan) → hepsi
        assertThat(service.list(null, null, null, null, null, null, null, null, null, Boolean.FALSE, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("takım kapsamı (IDOR engeli): scope yalnız o takımın (teamId VEYA createdByTeamId) olaylarını döndürür")
    void list_teamScope() {
        service.create(body("t1 olay", "2026-06-14T10:00:00", "HIGH", "OTHER"), "u1", 10L, 1L); // createdByTeamId=1
        service.create(body("t2 olay", "2026-06-15T10:00:00", "HIGH", "OTHER"), "u2", 20L, 2L); // createdByTeamId=2

        // global (scope=null) → ikisi de görünür
        assertThat(service.list(null, null, null, null, null, null, null, null, null, null, null,
                null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        // scope=[1] → yalnız team 1'in olayı
        assertThat(service.list(null, null, null, null, null, null, null, null, null, null, null,
                java.util.List.of(1L), PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        // scope=[2] → yalnız team 2'nin olayı
        assertThat(service.list(null, null, null, null, null, null, null, null, null, null, null,
                java.util.List.of(2L), PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        // scope=[] (kapsamsız kullanıcı) → hiçbiri (başka takımın kaydı sızmaz)
        assertThat(service.list(null, null, null, null, null, null, null, null, null, null, null,
                java.util.List.of(), PageRequest.of(0, 20)).getTotalElements()).isEqualTo(0);

        // trend/özet de kapsamlı: global → total 2, scope=[1] → 1, boş kapsam → 0
        assertThat(trendTotal(service.trends(null, null, null))).isEqualTo(2L);
        assertThat(trendTotal(service.trends(null, null, java.util.List.of(1L)))).isEqualTo(1L);
        assertThat(trendTotal(service.trends(null, null, java.util.List.of()))).isEqualTo(0L);
    }

    @SuppressWarnings("unchecked")
    private static long trendTotal(Map<String, Object> trends) {
        return ((Number) ((Map<String, Object>) trends.get("summary")).get("total")).longValue();
    }

    @Test
    @DisplayName("kanal: filtre + options union (kayıtlı + kullanılan) + addOption")
    void channel_filter_and_options() {
        Map<String, Object> b1 = body("Bireysel giriş hatası", "2026-06-14T10:00:00", "HIGH", "APPLICATION");
        b1.put("channel", "Bireysel İnternet Şubesi");
        b1.put("service", "ib-bireysel");
        service.create(b1, "admin", 1L, 1L);
        Map<String, Object> b2 = body("IVR menü", "2026-06-15T09:00:00", "LOW", "APPLICATION");
        b2.put("channel", "IVR");
        service.create(b2, "admin", 1L, 1L);

        // kanal filtresi
        assertThat(service.list(null, null, null, null, null, "IVR", null, null, null, null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);

        // addOption + listOptions union: eklenen + olaylarda kullanılan kanallar
        service.addOption("CHANNEL", "ATM", "admin", 1L);
        var channels = service.listOptions("CHANNEL", 1L, false);
        assertThat(channels).contains("ATM", "IVR", "Bireysel İnternet Şubesi");

        // DOMAIN union: kullanılan service değerleri
        assertThat(service.listOptions("DOMAIN", 1L, false)).contains("ib-bireysel");

        // trend by_channel
        @SuppressWarnings("unchecked")
        var byCh = (Map<String, Long>) service.trends(null, null, null).get("by_channel");
        assertThat(byCh.get("IVR")).isEqualTo(1L);
    }

    @Test
    @DisplayName("kanal/servis CSV (çoklu): filtre CSV içinde bulur, trend+options tekile böler")
    void channel_service_multi_csv() {
        Map<String, Object> b = body("çoklu kanal", "2026-06-16T10:00:00", "HIGH", "APPLICATION");
        b.put("channel", "ATM, IVR");
        b.put("service", "svc-a, svc-b");
        service.create(b, "admin", 1L, 1L);

        // CSV içinde geçen kanalı/servisi filtrele (LIKE)
        assertThat(service.list(null, null, null, null, null, "ATM", null, null, null, null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
        assertThat(service.list(null, null, null, null, null, "IVR", null, null, null, null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
        assertThat(service.list(null, null, null, null, "svc-b", null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);

        // options: combo değil TEKİL değerler
        assertThat(service.listOptions("CHANNEL", 1L, false)).contains("ATM", "IVR").doesNotContain("ATM, IVR");
        assertThat(service.listOptions("DOMAIN", 1L, false)).contains("svc-a", "svc-b").doesNotContain("svc-a, svc-b");

        // trend by_channel: combo yerine tekil kanallar sayılır
        @SuppressWarnings("unchecked")
        var byCh = (Map<String, Long>) service.trends(null, null, null).get("by_channel");
        assertThat(byCh.get("ATM")).isEqualTo(1L);
        assertThat(byCh.get("IVR")).isEqualTo(1L);
        assertThat(byCh).doesNotContainKey("ATM, IVR");
    }

    @Test
    @DisplayName("open filtresi: true=çözülmemiş, false=çözülmüş")
    void open_filter() {
        Map<String, Object> a = body("açık kayıt", "2026-06-14T10:00:00", "HIGH", "OTHER");
        a.put("status", "OPEN");
        service.create(a, "admin", 1L, 1L);
        service.create(body("çözülmüş kayıt", "2026-06-15T10:00:00", "LOW", "OTHER"), "admin", 1L, 1L); // body → RESOLVED
        assertThat(service.list(null, null, null, null, null, null, null, null, null, null, Boolean.TRUE, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
        assertThat(service.list(null, null, null, null, null, null, null, null, null, null, Boolean.FALSE, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("addOption: geçersiz tip reddedilir, boş değer reddedilir")
    void addOption_validation() {
        assertThatThrownBy(() -> service.addOption("WAT", "x", "admin", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addOption("CHANNEL", "  ", "admin", 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("trends: günlük seri + severity kırılımı + özet")
    void trends_aggregates() {
        service.create(body("a", "2026-06-14T10:00:00", "CRITICAL", "DATABASE"), "admin", 1L, 1L);
        service.create(body("b", "2026-06-14T12:00:00", "HIGH", "NETWORK"), "admin", 1L, 1L);
        service.create(body("c", "2026-06-15T09:00:00", "CRITICAL", "DATABASE"), "admin", 1L, 1L);

        Map<String, Object> tr = service.trends(null, null, null);

        @SuppressWarnings("unchecked")
        var daily = (java.util.List<Map<String, Object>>) tr.get("daily");
        assertThat(daily).hasSize(2); // 06-14 ve 06-15
        // Günlük ÖNEM kırılımı (çubuk grafiği yığılmış renkler için) — 06-14: 1 critical + 1 high, 06-15: 1 critical
        assertThat(daily.get(0).get("day")).isEqualTo("2026-06-14");
        assertThat(((Number) daily.get(0).get("count")).longValue()).isEqualTo(2L);
        assertThat(((Number) daily.get(0).get("critical")).longValue()).isEqualTo(1L);
        assertThat(((Number) daily.get(0).get("high")).longValue()).isEqualTo(1L);
        assertThat(((Number) daily.get(0).get("medium")).longValue()).isEqualTo(0L);
        assertThat(daily.get(1).get("day")).isEqualTo("2026-06-15");
        assertThat(((Number) daily.get(1).get("critical")).longValue()).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        var bySev = (Map<String, Long>) tr.get("by_severity");
        assertThat(bySev.get("CRITICAL")).isEqualTo(2L);
        assertThat(bySev.get("HIGH")).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) tr.get("summary");
        assertThat(((Number) summary.get("total")).longValue()).isEqualTo(3L);
        assertThat(((Number) summary.get("critical")).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("update: kısmi alan günceller, geçersiz status reddeder")
    void update_partial() {
        IncidentRecord e = service.create(body("a", "2026-06-14T10:00:00", "LOW", "OTHER"), "admin", 1L, 1L);
        Map<String, Object> upd = new HashMap<>();
        upd.put("status", "MITIGATED");
        upd.put("rca_summary", "Bağlantı havuzu büyütüldü");
        IncidentRecord saved = service.update(e.getId(), upd, "sre1");
        assertThat(saved.getStatus()).isEqualTo("MITIGATED");
        assertThat(saved.getRcaSummary()).isEqualTo("Bağlantı havuzu büyütüldü");
        assertThat(saved.getSeverity()).isEqualTo("LOW"); // dokunulmadı

        Map<String, Object> bad = new HashMap<>();
        bad.put("status", "WAT");
        assertThatThrownBy(() -> service.update(e.getId(), bad, "sre1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("transfer: seçili kayıtların takımını değiştirir; boş id / null takım → 0")
    void transfer_changesTeam() {
        IncidentRecord a = service.create(body("a", "2026-06-14T10:00:00", "LOW", "OTHER"), "admin", 1L, 1L);
        IncidentRecord b = service.create(body("b", "2026-06-15T10:00:00", "LOW", "OTHER"), "admin", 1L, 1L);

        int n = service.transfer(List.of(a.getId(), b.getId()), 99L, "Yeni Takım", "admin");
        assertThat(n).isEqualTo(2);
        assertThat(service.get(a.getId()).getTeamId()).isEqualTo(99L);
        assertThat(service.get(a.getId()).getTeamName()).isEqualTo("Yeni Takım");
        assertThat(service.get(b.getId()).getTeamId()).isEqualTo(99L);

        assertThat(service.transfer(List.of(), 99L, "X", "admin")).isZero();
        assertThat(service.transfer(List.of(a.getId()), null, "X", "admin")).isZero();
    }

    @Test
    @DisplayName("removeOption: kayıtlı seçeneği siler (kullanımdaki union etkilenmez)")
    void removeOption_removes() {
        service.addOption("CHANNEL", "Geçici Kanal", "admin", 1L);
        assertThat(service.listOptions("CHANNEL", 1L, false)).contains("Geçici Kanal");

        service.removeOption("CHANNEL", "geçici kanal", 1L, false); // büyük/küçük harf duyarsız
        assertThat(service.listOptions("CHANNEL", 1L, false)).doesNotContain("Geçici Kanal");

        // boş/null değer no-op (exception fırlatmaz)
        service.removeOption("CHANNEL", "  ", 1L, false);
    }

    @Test
    @DisplayName("trends: günlük seri Europe/Istanbul YEREL gününe göre (gece-yarısı UTC kayması düzeltildi)")
    void trends_dailyBucketedByIstanbul() {
        // 2026-06-18T22:00:00 UTC = 2026-06-19T01:00:00 IST → trend 19'da olmalı (18'de DEĞİL)
        service.create(body("gece olayı", "2026-06-18T22:00:00", "HIGH", "OTHER"), "admin", 1L, 1L);

        @SuppressWarnings("unchecked")
        var daily = (List<Map<String, Object>>) service.trends(null, null, null).get("daily");
        assertThat(daily).hasSize(1);
        assertThat(daily.get(0).get("day")).isEqualTo("2026-06-19");
    }

    @Test
    @DisplayName("trends: by_status kırılımı + resolved_within_sla (RESOLVED & SLA ihlali yok)")
    void trends_byStatusAndResolvedWithinSla() {
        Map<String, Object> inv = body("inceleniyor", "2026-06-20T10:00:00", "HIGH", "OTHER");
        inv.put("status", "INVESTIGATING");
        service.create(inv, "admin", 1L, 1L);
        service.create(body("sla içinde", "2026-06-20T11:00:00", "LOW", "OTHER"), "admin", 1L, 1L); // RESOLVED, sla=false
        Map<String, Object> breached = body("sla ihlal", "2026-06-20T12:00:00", "CRITICAL", "OTHER");
        breached.put("sla_breached", true);
        service.create(breached, "admin", 1L, 1L); // RESOLVED + ihlal

        Map<String, Object> tr = service.trends(null, null, null);
        @SuppressWarnings("unchecked")
        var byStatus = (Map<String, Long>) tr.get("by_status");
        assertThat(byStatus.get("INVESTIGATING")).isEqualTo(1L);
        assertThat(byStatus.get("RESOLVED")).isEqualTo(2L);

        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) tr.get("summary");
        // 2 RESOLVED'dan yalnız 1'i SLA içinde (diğeri ihlal)
        assertThat(((Number) summary.get("resolved_within_sla")).longValue()).isEqualTo(1L);
    }

    // ── Görsel sahipliği (prod kapısı 2026-09-25, Y-1) ─────────────────────────────────────────
    // linkImages eskiden markdown'da geçen HER görsel id'sini kaydedilen olaya taşıyordu: başka
    // takımın olayındaki görselin id'si açıklamaya yazılınca görsel yeni olaya "sahipleniliyordu".

    private com.sitemonitor.model.IncidentImage image(Long incidentId, String createdBy) {
        com.sitemonitor.model.IncidentImage img = new com.sitemonitor.model.IncidentImage();
        img.setIncidentId(incidentId);
        img.setContentType("image/png");
        img.setSizeBytes(3L);
        img.setData(new byte[]{1, 2, 3});
        img.setCreatedBy(createdBy);
        img.setCreatedAt("2026-09-25T10:00:00");
        return imageRepo.save(img);
    }

    private static String ref(com.sitemonitor.model.IncidentImage img) {
        return "![g](/api/incidents/images/" + img.getId() + ")";
    }

    @Test
    @DisplayName("Y-1: create yalnız AKTÖRÜN taslağını bağlar — başka olayın görseli ve başkasının taslağı yerinde kalır")
    void create_linksOnlyOwnDraftImages() {
        IncidentRecord foreign = service.create(body("B takımı olayı", "2026-09-20T10:00:00", "HIGH", "OTHER"), "u2", 20L, 2L);
        var foreignImg = image(foreign.getId(), "u2");   // B takımının olayına bağlı görsel
        var ownDraft = image(null, "u1");                // aktörün taslağı
        var otherDraft = image(null, "u2");              // başka kullanıcının taslağı

        Map<String, Object> b = body("A takımı olayı", "2026-09-21T10:00:00", "HIGH", "OTHER");
        b.put("description", ref(ownDraft) + "\n" + ref(foreignImg) + "\n" + ref(otherDraft));
        IncidentRecord mine = service.create(b, "u1", 10L, 1L);

        assertThat(imageRepo.findById(ownDraft.getId()).orElseThrow().getIncidentId()).isEqualTo(mine.getId());
        assertThat(imageRepo.findById(foreignImg.getId()).orElseThrow().getIncidentId())
                .as("başka olayın görseli taşınmamalı").isEqualTo(foreign.getId());
        assertThat(imageRepo.findById(otherDraft.getId()).orElseThrow().getIncidentId())
                .as("başka kullanıcının taslağı sahiplenilmemeli").isNull();
    }

    @Test
    @DisplayName("Y-1: update yolu da başka olayın görselini taşımaz")
    void update_doesNotMoveForeignIncidentImage() {
        IncidentRecord foreign = service.create(body("B takımı olayı", "2026-09-20T10:00:00", "HIGH", "OTHER"), "u2", 20L, 2L);
        var foreignImg = image(foreign.getId(), "u2");
        IncidentRecord mine = service.create(body("A takımı olayı", "2026-09-21T10:00:00", "HIGH", "OTHER"), "u1", 10L, 1L);

        Map<String, Object> upd = new HashMap<>();
        upd.put("rca_summary", "bkz. " + ref(foreignImg));
        service.update(mine.getId(), upd, "u1");

        assertThat(imageRepo.findById(foreignImg.getId()).orElseThrow().getIncidentId()).isEqualTo(foreign.getId());
    }

    @Test
    @DisplayName("trends: last_30d sabit pencere — eski olay hariç, son 30 gün içindeki sayılır")
    void trends_last30dWindow() {
        var fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        String recent = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(3).format(fmt);
        service.create(body("eski", "2019-01-01T10:00:00", "LOW", "OTHER"), "admin", 1L, 1L);
        service.create(body("yakın", recent, "LOW", "OTHER"), "admin", 1L, 1L);

        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) service.trends(null, null, null).get("summary");
        assertThat(((Number) summary.get("last_30d")).longValue()).isEqualTo(1L); // yalnız "yakın"
        assertThat(((Number) summary.get("last_7d")).longValue()).isEqualTo(1L);  // 3 gün önce → 7 gün içinde
        assertThat(((Number) summary.get("today")).longValue()).isEqualTo(0L);    // 3 gün önce → bugün değil
    }

    @Test
    @DisplayName("trends: MTTR = çözülmüş + süreli olayların ortalaması; açık / süresiz olay girmez, kapsam uygulanır")
    void trends_mttrFromResolvedDurations() {
        Map<String, Object> a = body("çözüldü 60", "2026-06-20T10:00:00", "HIGH", "OTHER");
        a.put("duration_minutes", 60);
        service.create(a, "admin", 1L, 1L);
        Map<String, Object> b = body("çözüldü 121", "2026-06-21T10:00:00", "LOW", "OTHER");
        b.put("duration_minutes", 121);
        service.create(b, "admin", 1L, 1L);
        Map<String, Object> open = body("açık ama süreli", "2026-06-22T10:00:00", "LOW", "OTHER");
        open.put("status", "OPEN");
        open.put("duration_minutes", 5000);            // çözülmemiş → MTTR'a girmez
        service.create(open, "admin", 1L, 1L);
        service.create(body("çözüldü süresiz", "2026-06-23T10:00:00", "LOW", "OTHER"), "admin", 1L, 1L); // süre yok → girmez
        Map<String, Object> other = body("başka takım", "2026-06-24T10:00:00", "LOW", "OTHER");
        other.put("duration_minutes", 10);
        other.put("team_id", 2);
        service.create(other, "admin", 2L, 2L);

        @SuppressWarnings("unchecked")
        var all = (Map<String, Object>) service.trends(null, null, null).get("summary");
        assertThat(((Number) all.get("mttr_sample")).longValue()).isEqualTo(3L);
        assertThat(((Number) all.get("mttr_minutes")).longValue()).isEqualTo(64L);   // (60 + 121 + 10) / 3 = 63.67 → 64

        @SuppressWarnings("unchecked")
        var team1 = (Map<String, Object>) service.trends(null, null, List.of(1L)).get("summary");
        assertThat(((Number) team1.get("mttr_sample")).longValue()).isEqualTo(2L);
        assertThat(((Number) team1.get("mttr_minutes")).longValue()).isEqualTo(91L); // (60 + 121) / 2 = 90.5 → 91

        @SuppressWarnings("unchecked")
        var ranged = (Map<String, Object>) service.trends("2026-06-21T00:00:00", "2026-06-21T23:59:59", null).get("summary");
        assertThat(((Number) ranged.get("mttr_minutes")).longValue()).isEqualTo(121L);
    }

    @Test
    @DisplayName("trends: çözülmüş-süreli olay yoksa MTTR null (uydurma 0 yok), örneklem 0")
    void trends_mttrNullWhenNoSample() {
        Map<String, Object> open = body("açık", "2026-06-20T10:00:00", "HIGH", "OTHER");
        open.put("status", "OPEN");
        service.create(open, "admin", 1L, 1L);
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) service.trends(null, null, null).get("summary");
        assertThat(summary).containsKey("mttr_minutes");
        assertThat(summary.get("mttr_minutes")).isNull();
        assertThat(((Number) summary.get("mttr_sample")).longValue()).isEqualTo(0L);
        assertThat(((Number) summary.get("total")).longValue()).isEqualTo(1L);
    }
}
