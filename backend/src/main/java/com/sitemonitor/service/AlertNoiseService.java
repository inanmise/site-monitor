package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;

/**
 * Alarm gürültü analizi (2026-09-12, zenginleştirme #18): son N günde en çok alarm üreten hedefler,
 * gün × saat ısı haritası (İstanbul), "flap" adayları (çok sayıda kısa alarm → eşik / onay sayısı
 * ayarı önerisi). Kapsam çağıranın predicate'i; tek repo sorgusu (createdAt ≥ cutoff).
 *
 * <p><b>Yeniden tasarım (2026-10-01):</b> aynı uca EKLEYİCİ alanlar — takım kırılımı ({@code teams}), hedef
 * satırlarında desen sınıfı + takım + ortanca süre + son açılış ({@code top[*].pattern …}), saat kovaları
 * ({@code hours}), gürültü skoru ({@code noise_score}) ve sunucuda hesaplanan çözüm önerileri
 * ({@code suggestions[]}, kodlar {@link AlertNoiseSuggestion}; metin arayüzde). İsteğe bağlı takım süzgeci
 * ({@code teamFilter}): olayın takımı ({@code AlertEvent.teamId}, yoksa envanterin SY takımı) o takımsa sayılır.
 * Eski alanların adı ve anlamı DEĞİŞMEDİ (eski test aynen geçer).
 *
 * <p><b>Performans (2026-10-01):</b> 90 güne kadar TAM {@code AlertEvent} entity'si (5 TEXT sütun) ve tam envanter
 * entity'leri yüklenirdi. Şimdi dar projeksiyonlar: alarmlar {@code findNoiseRowsSince} (yalnız okunan 9 alan, ORDER BY
 * yok — eski "en yeni önce" sırası bellekte kararlı sıralamayla korunur, eşitlik bozma/ilk-görülen takım aynı kalır),
 * envanter {@code findActiveDomainTeams} (alan adı + SY takımı). Tüm {@code build} tek salt-okunur işlemde.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertNoiseService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final int MAX_DAYS = 90, TOP = 10, FLAP_MIN_ALERTS = 5, FLAP_MAX_AVG_MINUTES = 10;

    // ── Öneri eşikleri (2026-10-01) — tek yer; testler bu sabitleri referans alır ──
    /** SHORT_OUTAGES: en az bu kadar alarm ve ortanca kapanma süresi bu dakikanın ALTINDA. */
    static final int SHORT_MIN_ALERTS = 3, SHORT_MAX_MEDIAN_MINUTES = 5;
    /** REPEAT_SAME_TARGET: pencere içinde aynı hedef + tip için en az bu kadar alarm. */
    static final int REPEAT_MIN_ALERTS = 7;
    /** SLOW_THRESHOLD_TIGHT: {@code *_SLOW} tipinde en az bu kadar alarm. */
    static final int SLOW_MIN_ALERTS = 3;
    /** DUPLICATE_MONITORS: aynı host için "düşük" sınıfı alarm üreten en az bu kadar FARKLI izleme türü. */
    static final int DUP_MIN_SOURCES = 2;
    /** SILENT_CLOSES: en az bu kadar sessiz kapanış VE toplamın en az bu yüzdesi. */
    static final int SILENT_MIN = 3, SILENT_MIN_PCT = 20;
    /** OFF_HOURS_NOISE: en az bu kadar alarm, gece (22–06 İstanbul) payı ≥ %, gece alarmlarının sahiplenilmeyen payı ≥ %. */
    static final int NIGHT_MIN_ALERTS = 10, NIGHT_MIN_PCT = 30, NIGHT_UNACKED_MIN_PCT = 80;
    /** STORM_PRONE: takımın pencere içinde açtığı fırtına sayısı en az bu kadar. */
    static final int STORM_MIN = 2;
    /** Öneri listesi tavanı (hedef bazlı öneriler en çok alarm üretenden başlar). */
    static final int SUGGESTIONS_MAX = 12;

    static final String PATTERN_NORMAL = "NORMAL";
    /** "Düşük" sınıfı alarm tipleri — aynı host'u birden çok türün izlediğini bunlarla anlarız. */
    private static final Set<String> DOWN_CLASS = Set.of("ACCESSIBILITY", "HTTP_DOWN", "PORT_DOWN", "PING_DOWN",
            "DNS_FAILURE", "KEYWORD", "PAGE_DOWN", "PAGESPEED_DOWN", "SCRIPTED_FAIL");

    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final AlertStormRepository stormRepo;
    private final TeamRepository teamRepo;

    /** Eski imza — takım süzgeci yok. */
    public Map<String, Object> build(int days, Predicate<Long> canViewTeam) {
        return build(days, null, canViewTeam);
    }

    /**
     * Analizin okuduğu alarm alanları — {@code AlertEventRepository.findNoiseRowsSince} sütun sırası:
     * {@code [domain, alertType, alertLevel, createdAt, resolvedAt, resolved, resolvedSilently, acknowledged, teamId]}.
     */
    record Ev(String domain, String alertType, String alertLevel, String createdAt, String resolvedAt,
              Boolean resolved, Boolean resolvedSilently, Boolean acknowledged, Long teamId) {
        static Ev of(Object[] r) {
            return new Ev(str(r[0]), str(r[1]), str(r[2]), str(r[3]), str(r[4]),
                    bool(r[5]), bool(r[6]), bool(r[7]), r[8] instanceof Number n ? n.longValue() : null);
        }
        private static String str(Object v) { return v == null ? null : String.valueOf(v); }
        private static Boolean bool(Object v) { return v instanceof Boolean b ? b : null; }
    }

    /** "En yeni önce" (eski {@code ORDER BY createdAt DESC}); null açılış en sona, eşitlikte okuma sırası korunur. */
    private static final Comparator<Ev> NEWEST_FIRST =
            Comparator.comparing(Ev::createdAt, Comparator.nullsLast(Comparator.<String>reverseOrder()));

    /** Hedef başına biriktirici (tek geçiş). */
    private static final class Target {
        int count, resolved, stillOpen, acked, night;
        double minutesSum;
        final List<Double> durations = new ArrayList<>();
        String type, lastOpened;
        Long teamId;
    }

    /** Takım başına biriktirici. */
    private static final class TeamAcc {
        int alerts, critical, stillOpen, silent, offHours, flapAlerts, noisyAlerts, noisyTargets, flapTargets, storms;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> build(int days, Long teamFilter, Predicate<Long> canViewTeam) {
        int d = Math.max(1, Math.min(MAX_DAYS, days));
        Set<String> domains = new HashSet<>();
        Map<String, Long> domainTeam = new HashMap<>();
        try {
            for (Object[] r : inventoryRepo.findActiveDomainTeams()) {
                if (r == null || r.length < 2) continue;
                String domain = r[0] == null ? null : String.valueOf(r[0]);
                Long teamId = r[1] instanceof Number n ? n.longValue() : null;
                if (canViewTeam.test(teamId)) { domains.add(domain); if (teamId != null) domainTeam.put(domain, teamId); }
            }
        } catch (Exception e) { log.debug("noise: envanter okunamadı: {}", e.toString()); }

        // Pencere ile kovalar AYNI takvimden: olaylar UTC kayan pencereden çekilirken kovalar
        // LocalDate.now(IST) ile kuruluyordu ve :81 computeIfPresent kullandığı için haritada
        // olmayan gün SESSİZCE düşüyordu. d=7, 13:00 IST: since = 6 gün önce 10:00Z, kovalar
        // bugün dâhil son 7 IST günü → o gün 10:00Z-21:00Z arasında açılan alarmlar total'e
        // giriyor ama series'ten düşüyordu (KPI 120, kıvılcım çizgisinin toplamı 97).
        String since = ISO.format(LocalDate.now(IST).minusDays(d - 1L).atStartOfDay(IST).toInstant());
        List<Ev> events = new ArrayList<>();
        for (Object[] r : alertEventRepo.findNoiseRowsSince(since)) {
            if (r == null || r.length < 9) continue;
            Ev e = Ev.of(r);
            boolean vis = (e.teamId() != null && canViewTeam.test(e.teamId())) || (e.domain() != null && domains.contains(e.domain()));
            if (!vis) continue;
            if (teamFilter != null && !teamFilter.equals(teamOf(e, domainTeam))) continue;
            events.add(e);
        }
        events.sort(NEWEST_FIRST);   // kararlı: eski ORDER BY createdAt DESC ile aynı geçiş sırası

        // Hedef başına sayım + süre
        Map<String, Target> perTarget = new LinkedHashMap<>();          // domain|type → biriktirici
        int[][] heat = new int[7][24];                                   // [Pzt..Paz][0..23]
        // Zenginleştirme (2026-09-16): günlük seri (kıvılcım çizgisi), tip/seviye kırılımı, MTTR,
        // çözülme oranı ve mesai-dışı payı. Hepsi AYNI tek geçişten çıkar — ek sorgu yok.
        Map<String, Integer> byDate = new LinkedHashMap<>();
        for (int i = d - 1; i >= 0; i--) byDate.put(LocalDate.now(IST).minusDays(i).toString(), 0);
        Map<String, int[]> byType = new LinkedHashMap<>();                // tip → [toplam, kritik, sessiz]
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<Long, TeamAcc> teams = new LinkedHashMap<>();                 // takım (null = takımsız) → biriktirici
        Map<String, Set<String>> domainSources = new HashMap<>();        // host → düşük-sınıfı izleme türleri
        Map<String, Set<Long>> domainTeams = new HashMap<>();            // host → takımlar
        double resolvedMinutesSum = 0;
        int resolvedTotal = 0, offHours = 0, stillOpen = 0, silenced = 0, night = 0, nightUnacked = 0;
        int total = 0, critical = 0;
        for (Ev e : events) {
            total++;
            boolean crit = "CRITICAL".equalsIgnoreCase(e.alertLevel());
            if (crit) critical++;
            byLevel.merge(e.alertLevel() == null ? "?" : e.alertLevel().toUpperCase(Locale.ROOT), 1, Integer::sum);
            int[] tc = byType.computeIfAbsent(e.alertType() == null ? "?" : e.alertType(), k -> new int[3]);
            tc[0]++;
            if (crit) tc[1]++;
            boolean open = !Boolean.TRUE.equals(e.resolved());
            if (open) stillOpen++;
            // D-7 / D-c11 (2026-09-29): sessiz kapanış (silindi / duraklatıldı / envanter pasif / tür bildirimi kapalı)
            // kurtarma değildir — çözülme oranına, MTTR'a ve flap ortalamasına girmez; ayrı "silenced" kovasında sayılır.
            boolean silent = Boolean.TRUE.equals(e.resolved()) && Boolean.TRUE.equals(e.resolvedSilently());
            if (silent) { silenced++; tc[2]++; }
            Long team = teamOf(e, domainTeam);
            TeamAcc ta = teams.computeIfAbsent(team, k -> new TeamAcc());
            ta.alerts++; if (crit) ta.critical++; if (open) ta.stillOpen++; if (silent) ta.silent++;
            String key = (e.domain() == null ? "?" : e.domain()) + "|" + e.alertType();
            Target c = perTarget.computeIfAbsent(key, k -> new Target());
            c.count++;
            if (open) c.stillOpen++;
            if (Boolean.TRUE.equals(e.acknowledged())) c.acked++;
            if (c.type == null) c.type = e.alertType();
            if (c.teamId == null) c.teamId = team;
            if (e.createdAt() != null && (c.lastOpened == null || e.createdAt().compareTo(c.lastOpened) > 0)) c.lastOpened = e.createdAt();
            if (e.domain() != null) {
                if (DOWN_CLASS.contains(e.alertType())) {
                    String mt = MonitorTypeCatalog.typeOfAlert(e.alertType());
                    if (mt != null) domainSources.computeIfAbsent(e.domain(), k -> new LinkedHashSet<>()).add(mt);
                }
                if (team != null) domainTeams.computeIfAbsent(e.domain(), k -> new LinkedHashSet<>()).add(team);
            }
            Instant created = parse(e.createdAt());
            if (created != null) {
                ZonedDateTime z = created.atZone(IST);
                heat[z.getDayOfWeek().getValue() - 1][z.getHour()]++;
                byDate.computeIfPresent(z.toLocalDate().toString(), (k, v) -> v + 1);
                // Mesai dışı: hafta sonu ya da 18:00–09:00 arası (İstanbul) — "nöbet yükü" göstergesi.
                boolean weekend = z.getDayOfWeek().getValue() >= 6;
                if (weekend || z.getHour() >= 18 || z.getHour() < 9) { offHours++; ta.offHours++; }
                // Gece (22:00–06:00): OFF_HOURS_NOISE önerisi — sahiplenilmemişse "kimse bakmıyor" demektir.
                if (z.getHour() >= 22 || z.getHour() < 6) {
                    night++; c.night++;
                    if (!Boolean.TRUE.equals(e.acknowledged())) nightUnacked++;
                }
                Instant resolved = parse(e.resolvedAt());
                if (!silent && Boolean.TRUE.equals(e.resolved()) && resolved != null && resolved.isAfter(created)) {
                    double min = (resolved.toEpochMilli() - created.toEpochMilli()) / 60000.0;
                    c.resolved++;
                    resolvedTotal++;
                    resolvedMinutesSum += min;
                    c.minutesSum += min;
                    c.durations.add(min);
                }
            }
        }

        // ── Fırtına sayıları (takım başına, pencere) ──
        Map<Long, Integer> stormsPerTeam = new HashMap<>();
        try {
            for (Object[] row : stormRepo.lastStormPerTeam(since)) {
                if (row == null || row.length < 3 || !(row[0] instanceof Number tid) || !(row[2] instanceof Number n)) continue;
                stormsPerTeam.put(tid.longValue(), n.intValue());
            }
        } catch (Exception e) { log.debug("noise: fırtına sayısı okunamadı: {}", e.toString()); }

        // ── Hedef satırları + desen sınıflandırması ──
        List<Map<String, Object>> top = new ArrayList<>();
        List<Map<String, Object>> flapping = new ArrayList<>();
        Map<String, Map<String, Object>> rowByKey = new HashMap<>();
        int flapAlerts = 0, noisyAlerts = 0, noisyTargets = 0, flapTargets = 0;
        for (Map.Entry<String, Target> en : perTarget.entrySet()) {
            String[] parts = en.getKey().split("\\|", 2);
            Target tg = en.getValue();
            int count = tg.count, resolved = tg.resolved;
            Double avg = resolved == 0 ? null : Math.round(tg.minutesSum / resolved * 10) / 10.0;
            Double median = median(tg.durations);
            String pattern = classify(tg.type, count, avg, median);
            boolean flap = count >= FLAP_MIN_ALERTS && avg != null && avg <= FLAP_MAX_AVG_MINUTES;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", parts[0]); m.put("type", parts.length > 1 ? parts[1] : null); m.put("count", count); m.put("resolved", resolved); m.put("avg_minutes", avg);
            m.put("share_pct", total == 0 ? 0 : Math.round(1000.0 * count / total) / 10.0);
            // Ekleyici alanlar (2026-10-01)
            m.put("monitor_type", MonitorTypeCatalog.typeOfAlert(tg.type));
            m.put("team_id", tg.teamId);
            m.put("median_minutes", median);
            m.put("flap_count", flap ? count : 0);
            m.put("still_open", tg.stillOpen);
            m.put("last_opened_at", tg.lastOpened);
            m.put("pattern", pattern);
            top.add(m);
            rowByKey.put(en.getKey(), m);
            TeamAcc ta = teams.get(tg.teamId);
            if (flap) {
                Map<String, Object> f = new LinkedHashMap<>(m);
                f.put("suggestion", "raise-confirm");   // onay sayısı / eşik önerisi — metin arayüzde
                flapping.add(f);
                flapAlerts += count; flapTargets++;
                if (ta != null) { ta.flapAlerts += count; ta.flapTargets++; }
            }
            if (!PATTERN_NORMAL.equals(pattern)) {
                noisyTargets++;
                if (ta != null) ta.noisyTargets++;
                if (!flap) { noisyAlerts += count; if (ta != null) ta.noisyAlerts += count; }
            }
        }
        top.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        flapping.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));

        // Isı haritası: en yoğun hücre + en yoğun gün/saat
        int peak = 0, peakDay = -1, peakHour = -1;
        int[] byHour = new int[24], byDay = new int[7];
        for (int dow = 0; dow < 7; dow++) for (int h = 0; h < 24; h++) {
            int v = heat[dow][h]; byHour[h] += v; byDay[dow] += v;
            if (v > peak) { peak = v; peakDay = dow; peakHour = h; }
        }
        List<List<Integer>> heatRows = new ArrayList<>();
        for (int[] row : heat) { List<Integer> r = new ArrayList<>(24); for (int v : row) r.add(v); heatRows.add(r); }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d); out.put("total", total); out.put("critical", critical);
        out.put("distinct_targets", perTarget.size());
        out.put("top", top.subList(0, Math.min(TOP, top.size())));
        out.put("flapping", flapping);
        Map<String, Object> heatMap = new LinkedHashMap<>();
        heatMap.put("rows", heatRows); heatMap.put("peak", peak); heatMap.put("peak_day", peakDay); heatMap.put("peak_hour", peakHour);
        heatMap.put("by_hour", Arrays.stream(byHour).boxed().toList()); heatMap.put("by_day", Arrays.stream(byDay).boxed().toList());
        out.put("heat", heatMap);
        // ── Zenginleştirme alanları (2026-09-16) ──
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<String, Integer> en : byDate.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", en.getKey()); p.put("count", en.getValue());
            series.add(p);
        }
        out.put("series", series);
        List<Map<String, Object>> types = new ArrayList<>();
        for (Map.Entry<String, int[]> en : byType.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", en.getKey()); p.put("count", en.getValue()[0]); p.put("critical", en.getValue()[1]);
            p.put("share_pct", total == 0 ? 0 : Math.round(1000.0 * en.getValue()[0] / total) / 10.0);
            p.put("silent", en.getValue()[2]);
            p.put("monitor_type", MonitorTypeCatalog.typeOfAlert(en.getKey()));
            types.add(p);
        }
        types.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        out.put("by_type", types.subList(0, Math.min(TOP, types.size())));
        out.put("by_level", byLevel);
        out.put("still_open", stillOpen);
        out.put("silenced_total", silenced);   // D-7: sessiz kapanışlar (kurtarma değil) — MTTR/çözülme dışı
        out.put("resolved_total", resolvedTotal);
        out.put("resolved_pct", total == 0 ? 0 : Math.round(1000.0 * resolvedTotal / total) / 10.0);
        out.put("mttr_minutes", resolvedTotal == 0 ? null : Math.round(resolvedMinutesSum / resolvedTotal * 10) / 10.0);
        out.put("off_hours", offHours);
        out.put("off_hours_pct", total == 0 ? 0 : Math.round(1000.0 * offHours / total) / 10.0);
        out.put("per_day_avg", Math.round(10.0 * total / d) / 10.0);

        // ── Yeniden tasarım alanları (2026-10-01) ──
        out.put("team_filter", teamFilter);
        out.put("since", since);
        out.put("noisy_targets", noisyTargets);
        out.put("flap_targets", flapTargets);
        out.put("flap_alerts", flapAlerts);
        out.put("night", night);
        out.put("night_pct", total == 0 ? 0 : Math.round(1000.0 * night / total) / 10.0);
        out.put("noise_score", score(total, flapAlerts, noisyAlerts, silenced, offHours));

        // Takım adları — yalnız gereken id'ler
        Map<Long, String> teamNames = new HashMap<>();
        Set<Long> ids = new HashSet<>(); for (Long id : teams.keySet()) if (id != null) ids.add(id);
        try { if (!ids.isEmpty()) for (Team t : teamRepo.findAllById(ids)) teamNames.put(t.getId(), t.getName()); }
        catch (Exception e) { log.debug("noise: takım adları okunamadı: {}", e.toString()); }
        for (Map<String, Object> m : top) m.put("team_name", m.get("team_id") == null ? null : teamNames.get((Long) m.get("team_id")));
        for (Map<String, Object> m : flapping) m.put("team_name", m.get("team_id") == null ? null : teamNames.get((Long) m.get("team_id")));

        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (Map.Entry<Long, TeamAcc> en : teams.entrySet()) {
            TeamAcc ta = en.getValue();
            ta.storms = en.getKey() == null ? 0 : stormsPerTeam.getOrDefault(en.getKey(), 0);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("team_id", en.getKey());
            r.put("team_name", en.getKey() == null ? null : teamNames.get(en.getKey()));
            r.put("alerts", ta.alerts); r.put("critical", ta.critical); r.put("still_open", ta.stillOpen);
            r.put("noisy_targets", ta.noisyTargets); r.put("flaps", ta.flapAlerts); r.put("flap_targets", ta.flapTargets);
            r.put("silent_closes", ta.silent); r.put("off_hours", ta.offHours); r.put("storms", ta.storms);
            r.put("share_pct", total == 0 ? 0 : Math.round(1000.0 * ta.alerts / total) / 10.0);
            r.put("noise_score", score(ta.alerts, ta.flapAlerts, ta.noisyAlerts, ta.silent, ta.offHours));
            teamRows.add(r);
        }
        teamRows.sort((a, b) -> {
            int c = Integer.compare((int) b.get("noise_score"), (int) a.get("noise_score"));
            return c != 0 ? c : Integer.compare((int) b.get("alerts"), (int) a.get("alerts"));
        });
        out.put("teams", teamRows);

        List<Map<String, Object>> hours = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("hour", h); p.put("count", byHour[h]); p.put("night", h >= 22 || h < 6);
            hours.add(p);
        }
        out.put("hours", hours);

        // Görülebilir takım seçenekleri (seçici) — aktif takımlar, kapsam predicate'i ile süzülür
        List<Map<String, Object>> teamOptions = new ArrayList<>();
        try {
            for (Team t : teamRepo.findByActiveTrueOrderByNameAsc()) {
                if (!canViewTeam.test(t.getId())) continue;
                Map<String, Object> o = new LinkedHashMap<>(); o.put("id", t.getId()); o.put("name", t.getName());
                teamOptions.add(o);
            }
        } catch (Exception e) { log.debug("noise: takım listesi okunamadı: {}", e.toString()); }
        out.put("team_options", teamOptions);

        out.put("suggestions", suggestions(d, total, top, teamNames, domainSources, domainTeams, silenced, night, nightUnacked, teamRows));
        return out;
    }

    // ── Isı haritası hücresi ayrıntısı (2026-10-01, kullanıcı isteği: "hücreler tıklanmıyor") ──
    static final int SLOT_LIMIT = 200;

    /**
     * Gün × saat hücresindeki alarmlar: pencere ({@code days}, İstanbul takvimi — {@link #build} ile aynı) içinde
     * haftanın {@code dow} günü (0 = Pazartesi … 6 = Pazar) saat {@code hour}'da (İstanbul) açılanlar, en yeniden. Görünürlük
     * ve takım süzgeci {@code build} ile BİREBİR (damgalı takım ya da görülebilir envanter; takım = damga, yoksa SY).
     * En çok {@value #SLOT_LIMIT} satır; {@code total} gerçek sayıdır (hücredeki sayıyla aynı).
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> slot(int days, Long teamFilter, Predicate<Long> canViewTeam, int dow, int hour) {
        int d = Math.max(1, Math.min(MAX_DAYS, days));
        if (dow < 0 || dow > 6 || hour < 0 || hour > 23) throw new IllegalArgumentException("dow 0-6, hour 0-23");
        Set<String> domains = new HashSet<>();
        Map<String, Long> domainTeam = new HashMap<>();
        try {
            for (Object[] r : inventoryRepo.findActiveDomainTeams()) {
                if (r == null || r.length < 2) continue;
                String domain = r[0] == null ? null : String.valueOf(r[0]);
                Long teamId = r[1] instanceof Number n ? n.longValue() : null;
                if (canViewTeam.test(teamId)) { domains.add(domain); if (teamId != null) domainTeam.put(domain, teamId); }
            }
        } catch (Exception e) { log.debug("noise slot: envanter okunamadı: {}", e.toString()); }
        String since = ISO.format(LocalDate.now(IST).minusDays(d - 1L).atStartOfDay(IST).toInstant());

        List<Object[]> hits = new ArrayList<>();
        for (Object[] r : alertEventRepo.findNoiseSlotRowsSince(since)) {
            if (r == null || r.length < 9) continue;
            String domain = r[1] == null ? null : String.valueOf(r[1]);
            Long stamped = r[8] instanceof Number n ? n.longValue() : null;
            boolean vis = (stamped != null && canViewTeam.test(stamped)) || (domain != null && domains.contains(domain));
            if (!vis) continue;
            Long team = stamped != null ? stamped : (domain == null ? null : domainTeam.get(domain));
            if (teamFilter != null && !teamFilter.equals(team)) continue;
            Instant created = parse(r[4] == null ? null : String.valueOf(r[4]));
            if (created == null) continue;
            ZonedDateTime z = created.atZone(IST);
            if (z.getDayOfWeek().getValue() - 1 != dow || z.getHour() != hour) continue;
            Object[] row = java.util.Arrays.copyOf(r, 10);
            row[9] = team;
            hits.add(row);
        }
        // En yeni önce; aynı saniyede açılanlarda kimlik azalan (sayfalar arası kararlı sıra)
        hits.sort((a, b) -> {
            int c = String.valueOf(b[4]).compareTo(String.valueOf(a[4]));
            if (c != 0) return c;
            long ia = a[0] instanceof Number n ? n.longValue() : 0L, ib = b[0] instanceof Number m ? m.longValue() : 0L;
            return Long.compare(ib, ia);
        });

        Map<Long, String> names = new HashMap<>();
        try {
            Set<Long> ids = new HashSet<>();
            for (int i = 0; i < Math.min(SLOT_LIMIT, hits.size()); i++) if (hits.get(i)[9] != null) ids.add((Long) hits.get(i)[9]);
            if (!ids.isEmpty()) for (Team t : teamRepo.findAllById(ids)) names.put(t.getId(), t.getName());
        } catch (Exception e) { log.debug("noise slot: takım adları okunamadı: {}", e.toString()); }

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < Math.min(SLOT_LIMIT, hits.size()); i++) {
            Object[] r = hits.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0] instanceof Number n ? n.longValue() : null);
            m.put("domain", r[1]);
            m.put("alert_type", r[2]);
            m.put("alert_level", r[3]);
            m.put("created_at", r[4]);
            m.put("resolved_at", r[5]);
            m.put("resolved", Boolean.TRUE.equals(r[6]));
            m.put("acknowledged", Boolean.TRUE.equals(r[7]));
            m.put("team_id", r[9]);
            m.put("team_name", r[9] == null ? null : names.get((Long) r[9]));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("dow", dow);
        out.put("hour", hour);
        out.put("team_filter", teamFilter);
        out.put("total", hits.size());
        out.put("truncated", hits.size() > SLOT_LIMIT);
        out.put("items", items);
        return out;
    }

    // ── Yardımcılar ──

    /** Olayın takımı: alarmda damgalı takım, yoksa envanterin SY takımı (görülebilir envanter). */
    private static Long teamOf(Ev e, Map<String, Long> domainTeam) {
        if (e.teamId() != null) return e.teamId();
        return e.domain() == null ? null : domainTeam.get(e.domain());
    }

    /** Desen: SLOW > FLAPPING > SHORT_OUTAGES > REPEAT > NORMAL (yavaşlık tipinde çözüm eşik, titremede onay sayısı). */
    static String classify(String type, int count, Double avg, Double median) {
        if (type != null && type.endsWith("_SLOW") && count >= SLOW_MIN_ALERTS) return AlertNoiseSuggestion.SLOW_THRESHOLD_TIGHT.name();
        if (count >= FLAP_MIN_ALERTS && avg != null && avg <= FLAP_MAX_AVG_MINUTES) return AlertNoiseSuggestion.FLAPPING.name();
        if (count >= SHORT_MIN_ALERTS && median != null && median < SHORT_MAX_MEDIAN_MINUTES) return AlertNoiseSuggestion.SHORT_OUTAGES.name();
        if (count >= REPEAT_MIN_ALERTS) return AlertNoiseSuggestion.REPEAT_SAME_TARGET.name();
        return PATTERN_NORMAL;
    }

    /**
     * Gürültü skoru 0–100: titreme alarmlarının payı tam ağırlık, öteki gürültülü hedeflerin alarmları 0,6,
     * sessiz kapanış 0,4, mesai dışı 0,3. Tümü titreme olan bir küme 100'e dayanır; sakin küme 0.
     */
    static int score(int total, int flapAlerts, int noisyAlerts, int silent, int offHours) {
        if (total <= 0) return 0;
        double s = 100.0 * flapAlerts / total + 60.0 * noisyAlerts / total + 40.0 * silent / total + 30.0 * offHours / total;
        return (int) Math.min(100, Math.round(s));
    }

    private static Double median(List<Double> xs) {
        if (xs.isEmpty()) return null;
        List<Double> s = new ArrayList<>(xs); Collections.sort(s);
        int n = s.size();
        double m = n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
        return Math.round(m * 10) / 10.0;
    }

    private static Map<String, Object> suggestion(AlertNoiseSuggestion code, String tab, Map<String, Object> actionParams) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", code.name()); s.put("severity", code.severity); s.put("title_key", code.titleKey());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("kind", code.action); a.put("tab", tab); a.put("params", actionParams == null ? Map.of() : actionParams);
        s.put("action", a);
        return s;
    }

    private List<Map<String, Object>> suggestions(int days, int total, List<Map<String, Object>> top, Map<Long, String> teamNames,
                                                  Map<String, Set<String>> domainSources, Map<String, Set<Long>> domainTeams,
                                                  int silenced, int night, int nightUnacked, List<Map<String, Object>> teamRows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (total == 0) return out;
        // Hedef bazlı — en çok alarm üretenden başlar (top zaten sıralı)
        for (Map<String, Object> row : top) {
            if (out.size() >= SUGGESTIONS_MAX) break;
            String pattern = (String) row.get("pattern");
            if (PATTERN_NORMAL.equals(pattern)) continue;
            AlertNoiseSuggestion code = AlertNoiseSuggestion.valueOf(pattern);
            String domain = (String) row.get("domain");
            String mt = (String) row.get("monitor_type");
            String tab = switch (code) {
                case REPEAT_SAME_TARGET -> "maintenance";
                default -> mt != null ? mt : "alerthistory";
            };
            Map<String, Object> s = suggestion(code, tab, Map.of("q", domain));
            s.put("target", domain); s.put("type", row.get("type")); s.put("monitor_type", mt);
            s.put("team_id", row.get("team_id")); s.put("team_name", row.get("team_name"));
            s.put("count", row.get("count"));
            List<Object> params = switch (code) {
                case FLAPPING -> List.of(row.get("count"), row.get("avg_minutes"));
                case SHORT_OUTAGES -> List.of(row.get("count"), row.get("median_minutes"));
                case REPEAT_SAME_TARGET -> List.of(row.get("count"), days);
                default -> List.of(row.get("count"));
            };
            s.put("params", params);
            out.add(s);
        }
        // Aynı host'u birden çok tür izliyor
        for (Map.Entry<String, Set<String>> en : domainSources.entrySet()) {
            if (out.size() >= SUGGESTIONS_MAX) break;
            Set<Long> tms = domainTeams.getOrDefault(en.getKey(), Set.of());
            if (en.getValue().size() < DUP_MIN_SOURCES && tms.size() < DUP_MIN_SOURCES) continue;
            Map<String, Object> s = suggestion(AlertNoiseSuggestion.DUPLICATE_MONITORS, "alerthistory", Map.of("q", en.getKey()));
            s.put("target", en.getKey());
            s.put("sources", new ArrayList<>(en.getValue()));
            s.put("team_ids", new ArrayList<>(tms));
            s.put("params", List.of(Math.max(en.getValue().size(), tms.size()), String.join(", ", en.getValue())));
            out.add(s);
        }
        // Sessiz kapanışlar
        int silentPct = (int) Math.round(100.0 * silenced / total);
        if (silenced >= SILENT_MIN && silentPct >= SILENT_MIN_PCT) {
            Map<String, Object> s = suggestion(AlertNoiseSuggestion.SILENT_CLOSES, "alerthistory", Map.of("view", "closed"));
            s.put("count", silenced); s.put("params", List.of(silenced, silentPct));
            out.add(s);
        }
        // Gece gürültüsü (sahiplenilmemiş)
        int nightPct = (int) Math.round(100.0 * night / total);
        int nightUnackedPct = night == 0 ? 0 : (int) Math.round(100.0 * nightUnacked / night);
        if (total >= NIGHT_MIN_ALERTS && nightPct >= NIGHT_MIN_PCT && nightUnackedPct >= NIGHT_UNACKED_MIN_PCT) {
            Map<String, Object> s = suggestion(AlertNoiseSuggestion.OFF_HOURS_NOISE, "settings", Map.of("sec", "userpush"));
            s.put("count", night); s.put("params", List.of(nightPct, nightUnackedPct));
            out.add(s);
        }
        // Fırtınaya yatkın takımlar
        for (Map<String, Object> r : teamRows) {
            int storms = (int) r.get("storms");
            if (r.get("team_id") == null || storms < STORM_MIN) continue;
            Map<String, Object> s = suggestion(AlertNoiseSuggestion.STORM_PRONE, "settings", Map.of("sec", "storm"));
            s.put("team_id", r.get("team_id")); s.put("team_name", r.get("team_name"));
            s.put("count", storms); s.put("params", List.of(storms, days));
            out.add(s);
        }
        // Sıra: HIGH → MEDIUM → INFO, içinde alarm sayısı azalan
        Map<String, Integer> rank = Map.of("HIGH", 0, "MEDIUM", 1, "INFO", 2);
        out.sort((a, b) -> {
            int c = Integer.compare(rank.getOrDefault((String) a.get("severity"), 9), rank.getOrDefault((String) b.get("severity"), 9));
            if (c != 0) return c;
            int ca = a.get("count") instanceof Number n ? n.intValue() : 0, cb = b.get("count") instanceof Number n ? n.intValue() : 0;
            return Integer.compare(cb, ca);
        });
        return out;
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z"); } catch (Exception e) { return null; }
    }
}
