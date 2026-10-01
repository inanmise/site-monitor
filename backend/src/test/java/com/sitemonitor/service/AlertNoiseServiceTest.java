package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Gürültü analizi (2026-09-12, #18): sayım/pay/ortalama süre, flap kuralı (5+ alarm & ort ≤ 10 dk), ısı haritası, kapsam, gün tavanı.
 * Yeniden tasarım (2026-10-01): takım kırılımı, desen sınıfı, öneri motoru (eşikler {@link AlertNoiseService} sabitleri), takım süzgeci,
 * sakin kümede öneri yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertNoiseServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    @Mock AlertEventRepository alertEventRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock AlertStormRepository stormRepo;
    @Mock TeamRepository teamRepo;

    /**
     * En yakın HAFTA İÇİ günün (bugün; cumartesi/pazarsa cuma) belirli İstanbul saati — testler saat/mesai kovalarını
     * (mesai dışı, gece) DETERMİNİSTİK görsün: "şimdi − N saat" gece ya da hafta sonuna denk gelince OFF_HOURS_NOISE
     * önerisi ve skor rastgele değişirdi.
     */
    private static Instant weekdayAt(int istHour) {
        ZonedDateTime z = ZonedDateTime.now(IST).withHour(istHour).withMinute(10).withSecond(0).withNano(0);
        while (z.getDayOfWeek().getValue() >= 6) z = z.minusDays(1);
        return z.toInstant();
    }

    /** Hafta içi 11:00 İstanbul'dan {@code minutesAgo} dakika önce açılmış alarm (mesai içi, gündüz). */
    private static AlertEvent ev(long id, String domain, String type, Long team, int minutesAgo, Integer durMin) {
        AlertEvent e = new AlertEvent(); e.setId(id); e.setDomain(domain); e.setAlertType(type); e.setTeamId(team); e.setAlertLevel("CRITICAL");
        Instant c = weekdayAt(11).minus(minutesAgo, ChronoUnit.MINUTES);
        e.setCreatedAt(ISO.format(c));
        if (durMin != null) { e.setResolved(true); e.setResolvedAt(ISO.format(c.plus(durMin, ChronoUnit.MINUTES))); }
        return e;
    }

    /** Belirli İstanbul saatinde (hafta içi) açılmış alarm — gece kovası testleri için. */
    private static AlertEvent evAtHour(long id, String domain, String type, Long team, int istHour, Integer durMin, boolean acked) {
        AlertEvent e = ev(id, domain, type, team, 0, durMin);
        Instant c = weekdayAt(istHour);
        e.setCreatedAt(ISO.format(c));
        if (durMin != null) e.setResolvedAt(ISO.format(c.plus(durMin, ChronoUnit.MINUTES)));
        e.setAcknowledged(acked);
        return e;
    }

    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); t.setActive(true); return t; }

    /**
     * Olay → {@code findNoiseRowsSince} projeksiyon satırı. Sütun sırası {@link AlertNoiseService.Ev} ile AYNI
     * ({@code AlertNoiseProjectionQueriesTest} gerçek sorguda pinler). Her çağrıda yeniden kurulur (thenAnswer) —
     * testler olayları değiştirip yeniden koşar.
     */
    private static List<Object[]> rows(List<AlertEvent> events) {
        List<Object[]> out = new ArrayList<>();
        for (AlertEvent e : events) out.add(new Object[]{ e.getDomain(), e.getAlertType(), e.getAlertLevel(), e.getCreatedAt(),
                e.getResolvedAt(), e.getResolved(), e.getResolvedSilently(), e.getAcknowledged(), e.getTeamId() });
        return out;
    }

    private void stubEvents(List<AlertEvent> events) {
        when(alertEventRepo.findNoiseRowsSince(anyString())).thenAnswer(inv -> rows(events));
    }

    /** Aktif envanter (alan adı, SY takımı) projeksiyonu. */
    private void stubInventory(CertificateInventory... invs) {
        List<Object[]> out = new ArrayList<>();
        for (CertificateInventory i : invs) out.add(new Object[]{ i.getDomain(), i.getTeamId() });
        when(inventoryRepo.findActiveDomainTeams()).thenReturn(out);
    }

    private AlertNoiseService svc() {
        when(stormRepo.lastStormPerTeam(anyString())).thenReturn(List.of());
        when(teamRepo.findAllById(any())).thenReturn(List.of(team(1L, "Ödeme"), team(2L, "Kart"), team(3L, "Altyapı")));
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(team(3L, "Altyapı"), team(2L, "Kart"), team(1L, "Ödeme")));
        return new AlertNoiseService(alertEventRepo, inventoryRepo, stormRepo, teamRepo);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> out, String key) { return (List<Map<String, Object>>) out.get(key); }

    private static Set<String> codes(Map<String, Object> out) {
        return list(out, "suggestions").stream().map(s -> (String) s.get("code")).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flap: 6 kısa alarm (3 dk) → aday; 3 uzun alarm değil; pay yüzdesi; takım 2 elenir; en çok üstte")
    void noise() {
        CertificateInventory inv = new CertificateInventory(); inv.setDomain("flap.example.com"); inv.setTeamId(1L); inv.setActive(true);
        stubInventory(inv);
        List<AlertEvent> events = new ArrayList<>();
        for (int i = 0; i < 6; i++) events.add(ev(i, "flap.example.com", "HTTP_DOWN", null, 2 + i, 3));   // takımsız ama alan görünür
        for (int i = 10; i < 13; i++) events.add(ev(i, "slow.example.com", "PING_DOWN", 1L, 5 + i, 60));
        events.add(ev(99, "other.example.com", "X", 2L, 1, 1));   // takım 2 → dışarıda
        stubEvents(events);

        AlertNoiseService svc = svc();
        Map<String, Object> out = svc.build(999, t -> t != null && t == 1L);

        assertThat(out).containsEntry("days", 90).containsEntry("total", 9).containsEntry("distinct_targets", 2);
        List<Map<String, Object>> top = list(out, "top");
        assertThat(top.get(0)).containsEntry("domain", "flap.example.com").containsEntry("count", 6).containsEntry("avg_minutes", 3.0);
        assertThat((Double) top.get(0).get("share_pct")).isEqualTo(66.7);
        assertThat(top.get(1)).containsEntry("domain", "slow.example.com").containsEntry("avg_minutes", 60.0);
        List<Map<String, Object>> flap = list(out, "flapping");
        assertThat(flap).hasSize(1);
        assertThat(flap.get(0)).containsEntry("domain", "flap.example.com").containsEntry("suggestion", "raise-confirm");
        @SuppressWarnings("unchecked") Map<String, Object> heat = (Map<String, Object>) out.get("heat");
        @SuppressWarnings("unchecked") List<List<Integer>> rows = (List<List<Integer>>) heat.get("rows");
        assertThat(rows).hasSize(7);
        assertThat(rows.stream().flatMap(List::stream).mapToInt(Integer::intValue).sum()).isEqualTo(9);
        assertThat((Integer) heat.get("peak")).isGreaterThan(0);

        // ── Yeniden tasarım alanları (ekleyici) ──
        assertThat(top.get(0)).containsEntry("pattern", "FLAPPING").containsEntry("monitor_type", "http")
                .containsEntry("team_id", 1L).containsEntry("team_name", "Ödeme").containsEntry("flap_count", 6);
        assertThat(top.get(0).get("last_opened_at")).isNotNull();
        assertThat(top.get(1)).containsEntry("pattern", "NORMAL").containsEntry("median_minutes", 60.0);
        assertThat(out).containsEntry("noisy_targets", 1).containsEntry("flap_targets", 1).containsEntry("flap_alerts", 6);
        assertThat((Integer) out.get("noise_score")).isBetween(60, 100);
        List<Map<String, Object>> hours = list(out, "hours");
        assertThat(hours).hasSize(24);
        assertThat(hours.stream().mapToInt(h -> (int) h.get("count")).sum()).isEqualTo(9);
        // Takım kırılımı: envanter SY takımı takımsız alarmı takım 1'e bağlar → tek satır
        List<Map<String, Object>> teams = list(out, "teams");
        assertThat(teams).hasSize(1);
        assertThat(teams.get(0)).containsEntry("team_id", 1L).containsEntry("team_name", "Ödeme").containsEntry("alerts", 9)
                .containsEntry("noisy_targets", 1).containsEntry("flaps", 6).containsEntry("silent_closes", 0).containsEntry("storms", 0);
        // Seçenekler yalnız görülebilir takımlar
        assertThat(list(out, "team_options")).extracting(o -> o.get("id")).containsExactly(1L);
        // Öneri: FLAPPING → monitör sekmesi + q
        List<Map<String, Object>> sug = list(out, "suggestions");
        assertThat(sug).hasSize(1);
        assertThat(sug.get(0)).containsEntry("code", "FLAPPING").containsEntry("severity", "HIGH").containsEntry("target", "flap.example.com")
                .containsEntry("title_key", "noise.sug.FLAPPING").containsEntry("team_id", 1L);
        @SuppressWarnings("unchecked") Map<String, Object> action = (Map<String, Object>) sug.get(0).get("action");
        assertThat(action).containsEntry("kind", "open_monitor").containsEntry("tab", "http");
        assertThat(action.get("params")).isEqualTo(Map.of("q", "flap.example.com"));
        assertThat(sug.get(0).get("params")).isEqualTo(List.of(6, 3.0));
    }

    @Test
    @DisplayName("takım süzgeci: yalnız o takımın alarmları sayılır; takım kırılımı skora göre sıralı")
    void teamFilter() {
        stubInventory();
        List<AlertEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) events.add(ev(i, "a.example.com", "HTTP_DOWN", 1L, 2 + i, 2));    // takım 1: flap
        for (int i = 10; i < 12; i++) events.add(ev(i, "b.example.com", "PORT_DOWN", 2L, 2 + i, 90)); // takım 2: sakin
        events.add(ev(20, "c.example.com", "PING_DOWN", null, 3, 30));   // takımsız ve envanterde yok → kapsam dışı (eski kural)
        stubEvents(events);
        AlertNoiseService svc = svc();

        Map<String, Object> all = svc.build(7, null, t -> true);
        assertThat(all).containsEntry("total", 7).containsEntry("team_filter", null);
        List<Map<String, Object>> teams = list(all, "teams");
        assertThat(teams).extracting(r -> r.get("team_id")).containsExactly(1L, 2L);   // skor: takım 1 (100) > takım 2 (0)
        assertThat(teams.get(0)).containsEntry("noise_score", 100).containsEntry("alerts", 5);
        assertThat(teams.get(1)).containsEntry("noise_score", 0).containsEntry("alerts", 2).containsEntry("team_name", "Kart");

        Map<String, Object> only2 = svc.build(7, 2L, t -> true);
        assertThat(only2).containsEntry("total", 2).containsEntry("team_filter", 2L).containsEntry("noise_score", 0);
        assertThat(list(only2, "teams")).hasSize(1);
        assertThat(list(only2, "suggestions")).isEmpty();
    }

    @Test
    @DisplayName("sakin küme: 2 uzun alarm → desen NORMAL, öneri yok, skor 0")
    void quiet_noSuggestions() {
        stubInventory();
        stubEvents(List.of(ev(1, "a.example.com", "HTTP_DOWN", 1L, 12, 120), ev(2, "b.example.com", "PORT_DOWN", 1L, 13, 200)));
        Map<String, Object> out = svc().build(7, t -> true);
        assertThat(out).containsEntry("noise_score", 0).containsEntry("noisy_targets", 0);
        assertThat(list(out, "suggestions")).isEmpty();
        assertThat(list(out, "top")).allSatisfy(r -> assertThat(r).containsEntry("pattern", "NORMAL"));
    }

    @Test
    @DisplayName("boş pencere: total 0 → öneri yok, takım satırı yok, hours 24 sıfır")
    void empty() {
        stubInventory();
        stubEvents(List.of());
        Map<String, Object> out = svc().build(7, t -> true);
        assertThat(out).containsEntry("total", 0).containsEntry("noise_score", 0);
        assertThat(list(out, "suggestions")).isEmpty();
        assertThat(list(out, "teams")).isEmpty();
        assertThat(list(out, "hours")).hasSize(24);
    }

    @Test
    @DisplayName("desenler: SLOW (3× *_SLOW), SHORT_OUTAGES (3 alarm, ortanca < 5 dk), REPEAT (7 uzun alarm) — her biri kendi önerisi ve eylemi")
    void patterns() {
        stubInventory();
        List<AlertEvent> events = new ArrayList<>();
        for (int i = 0; i < 3; i++) events.add(ev(i, "slow.example.com", "PING_SLOW", 1L, 2 + i, 30));
        for (int i = 10; i < 13; i++) events.add(ev(i, "short.example.com", "HTTP_DOWN", 1L, 2 + i, i == 12 ? 40 : 2)); // ortanca 2 dk, ort 14.7
        for (int i = 20; i < 27; i++) events.add(ev(i, "repeat.example.com", "PORT_DOWN", 2L, 2 + i, 45));
        stubEvents(events);
        Map<String, Object> out = svc().build(7, t -> true);

        Map<String, String> patterns = list(out, "top").stream().collect(Collectors.toMap(r -> (String) r.get("domain"), r -> (String) r.get("pattern")));
        assertThat(patterns).containsEntry("slow.example.com", "SLOW_THRESHOLD_TIGHT")
                .containsEntry("short.example.com", "SHORT_OUTAGES").containsEntry("repeat.example.com", "REPEAT_SAME_TARGET");
        assertThat(out).containsEntry("noisy_targets", 3).containsEntry("flap_targets", 0);

        List<Map<String, Object>> sug = list(out, "suggestions");
        // HIGH önce; MEDIUM'da eşit sayı (3/3) → kararlı sıralama top'un sırasını korur (slow, short)
        assertThat(sug).extracting(s -> s.get("code")).containsExactly("REPEAT_SAME_TARGET", "SLOW_THRESHOLD_TIGHT", "SHORT_OUTAGES");
        Map<String, Map<String, Object>> byCode = sug.stream().collect(Collectors.toMap(s -> (String) s.get("code"), s -> s));
        @SuppressWarnings("unchecked") Map<String, Object> rep = (Map<String, Object>) byCode.get("REPEAT_SAME_TARGET").get("action");
        assertThat(rep).containsEntry("kind", "open_maintenance").containsEntry("tab", "maintenance");
        assertThat(byCode.get("REPEAT_SAME_TARGET").get("params")).isEqualTo(List.of(7, 7));
        @SuppressWarnings("unchecked") Map<String, Object> slow = (Map<String, Object>) byCode.get("SLOW_THRESHOLD_TIGHT").get("action");
        assertThat(slow).containsEntry("kind", "open_monitor").containsEntry("tab", "ping");
        assertThat(byCode.get("SHORT_OUTAGES").get("params")).isEqualTo(List.of(3, 2.0));
        assertThat(byCode.get("SHORT_OUTAGES")).containsEntry("severity", "MEDIUM");
    }

    @Test
    @DisplayName("DUPLICATE_MONITORS: aynı host'a HTTP + PORT + PING düşük alarmı → tek öneri, kaynak türleri listelenir")
    void duplicateMonitors() {
        stubInventory();
        stubEvents(List.of(
                ev(1, "dup.example.com", "HTTP_DOWN", 1L, 2, 30), ev(2, "dup.example.com", "PORT_DOWN", 1L, 2, 30), ev(3, "dup.example.com", "PING_DOWN", 2L, 2, 30),
                ev(4, "single.example.com", "HTTP_DOWN", 1L, 3, 30)));
        Map<String, Object> out = svc().build(7, t -> true);
        List<Map<String, Object>> sug = list(out, "suggestions");
        assertThat(sug).hasSize(1);
        assertThat(sug.get(0)).containsEntry("code", "DUPLICATE_MONITORS").containsEntry("target", "dup.example.com");
        @SuppressWarnings("unchecked") List<Object> sources = (List<Object>) sug.get(0).get("sources");
        assertThat(sources).containsExactlyInAnyOrder("http", "port", "ping");
        @SuppressWarnings("unchecked") List<Object> teamIds = (List<Object>) sug.get(0).get("team_ids");
        assertThat(teamIds).containsExactlyInAnyOrder(1L, 2L);
        @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) sug.get(0).get("action");
        assertThat(a).containsEntry("kind", "open_alerts").containsEntry("tab", "alerthistory");
    }

    @Test
    @DisplayName("SILENT_CLOSES: 3 sessiz kapanış / 6 alarm (%50) → INFO önerisi; 1 sessiz kapanış → yok")
    void silentCloses() {
        stubInventory();
        List<AlertEvent> events = new ArrayList<>();
        for (int i = 0; i < 3; i++) { AlertEvent e = ev(i, "s" + i + ".example.com", "HTTP_DOWN", 1L, 2 + i, 30); e.setResolvedSilently(true); events.add(e); }
        for (int i = 10; i < 13; i++) events.add(ev(i, "n" + i + ".example.com", "PORT_DOWN", 1L, 2 + i, 30));
        stubEvents(events);
        Map<String, Object> out = svc().build(7, t -> true);
        assertThat(out).containsEntry("silenced_total", 3);
        assertThat(codes(out)).containsExactly("SILENT_CLOSES");
        Map<String, Object> s = list(out, "suggestions").get(0);
        assertThat(s).containsEntry("severity", "INFO");
        assertThat(s.get("params")).isEqualTo(List.of(3, 50));
        assertThat(list(out, "teams").get(0)).containsEntry("silent_closes", 3);

        events.get(1).setResolvedSilently(false); events.get(2).setResolvedSilently(false);
        assertThat(codes(svc().build(7, t -> true))).isEmpty();
    }

    @Test
    @DisplayName("OFF_HOURS_NOISE: 10 alarmın 4'ü gece 02:00'de ve sahiplenilmemiş → öneri (userpush); sahiplenilmişse yok")
    void offHoursNoise() {
        stubInventory();
        List<AlertEvent> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) events.add(evAtHour(i, "night" + i + ".example.com", "HTTP_DOWN", 1L, 2, 60, false));
        for (int i = 10; i < 16; i++) events.add(evAtHour(i, "day" + i + ".example.com", "PORT_DOWN", 1L, 11, 60, true));
        stubEvents(events);
        Map<String, Object> out = svc().build(7, t -> true);
        assertThat(out).containsEntry("night", 4).containsEntry("night_pct", 40.0);
        assertThat(codes(out)).containsExactly("OFF_HOURS_NOISE");
        Map<String, Object> s = list(out, "suggestions").get(0);
        assertThat(s.get("params")).isEqualTo(List.of(40, 100));
        @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) s.get("action");
        assertThat(a).containsEntry("kind", "open_settings").containsEntry("tab", "settings");
        assertThat(a.get("params")).isEqualTo(Map.of("sec", "userpush"));

        for (int i = 0; i < 4; i++) events.get(i).setAcknowledged(true);
        assertThat(codes(svc().build(7, t -> true))).isEmpty();
    }

    @Test
    @DisplayName("STORM_PRONE: takımın pencerede 2 fırtınası → takım satırında storms=2 ve öneri (settings/storm); 1 fırtına → yok")
    void stormProne() {
        stubInventory();
        stubEvents(List.of(ev(1, "a.example.com", "HTTP_DOWN", 1L, 2, 60), ev(2, "b.example.com", "HTTP_DOWN", 2L, 2, 60)));
        AlertNoiseService svc = svc();
        when(stormRepo.lastStormPerTeam(anyString())).thenReturn(List.<Object[]>of(new Object[]{1L, "2026-09-30T10:00:00", 2L}, new Object[]{2L, "2026-09-30T10:00:00", 1L}));
        Map<String, Object> out = svc.build(7, t -> true);
        Map<Object, Integer> storms = list(out, "teams").stream().collect(Collectors.toMap(r -> r.get("team_id"), r -> (Integer) r.get("storms")));
        assertThat(storms).containsEntry(1L, 2).containsEntry(2L, 1);
        List<Map<String, Object>> sug = list(out, "suggestions");
        assertThat(sug).hasSize(1);
        assertThat(sug.get(0)).containsEntry("code", "STORM_PRONE").containsEntry("team_id", 1L).containsEntry("team_name", "Ödeme").containsEntry("count", 2);
        @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) sug.get(0).get("action");
        assertThat(a.get("params")).isEqualTo(Map.of("sec", "storm"));
        assertThat(sug.get(0).get("params")).isEqualTo(List.of(2, 7));
    }

    @Test
    @DisplayName("öneri tavanı: 15 gürültülü hedef → en fazla SUGGESTIONS_MAX hedef önerisi; en çok alarm üreten önce")
    void suggestionCap() {
        stubInventory();
        List<AlertEvent> events = new ArrayList<>(); long id = 0;
        for (int tgt = 0; tgt < 15; tgt++) for (int i = 0; i < 5 + tgt; i++) events.add(ev(id++, "t" + tgt + ".example.com", "HTTP_DOWN", 1L, 2, 3));
        stubEvents(events);
        Map<String, Object> out = svc().build(7, t -> true);
        List<Map<String, Object>> sug = list(out, "suggestions");
        // top yalnız 10 satır taşır → hedef önerileri de en çok 10 (TOP ≤ SUGGESTIONS_MAX)
        assertThat(sug.size()).isLessThanOrEqualTo(AlertNoiseService.SUGGESTIONS_MAX);
        assertThat(sug.get(0)).containsEntry("target", "t14.example.com").containsEntry("count", 19);
        assertThat(sug).allSatisfy(s -> assertThat(s).containsEntry("code", "FLAPPING"));
    }

    @Test
    @DisplayName("performans (2026-10-01): projeksiyon ORDER BY'sız (en eski önce) gelse de sonuç 'en yeni önce' ile aynı — hedefin takımı en yeni olaydan, eşit sayıda en yeni hedef önde")
    void unorderedProjection_keepsNewestFirstSemantics() {
        stubInventory();
        List<AlertEvent> newestFirst = new ArrayList<>(List.of(
                ev(1, "tie-new.example.com", "HTTP_DOWN", 2L, 1, 60),     // en yeni olay: takım 2
                ev(2, "tie-old.example.com", "PORT_DOWN", 1L, 5, 60),
                ev(3, "tie-new.example.com", "HTTP_DOWN", 1L, 30, 60),    // aynı hedefin ESKİ olayı: takım 1
                ev(4, "tie-old.example.com", "PORT_DOWN", 1L, 40, 60)));
        stubEvents(newestFirst);
        Map<String, Object> expected = svc().build(7, t -> true);

        List<AlertEvent> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        stubEvents(oldestFirst);
        Map<String, Object> actual = svc().build(7, t -> true);

        List<Map<String, Object>> top = list(actual, "top");
        assertThat(top).extracting(r -> r.get("domain")).containsExactly("tie-new.example.com", "tie-old.example.com");
        assertThat(top.get(0)).containsEntry("team_id", 2L).containsEntry("count", 2);
        assertThat(top).isEqualTo(list(expected, "top"));
        assertThat(list(actual, "teams")).isEqualTo(list(expected, "teams"));
        assertThat(actual.get("by_type")).isEqualTo(expected.get("by_type"));
    }

    @Test
    @DisplayName("performans (2026-10-01): tam entity/envanter yüklenmez — yalnız dar projeksiyonlar sorgulanır")
    void usesNarrowProjectionsOnly() {
        stubInventory();
        stubEvents(List.of(ev(1, "a.example.com", "HTTP_DOWN", 1L, 2, 60)));
        svc().build(7, t -> true);
        org.mockito.Mockito.verify(alertEventRepo).findNoiseRowsSince(anyString());
        org.mockito.Mockito.verify(alertEventRepo, org.mockito.Mockito.never()).findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString());
        org.mockito.Mockito.verify(inventoryRepo).findActiveDomainTeams();
        org.mockito.Mockito.verify(inventoryRepo, org.mockito.Mockito.never()).findByActiveTrueOrderByDomainAsc();
    }

    @Test
    @DisplayName("skor formülü: tümü flap → 100; hiç gürültü yok → 0; boş → 0")
    void scoreFormula() {
        assertThat(AlertNoiseService.score(10, 10, 0, 0, 0)).isEqualTo(100);
        assertThat(AlertNoiseService.score(10, 0, 0, 0, 0)).isEqualTo(0);
        assertThat(AlertNoiseService.score(0, 0, 0, 0, 0)).isEqualTo(0);
        assertThat(AlertNoiseService.score(10, 0, 5, 0, 0)).isEqualTo(30);
        assertThat(AlertNoiseService.score(10, 0, 0, 5, 5)).isEqualTo(35);
    }

    @Test
    @DisplayName("classify: eşikler sabitlerle uyumlu (sınır değerleri)")
    void classifyBoundaries() {
        assertThat(AlertNoiseService.classify("HTTP_DOWN", 5, 10.0, 10.0)).isEqualTo("FLAPPING");
        assertThat(AlertNoiseService.classify("HTTP_DOWN", 4, 1.0, 1.0)).isEqualTo("SHORT_OUTAGES");
        assertThat(AlertNoiseService.classify("HTTP_DOWN", 3, 5.0, 5.0)).isEqualTo("NORMAL");       // ortanca 5 dk ≥ SHORT_MAX → değil
        assertThat(AlertNoiseService.classify("HTTP_DOWN", 7, 60.0, 60.0)).isEqualTo("REPEAT_SAME_TARGET");
        assertThat(AlertNoiseService.classify("HTTP_DOWN", 6, 60.0, 60.0)).isEqualTo("NORMAL");
        assertThat(AlertNoiseService.classify("PING_SLOW", 3, 60.0, 60.0)).isEqualTo("SLOW_THRESHOLD_TIGHT");
        assertThat(AlertNoiseService.classify("PING_SLOW", 2, 1.0, 1.0)).isEqualTo("NORMAL");
        assertThat(AlertNoiseService.classify("HTTP_DOWN", 5, null, null)).isEqualTo("NORMAL");       // hepsi açık → süre yok
    }

    // ── Isı haritası hücresi ayrıntısı (2026-10-01) ──

    private void stubSlotEvents(List<AlertEvent> events) {
        when(alertEventRepo.findNoiseSlotRowsSince(anyString())).thenAnswer(inv -> {
            List<Object[]> out = new ArrayList<>();
            for (AlertEvent e : events) out.add(new Object[]{ e.getId(), e.getDomain(), e.getAlertType(), e.getAlertLevel(), e.getCreatedAt(),
                    e.getResolvedAt(), e.getResolved(), e.getAcknowledged(), e.getTeamId() });
            return out;
        });
    }

    @Test
    @DisplayName("slot: yalnız o gün × saatte (İstanbul) açılanlar, en yeniden; hücre sayısı = build ısı haritası; görünürlük + takım süzgeci aynı; envanter SY takımı")
    void slot_matchesHeatCell() {
        AlertEvent a = evAtHour(1, "a.example.com", "HTTP_DOWN", 1L, 14, 5, false);
        AlertEvent b = evAtHour(2, "b.example.com", "PING_DOWN", 2L, 14, null, true);
        AlertEvent c = evAtHour(3, "c.example.com", "HTTP_DOWN", 1L, 9, 5, false);    // başka saat
        AlertEvent hidden = evAtHour(4, "x.example.com", "HTTP_DOWN", 9L, 14, 5, false);   // kapsam dışı takım
        AlertEvent inv = evAtHour(5, "inv.example.com", "ACCESSIBILITY", null, 14, 5, false);   // takımsız → envanter SY (1)
        b.setCreatedAt(ISO.format(weekdayAt(14).plus(20, ChronoUnit.MINUTES)));   // aynı saat, daha yeni
        CertificateInventory ci = new CertificateInventory(); ci.setDomain("inv.example.com"); ci.setTeamId(1L);
        stubInventory(ci);
        List<AlertEvent> all = List.of(a, b, c, hidden, inv);
        stubEvents(all);
        stubSlotEvents(all);
        AlertNoiseService svc = svc();
        java.util.function.Predicate<Long> canView = id -> id == null || id == 1L || id == 2L;
        int dow = ZonedDateTime.ofInstant(weekdayAt(14), IST).getDayOfWeek().getValue() - 1;

        Map<String, Object> out = svc.slot(7, null, canView, dow, 14);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).extracting(m -> m.get("id")).containsExactly(2L, 5L, 1L);   // en yeni önce (b +20 dk); a ile inv aynı an → kimlik azalan
        assertThat(out).containsEntry("total", 3).containsEntry("truncated", false).containsEntry("dow", dow).containsEntry("hour", 14);
        assertThat(items.get(0)).containsEntry("team_name", "Kart").containsEntry("acknowledged", true).containsEntry("resolved", false);
        assertThat(items.stream().filter(m -> Long.valueOf(5L).equals(m.get("id"))).findFirst().orElseThrow()).containsEntry("team_id", 1L);

        // Hücre sayısı build'in ısı haritasıyla aynı
        @SuppressWarnings("unchecked") Map<String, Object> heat = (Map<String, Object>) svc.build(7, null, canView).get("heat");
        @SuppressWarnings("unchecked") List<List<Integer>> rows = (List<List<Integer>>) heat.get("rows");
        assertThat(rows.get(dow).get(14)).isEqualTo(3);

        // Takım süzgeci
        Map<String, Object> t1 = svc.slot(7, 1L, canView, dow, 14);
        assertThat(t1).containsEntry("total", 2);
        assertThatThrownBy(() -> svc.slot(7, null, canView, 7, 14)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.slot(7, null, canView, 0, 24)).isInstanceOf(IllegalArgumentException.class);
    }
}
