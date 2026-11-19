package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.util.TtlMemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Takım kesinti takvimi (2026-11-19, Alarm Geçmişi → "Takvim" görünümü) — bir takımın bir ayındaki kesinti dakikaları.
 *
 * <p><b>Kaynak.</b> Yalnız KESİNTİ ailesi alarmları ({@link EscalationService#DOWN_ALERT_TYPES}); uyarı / yavaşlık /
 * değişiklik / sertifika alarmları sayılmaz. Aralık açılış → kapanış; hâlâ açık alarm "şimdi"ye kadar.
 *
 * <p><b>Sahiplik.</b> {@link AlertOwnership#ownerTeamIds(AlertEvent, Long, Long)} — damgalı takım, envanter gibi yönlenen
 * alarmda ayrıca alan adının envanter SY / UG takımı; bağımsız izlemenin alarmı yalnız damgalı takımındır; sahipsiz alarm
 * hiçbir takımın değildir. Bağımsızlık kararı bağlam JSON'undaki işarete bağlı olabildiğinden bağlam yalnız bu türlerde
 * (envanter türevi olabilen Port / DNS / Erişilebilirlik) okunur.
 *
 * <p><b>Maliyet.</b> Kurum geneli ay verisi ({@link MonthData}: sahipli aralıklar) pod başına
 * {@code site.monitor.alerts.outage-calendar-cache-ms} (60 sn) ay anahtarıyla saklanır: TEK dar alarm sorgusu (tavan
 * {@link #MAX_ROWS}, aşılırsa {@code truncated}) + envanter gibi yönlenen alarmların alan adları için ≤ 500'lük parçalarla
 * envanter okuması. Takım süzgeci ve gün hesabı HER çağrıda bellekte yapılır (kullanıcıya özgü sonuç saklanmaz).
 *
 * <p><b>Okunamayan veri "kesinti yok" DEĞİLDİR:</b> sorgulardan biri düşerse {@link UnavailableException} fırlar (denetleyici
 * 503 {@code DB_UNAVAILABLE} döner) ve sonuç saklanmaz.
 */
@Slf4j
@Service
public class OutageCalendarService {

    /** Ay başına okunan en çok kesinti alarmı — aşılırsa yanıtta {@code truncated: true}. */
    public static final int MAX_ROWS = 20_000;
    /** Envanter okuması parça boyu (IN listesi). */
    static final int INVENTORY_CHUNK = 500;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Kesinti ailesi — sıralı (sorgu parametresi kararlı olsun). */
    static final List<String> DOWN_TYPES = List.copyOf(new TreeSet<>(EscalationService.DOWN_ALERT_TYPES));
    /**
     * Sahipliği bağlamdaki bağımsızlık işaretine bağlı kesinti türleri — tür listesi tek başına "bağımsız" demiyor. Elle
     * yazılmaz: bağlamsız bir olayın bağımsızlık kararından türetilir ({@link EscalationService#isStandaloneEvent}).
     */
    static final List<String> CONTEXT_TYPES = contextTypes();

    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;

    @Value("${site.monitor.alerts.outage-calendar-cache-ms:60000}")
    long cacheMs = 60_000L;

    TtlMemo<MonthData> memo = new TtlMemo<>(32);
    /** Satır tavanı — test kancası (üretimde {@link #MAX_ROWS}). */
    int maxRows = MAX_ROWS;

    public OutageCalendarService(AlertEventRepository alertEventRepo, CertificateInventoryRepository inventoryRepo) {
        this.alertEventRepo = alertEventRepo;
        this.inventoryRepo = inventoryRepo;
    }

    /** Sahip takımlarıyla bir kesinti aralığı. */
    public record OwnedInterval(OutageCalendar.Interval interval, Set<Long> owners) {}

    /** Kurum geneli ay verisi — çağıranlar arasında paylaşılır, DEĞİŞTİRİLMEZ. */
    public record MonthData(List<OwnedInterval> intervals, boolean truncated) {}

    /** Takımın ay sonucu + kesilme bilgisi. */
    public record TeamMonth(OutageCalendar.Result result, boolean truncated) {}

    /** Veri okunamadı — "kesinti yok" sayılmaz. */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("kesinti takvimi okunamadı", cause); }
    }

    /** Takımın ayı — kurum geneli ay verisi (bellekten) takıma süzülür, "şimdi" ile hesaplanır. */
    public TeamMonth forTeam(YearMonth month, long teamId, long nowMs) {
        return forTeam(month, teamId, nowMs, Long.MIN_VALUE);
    }

    /** {@code coverFromMs} öncesinde başlayan günler "veri yok" (saklama süresi — {@link OutageCalendar#compute}). */
    public TeamMonth forTeam(YearMonth month, long teamId, long nowMs, long coverFromMs) {
        MonthData data = monthData(month);
        List<OutageCalendar.Interval> mine = new ArrayList<>();
        for (OwnedInterval oi : data.intervals()) {
            if (oi.owners().contains(teamId)) mine.add(oi.interval());
        }
        return new TeamMonth(OutageCalendar.compute(month, mine, nowMs, coverFromMs), data.truncated());
    }

    /** Kurum geneli ay verisi — {@code cacheMs} boyunca paylaşılır; okuma hatası saklanmaz. */
    public MonthData monthData(YearMonth month) {
        return memo.get(month.toString(), cacheMs, false, () -> load(month));
    }

    MonthData load(YearMonth month) {
        long[] win = OutageCalendar.monthWindowMs(month);
        String start = ISO.format(Instant.ofEpochMilli(win[0]));
        String end = ISO.format(Instant.ofEpochMilli(win[1]));
        List<Object[]> rows;
        try {
            rows = alertEventRepo.findOutageCalendarRows(DOWN_TYPES,
                    CONTEXT_TYPES.isEmpty() ? List.of("-") : CONTEXT_TYPES, start, end, PageRequest.of(0, maxRows + 1));
        } catch (RuntimeException e) {
            throw new UnavailableException(e);
        }
        boolean truncated = rows.size() > maxRows;
        if (truncated) {
            log.warn("Kesinti takvimi {}: {} satır tavanı aşıldı — en eski {} alarm hesaba katıldı", month, maxRows, maxRows);
            rows = rows.subList(0, maxRows);
        }

        List<AlertEvent> events = new ArrayList<>(rows.size());
        Set<String> invDomains = new LinkedHashSet<>();
        for (Object[] r : rows) {
            AlertEvent ev = toEvent(r);
            if (ev == null) continue;
            events.add(ev);
            if (ev.getDomain() != null && AlertOwnership.routesLikeInventory(ev)) invDomains.add(ev.getDomain());
        }
        Map<String, Long[]> inv = inventoryOwners(invDomains);

        List<OwnedInterval> out = new ArrayList<>(events.size());
        for (AlertEvent ev : events) {
            Long startMs = parseMs(ev.getCreatedAt());
            if (startMs == null) continue;   // bozuk damga: hesaba katılamaz
            Long endMs;
            if (Boolean.TRUE.equals(ev.getResolved())) {
                Long r = parseMs(ev.getResolvedAt());
                endMs = r == null ? startMs : r;   // kapanış anı bilinmiyor → sıfır uzunluk (alarm yine sayılır)
            } else {
                endMs = null;                      // hâlâ açık → "şimdi"ye kadar
            }
            Long[] o = ev.getDomain() == null ? null : inv.get(ev.getDomain());
            Set<Long> owners = AlertOwnership.ownerTeamIds(ev, o == null ? null : o[0], o == null ? null : o[1]);
            if (owners.isEmpty()) continue;        // sahipsiz alarm hiçbir takımın değildir
            out.add(new OwnedInterval(new OutageCalendar.Interval(startMs, endMs, ev.getAlertLevel()), Set.copyOf(owners)));
        }
        return new MonthData(Collections.unmodifiableList(out), truncated);
    }

    /** Alan adı → [SY, UG] — yalnız istenen alan adları, ≤ 500'lük parçalarla (ilk satır kazanır). */
    private Map<String, Long[]> inventoryOwners(Set<String> domains) {
        Map<String, Long[]> m = new HashMap<>();
        if (domains.isEmpty()) return m;
        List<String> all = new ArrayList<>(domains);
        try {
            for (int i = 0; i < all.size(); i += INVENTORY_CHUNK) {
                List<String> slice = all.subList(i, Math.min(i + INVENTORY_CHUNK, all.size()));
                for (Object[] r : inventoryRepo.findDomainOwnersIn(slice)) {
                    if (r == null || r.length < 3 || r[0] == null) continue;
                    m.putIfAbsent(String.valueOf(r[0]), new Long[] { asLong(r[1]), asLong(r[2]) });
                }
            }
        } catch (RuntimeException e) {
            throw new UnavailableException(e);
        }
        return m;
    }

    private static AlertEvent toEvent(Object[] r) {
        if (r == null || r.length < 9) return null;
        AlertEvent ev = new AlertEvent();
        ev.setId(asLong(r[0]));
        ev.setDomain(r[1] == null ? null : String.valueOf(r[1]));
        ev.setAlertType(r[2] == null ? null : String.valueOf(r[2]));
        ev.setAlertLevel(r[3] == null ? null : String.valueOf(r[3]));
        ev.setTeamId(asLong(r[4]));
        ev.setCreatedAt(r[5] == null ? null : String.valueOf(r[5]));
        ev.setResolvedAt(r[6] == null ? null : String.valueOf(r[6]));
        ev.setResolved(r[7] instanceof Boolean b ? b : Boolean.FALSE);
        ev.setContextJson(r[8] == null ? null : String.valueOf(r[8]));
        return ev;
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }

    /** UTC ISO damga ({@code yyyy-MM-ddTHH:mm:ss}, ilk 19 karakter) → epoch ms; okunamazsa null. */
    static Long parseMs(String s) {
        if (s == null || s.length() < 19) return null;
        try {
            return LocalDateTime.parse(s.substring(0, 19)).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> contextTypes() {
        List<String> out = new ArrayList<>();
        for (String t : DOWN_TYPES) {
            AlertEvent probe = new AlertEvent();
            probe.setAlertType(t);
            if (!EscalationService.isStandaloneEvent(probe)) out.add(t);
        }
        return List.copyOf(out);
    }
}
