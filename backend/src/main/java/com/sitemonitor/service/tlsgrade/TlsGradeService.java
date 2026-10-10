package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.model.TlsGradeStatus;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.TlsGradeChangeRepository;
import com.sitemonitor.repository.TlsGradeStatusRepository;
import com.sitemonitor.repository.TlsProfileRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.tlsgrade.TlsGradeRules.Finding;
import com.sitemonitor.service.tlsgrade.TlsGradeRules.Grade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TLS notunun okuma / karşılaştırma servisi (2026-10-10). Not {@link TlsGradeRules}'ta hesaplanır; bu sınıf:
 * <ul>
 *   <li>liste satırlarına notu yazar ({@link #snapshot} + {@link #apply}) — {@code CertificateService.getAllLatest} TEK
 *       toplu profil + durum okumasıyla (satır başına sorgu YOK; liste {@code cert-latest} önbelleğinde);</li>
 *   <li>güncel notu son bilinen notla karşılaştırır ({@link #reconcile}) — değişim günlüğü + düşüş damgası + etkinlik
 *       kaydı ({@code TLS_GRADE_DROPPED}); notlanamayan satır durumu DEĞİŞTİRMEZ;</li>
 *   <li>sertifika penceresinin ayrıntısını ({@link #detail}) ve "son düşüşler" listesini ({@link #drops}) kurar.</li>
 * </ul>
 * Bildirim (e-posta / push) GÖNDERMEZ: not düşüşü uygulama içinde gösterilir (kart göstergesi, liste, etkinlik akışı).
 */
@Slf4j
@Service
public class TlsGradeService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Liste satırına yazılan en fazla neden kodu (yük küçük kalsın). */
    static final int ROW_REASONS_MAX = 8;
    /** Durum tablosuna yazılan kod listesinin karakter sınırı (kolon 1000). */
    static final int CODES_MAX_CHARS = 990;

    private final TlsProfileRepository profileRepo;
    private final TlsGradeStatusRepository statusRepo;
    private final TlsGradeChangeRepository changeRepo;

    /** Etkinlik akışı (best-effort) — isteğe bağlı: birim testi bağlamında yok. */
    @Autowired(required = false)
    private ActivityLogService activityLog;

    /** Kartın "not düştü" göstergesi bu kadar gün görünür (not geri yükselirse hemen kalkar). */
    @Value("${site.monitor.tls-grade.drop-notice-days:7}")
    int dropNoticeDays = 7;

    public TlsGradeService(TlsProfileRepository profileRepo, TlsGradeStatusRepository statusRepo,
                           TlsGradeChangeRepository changeRepo) {
        this.profileRepo = profileRepo;
        this.statusRepo = statusRepo;
        this.changeRepo = changeRepo;
    }

    void setActivityLog(ActivityLogService activityLog) {
        this.activityLog = activityLog;
    }

    // ── Liste satırları ───────────────────────────────────────────────────────────────────────

    /** Liste kurulurken bir kez okunan profil + durum haritaları. */
    public record Snapshot(Map<String, TlsProfile> profiles, Map<Long, TlsGradeStatus> statuses) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());
    }

    /** İki toplu sorgu (profil: alan adı IN; durum: kimlik IN). Hata → boş anlık görüntü (not yine hesaplanır). */
    public Snapshot snapshot(Collection<String> domains, Collection<Long> inventoryIds) {
        Map<String, TlsProfile> profiles = new HashMap<>();
        Map<Long, TlsGradeStatus> statuses = new HashMap<>();
        try {
            if (domains != null && !domains.isEmpty()) {
                for (TlsProfile p : profileRepo.findByDomainIn(domains)) if (p.getDomain() != null) profiles.put(p.getDomain(), p);
            }
            if (inventoryIds != null && !inventoryIds.isEmpty()) {
                for (TlsGradeStatus s : statusRepo.findAllById(inventoryIds)) statuses.put(s.getInventoryId(), s);
            }
        } catch (Exception e) {
            log.debug("TLS notu anlık görüntüsü okunamadı (tablo henüz yok?): {}", e.toString());
            return Snapshot.EMPTY;
        }
        return new Snapshot(profiles, statuses);
    }

    /**
     * Satıra {@code tls_grade}, {@code tls_grade_reasons} ve (son {@link #dropNoticeDays} günde düştüyse)
     * {@code tls_grade_drop} yazar. Elle yüklenen / notlanamayan satıra hiçbir şey yazılmaz (alanlar yanıtta görünmez).
     */
    public void apply(CertificateDto dto, LatestCheck lc, CertificateInventory inv, Snapshot snap) {
        if (dto == null || lc == null) return;
        Snapshot s = snap == null ? Snapshot.EMPTY : snap;
        boolean manual = inv != null && inv.isManual();
        Grade g = TlsGradeRules.evaluate(lc, s.profiles().get(lc.getDomain()), manual, dto.getSecurityFlags());
        if (!g.graded()) return;
        dto.setTlsGrade(g.grade());
        List<String> codes = g.codes();
        dto.setTlsGradeReasons(codes.size() > ROW_REASONS_MAX ? codes.subList(0, ROW_REASONS_MAX) : codes);
        TlsGradeStatus st = inv == null || inv.getId() == null ? null : s.statuses().get(inv.getId());
        dto.setTlsGradeDrop(dropOf(st, g.grade(), Instant.now()));
    }

    /** Etkin düşüş: süre penceresinde ve not, düşmeden önceki değerin hâlâ ALTINDA. */
    Map<String, Object> dropOf(TlsGradeStatus st, String current, Instant now) {
        if (st == null || st.getDroppedFrom() == null || st.getDroppedAt() == null) return null;
        if (TlsGradeRules.rank(current) >= TlsGradeRules.rank(st.getDroppedFrom())) return null;
        Instant at = parse(st.getDroppedAt());
        if (at == null || at.isBefore(now.minus(Math.max(1, dropNoticeDays), ChronoUnit.DAYS))) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", st.getDroppedFrom());
        m.put("to", current);
        m.put("at", st.getDroppedAt());
        return m;
    }

    // ── Karşılaştırma (iş turu) ───────────────────────────────────────────────────────────────

    /** Karşılaştırılacak satır: envanter kaydı + güncel not + kodlar. */
    public record Evaluated(CertificateInventory inv, String grade, List<String> codes) { }

    public record ReconcileResult(int created, int updated, int drops, int rises, int refined) {
        public ReconcileResult(int created, int updated, int drops, int rises) { this(created, updated, drops, rises, 0); }

        public boolean changed() { return drops + rises + refined > 0; }
    }

    /** Notu TLS profilinin EKSİKLİĞİ sınırlayan kodlar — bunlardan biri varken not değişimi bir "bilgi değişimi"dir. */
    static final java.util.Set<String> PROFILE_GAP_CODES = java.util.Set.of(
            TlsGradeRules.Reason.PROFILE_PENDING.name(), TlsGradeRules.Reason.PROFILE_FAILED.name(),
            TlsGradeRules.Reason.PROFILE_PARTIAL.name());

    static boolean hasProfileGap(java.util.Collection<String> codes) {
        if (codes == null) return false;
        for (String c : codes) if (PROFILE_GAP_CODES.contains(c)) return true;
        return false;
    }

    /**
     * Güncel notları son bilinenle karşılaştırır. Yalnız DEĞİŞEN satır yazılır (tur başına sabit sayıda toplu sorgu:
     * durumlar IN ile okunur, değişenler {@code saveAll}). İlk görüş değişim sayılmaz.
     */
    public ReconcileResult reconcile(List<Evaluated> items) {
        if (items == null || items.isEmpty()) return new ReconcileResult(0, 0, 0, 0);
        Map<Long, Evaluated> byId = new LinkedHashMap<>();
        for (Evaluated e : items) {
            if (e == null || e.inv() == null || e.inv().getId() == null || !TlsGradeRules.isGrade(e.grade())) continue;
            byId.putIfAbsent(e.inv().getId(), e);
        }
        if (byId.isEmpty()) return new ReconcileResult(0, 0, 0, 0);
        Map<Long, TlsGradeStatus> existing = new HashMap<>();
        for (TlsGradeStatus s : statusRepo.findAllById(byId.keySet())) existing.put(s.getInventoryId(), s);

        String now = ISO.format(Instant.now());
        List<TlsGradeStatus> toSave = new ArrayList<>();
        List<TlsGradeChange> events = new ArrayList<>();
        int created = 0, updated = 0, drops = 0, rises = 0, refined = 0;
        for (Evaluated e : byId.values()) {
            String codes = joinCodes(e.codes());
            TlsGradeStatus st = existing.get(e.inv().getId());
            if (st == null) {
                st = new TlsGradeStatus();
                st.setInventoryId(e.inv().getId());
                st.setDomain(e.inv().getDomain());
                st.setGrade(e.grade());
                st.setReasons(codes);
                st.setEvaluatedAt(now);
                st.setChangedAt(now);
                toSave.add(st);
                created++;
                continue;
            }
            if (e.grade().equals(st.getGrade())) {
                if (!codes.equals(st.getReasons() == null ? "" : st.getReasons())
                        || !java.util.Objects.equals(st.getDomain(), e.inv().getDomain())) {
                    st.setReasons(codes);
                    st.setDomain(e.inv().getDomain());
                    st.setEvaluatedAt(now);
                    toSave.add(st);
                    updated++;
                }
                continue;
            }
            String old = st.getGrade();
            boolean lower = TlsGradeRules.isGrade(old) && TlsGradeRules.rank(e.grade()) < TlsGradeRules.rank(old);
            // Bilgi değişimi: önceki ya da yeni notu profil EKSİKLİĞİ sınırlıyordu (ilk tarama, başarısız / eksik tarama).
            // "TLS 1.0 açık" ilk kez ÖĞRENİLİNCE A → B bir yapılandırma bozulması değildir — düşüş damgası ve etkinlik yok.
            boolean refine = hasProfileGap(splitCodes(st.getReasons())) || hasProfileGap(e.codes());
            boolean drop = lower && !refine;
            TlsGradeChange ch = new TlsGradeChange();
            ch.setInventoryId(e.inv().getId());
            ch.setDomain(e.inv().getDomain());
            ch.setTeamId(e.inv().getTeamId());
            ch.setUgTeamId(e.inv().getUgTeamId());
            ch.setFromGrade(old);
            ch.setToGrade(e.grade());
            ch.setDirection(refine ? TlsGradeChange.REFINE : drop ? TlsGradeChange.DROP : TlsGradeChange.RISE);
            ch.setReasons(codes);
            ch.setChangedAt(now);
            events.add(ch);

            st.setPreviousGrade(old);
            st.setGrade(e.grade());
            st.setReasons(codes);
            st.setDomain(e.inv().getDomain());
            st.setEvaluatedAt(now);
            st.setChangedAt(now);
            if (refine) {
                refined++;   // düşüş damgasına dokunulmaz (gösterge, mevcut nota göre kendiliğinden görünür / kaybolur)
            } else if (drop) {
                st.setDroppedFrom(old);
                st.setDroppedAt(now);
                drops++;
            } else {
                if (st.getDroppedFrom() != null && TlsGradeRules.rank(e.grade()) >= TlsGradeRules.rank(st.getDroppedFrom())) {
                    st.setDroppedFrom(null);
                    st.setDroppedAt(null);
                }
                rises++;
            }
            toSave.add(st);
        }
        if (!toSave.isEmpty()) statusRepo.saveAll(toSave);
        if (!events.isEmpty()) changeRepo.saveAll(events);
        for (TlsGradeChange ch : events) {
            if (!TlsGradeChange.DROP.equals(ch.getDirection()) || activityLog == null) continue;
            activityLog.recordLifecycle(ActivityLogService.CERT, ch.getInventoryId(), ch.getDomain(), ch.getDomain(),
                    ch.getTeamId(), "TLS_GRADE_DROPPED", "system",
                    "TLS notu " + ch.getFromGrade() + " → " + ch.getToGrade() + " · " + ch.getReasons());
        }
        if (drops + rises + refined > 0) {
            log.info("TLS notu karşılaştırması: {} düşüş, {} yükseliş, {} bilgi değişimi, {} yeni, {} neden güncellemesi",
                    drops, rises, refined, created, updated);
        }
        return new ReconcileResult(created, updated, drops, rises, refined);
    }

    static String joinCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String c : codes) {
            if (sb.length() + c.length() + 1 > CODES_MAX_CHARS) break;
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        return sb.toString();
    }

    static List<String> splitCodes(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String c : s.split(",")) if (!c.isBlank()) out.add(c.trim());
        return out;
    }

    // ── Ayrıntı (sertifika penceresi) ─────────────────────────────────────────────────────────

    /**
     * Sertifika penceresinin "TLS notu" bölümü: not + TÜM nedenler (parametreleriyle) + bilgi notları + profil + anlaşılan
     * el sıkışması + HSTS + not geçmişi.
     */
    public Map<String, Object> detail(CertificateInventory inv, LatestCheck lc) {
        TlsProfile profile = inv == null || inv.getDomain() == null ? null : profileRepo.findById(inv.getDomain()).orElse(null);
        boolean manual = inv != null && inv.isManual();
        Grade g = TlsGradeRules.evaluate(lc, manual ? null : profile, manual);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", inv == null ? (lc == null ? null : lc.getDomain()) : inv.getDomain());
        out.put("port", inv == null || inv.getPort() == null ? 443 : inv.getPort());
        out.put("rubric_version", TlsGradeRules.RUBRIC_VERSION);
        out.put("state", g.state());
        out.put("state_reason", g.stateReason());
        out.put("grade", g.grade());
        out.put("reasons", g.reasons().stream().map(Finding::toJson).toList());
        out.put("decisive", g.decisive().stream().map(Finding::code).toList());
        out.put("notes", g.notes().stream().map(Finding::toJson).toList());
        out.put("profile", manual ? null : profileJson(profile));
        if (lc != null && !manual) {
            Map<String, Object> neg = new LinkedHashMap<>();
            neg.put("tls_version", lc.getTlsVersion());
            neg.put("cipher_suite", lc.getCipherSuite());
            neg.put("tls_mode_used", lc.getTlsModeUsed());
            neg.put("checked_at", lc.getCheckedAt());
            out.put("negotiated", neg);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("status", lc.getHstsStatus());
            h.put("checked_at", lc.getHstsAt());
            Object maxAge = com.sitemonitor.service.CertificateHealthService.parseJsonMap(lc.getHstsPolicy()).get("max_age");
            h.put("max_age_days", maxAge instanceof Number n ? n.longValue() / 86_400L : null);
            h.put("min_days", TlsGradeRules.HSTS_MIN_DAYS);
            out.put("hsts", h);
        } else {
            out.put("negotiated", null);
            out.put("hsts", null);
        }
        List<Map<String, Object>> history = new ArrayList<>();
        TlsGradeStatus st = null;
        if (inv != null && inv.getId() != null) {
            try {
                for (TlsGradeChange c : changeRepo.findTop20ByInventoryIdOrderByChangedAtDescIdDesc(inv.getId())) history.add(changeJson(c));
                st = statusRepo.findById(inv.getId()).orElse(null);
            } catch (Exception e) {
                log.debug("TLS notu geçmişi okunamadı: {}", e.toString());
            }
        }
        out.put("history", history);
        out.put("drop", g.graded() ? dropOf(st, g.grade(), Instant.now()) : null);
        return out;
    }

    static Map<String, Object> profileJson(TlsProfile p) {
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", p.getStatus());
        m.put("probed_at", p.getProbedAt());
        m.put("via", p.getVia());
        m.put("port", p.getPort());
        Map<String, Object> protocols = new LinkedHashMap<>();
        protocols.put("tls13", p.getTls13());
        protocols.put("tls12", p.getTls12());
        protocols.put("tls11", p.getTls11());
        protocols.put("tls10", p.getTls10());
        m.put("protocols", protocols);
        m.put("ocsp_stapling", p.getOcspStapling());
        m.put("weak_cipher", p.getWeakCipher());
        m.put("weak_cipher_suite", p.getWeakCipherSuite());
        m.put("preferred_cipher", p.getPreferredCipher());
        m.put("error", p.getError());
        m.put("duration_ms", p.getDurationMs());
        m.put("trigger", p.getProbeTrigger());
        return m;
    }

    private static Map<String, Object> changeJson(TlsGradeChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", c.getFromGrade());
        m.put("to", c.getToGrade());
        m.put("direction", c.getDirection());
        m.put("at", c.getChangedAt());
        m.put("reasons", splitCodes(c.getReasons()));
        return m;
    }

    // ── Son düşüşler ──────────────────────────────────────────────────────────────────────────

    /** Kapsam süzgecinden ÖNCE okunan en fazla satır (sınır — tablo küçük ama sorgu sınırsız olmasın). */
    static final int DROPS_SCAN_MAX = 500;

    /**
     * Son {@code days} gündeki düşüşler, yeniden eskiye. {@code teamIds} null = tüm takımlar; dolu = SY ya da UG takımı
     * kapsamda olanlar; boş = hiçbiri. Her satır güncel notu ve "toparlandı mı" bilgisini taşır.
     */
    public List<Map<String, Object>> drops(Collection<Long> teamIds, int days, int limit,
                                           Map<Long, String> teamNames) {
        if (teamIds != null && teamIds.isEmpty()) return List.of();
        int d = Math.max(1, Math.min(days, 180));
        int lim = Math.max(1, Math.min(limit, 200));
        String since = ISO.format(Instant.now().minus(d, ChronoUnit.DAYS));
        Set<Long> scope = teamIds == null ? null : new HashSet<>(teamIds);
        List<TlsGradeChange> rows = changeRepo.findRecent(since, TlsGradeChange.DROP, PageRequest.of(0, DROPS_SCAN_MAX));
        List<TlsGradeChange> visible = new ArrayList<>();
        for (TlsGradeChange c : rows) {
            if (scope != null && (c.getTeamId() == null || !scope.contains(c.getTeamId()))
                    && (c.getUgTeamId() == null || !scope.contains(c.getUgTeamId()))) continue;
            visible.add(c);
            if (visible.size() >= lim) break;
        }
        Set<Long> invIds = new HashSet<>();
        for (TlsGradeChange c : visible) if (c.getInventoryId() != null) invIds.add(c.getInventoryId());
        Map<Long, TlsGradeStatus> current = new HashMap<>();
        if (!invIds.isEmpty()) for (TlsGradeStatus s : statusRepo.findAllById(invIds)) current.put(s.getInventoryId(), s);
        List<Map<String, Object>> out = new ArrayList<>(visible.size());
        for (TlsGradeChange c : visible) {
            Map<String, Object> m = changeJson(c);
            m.put("domain", c.getDomain());
            m.put("team_id", c.getTeamId());
            m.put("team_name", c.getTeamId() == null || teamNames == null ? null : teamNames.get(c.getTeamId()));
            TlsGradeStatus s = current.get(c.getInventoryId());
            String now = s == null ? null : s.getGrade();
            m.put("current", now);
            m.put("recovered", now != null && TlsGradeRules.rank(now) >= TlsGradeRules.rank(c.getFromGrade()));
            out.add(m);
        }
        return out;
    }

    /**
     * Profil kapsaması (kapsamdaki aktif AĞ kayıtları): yoklanan / sorunsuz / eksik / başarısız / bekleyen + en son
     * yoklama anı. Elle yüklenen kayıt sayılmaz.
     */
    public Map<String, Object> coverage(List<CertificateInventory> inScope) {
        Set<String> domains = new HashSet<>();
        for (CertificateInventory inv : inScope) if (!inv.isManual() && inv.getDomain() != null) domains.add(inv.getDomain());
        int ok = 0, partial = 0, failed = 0;
        String latest = null;
        if (!domains.isEmpty()) {
            for (TlsProfile p : profileRepo.findByDomainIn(domains)) {
                String st = p.getStatus();
                if (TlsProfile.STATUS_OK.equals(st)) ok++;
                else if (TlsProfile.STATUS_PARTIAL.equals(st)) partial++;
                else failed++;
                if (p.getProbedAt() != null && (latest == null || p.getProbedAt().compareTo(latest) > 0)) latest = p.getProbedAt();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoints", domains.size());
        m.put("ok", ok);
        m.put("partial", partial);
        m.put("failed", failed);
        m.put("pending", Math.max(0, domains.size() - ok - partial - failed));
        m.put("latest_probe_at", latest);
        return m;
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z");
        } catch (Exception e) {
            return null;
        }
    }
}
