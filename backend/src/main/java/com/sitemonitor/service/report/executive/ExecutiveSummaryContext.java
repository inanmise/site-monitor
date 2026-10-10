package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bölüm hesabının girdisi: AY SINIRLARI (Europe/Istanbul takvimi), üretim anı, hedefler, KAPSAM ve bölümler arasında
 * PAYLAŞILAN tembel veri (envanter + son sertifika durumu — her biri özet başına en fazla BİR kez okunur).
 *
 * <p><b>Saat dilimi tuzağı (CLAUDE.md):</b> zaman damgaları UTC saklanır. "Eylül 2026" İstanbul'da 1 Eylül 00:00'da
 * başlar = 31 Ağustos 21:00 UTC. Bütün sorgular {@link #from()} / {@link #to()} anlarını kullanır; günlük UTC
 * kovası değil.
 *
 * <h2>Kapsam (2026-10-10, kullanıcı isteği: takıma özel özet)</h2>
 * {@link #scopeTeamId()} {@code null} = KURUM GENELİ (bugünkü davranış, çıktı birebir aynı). Dolu = yalnız o takım:
 * <ul>
 *   <li>{@link #inventory()} / {@link #latestCerts()} yalnız kapsamdaki envanter satırlarını döndürür — envanter satırı
 *       SY ({@code team_id}) YA DA UG ({@code ug_team_id}) takımı kapsam takımıysa kapsamdadır (alarm sahipliğiyle aynı
 *       kural). Süzülmemiş haller {@link #allInventory()} / {@link #allLatestCerts()} (eşleme için).</li>
 *   <li>Takım kimliği taşıyan satırlar (izleme, alarm) {@link #teamInScope(Long)} ile süzülür; alan adı üzerinden
 *       envantere bağlanan satırlar {@link #domainInScope(String)} ile.</li>
 *   <li>Takım kapsamında "takımlara göre" kırılımlar anlamsızdır (tek satır) — bölümler {@link #teamScoped()} ile
 *       takım tablosunu / takım sayısı hükmünü atlar.</li>
 * </ul>
 *
 * <h2>Koşu boyu paylaşım</h2>
 * Aylık gönderim aynı ay için kurum + N takım özeti hesaplar. Kurum geneli ham satırlar (envanter, son sertifikalar,
 * ay boyu toplu sorgular) {@link #shared(String, Supplier)} ile KOŞU başına bir kez okunur ve her takım bağlamı aynı
 * haritayı alır; takım süzmesi bellekte yapılır. Ekrandaki tek özet için harita bağlama özeldir.
 */
public final class ExecutiveSummaryContext {

    public static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    /** Kayıtlı damgaların biçimi ({@code alert_events.created_at} vb.) — UTC, bölge eki yok. */
    public static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Saatlik rollup kovası ({@code monitor_check_hourly.hour_bucket}, 13 karakter, UTC). */
    public static final DateTimeFormatter HOUR_BUCKET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    /** Envanter satırının özetin kullandığı alanları. */
    public record InventoryRow(String domain, Long teamId, Long ugTeamId, Integer tier, String groupName,
                               String renewalPlannedAt) { }

    private final YearMonth month;
    private final Instant from;
    private final Instant to;
    private final Instant prevFrom;
    private final Instant now;
    private final double availabilityTarget;
    private final int renewalTargetDays;
    private final Long scopeTeamId;
    private final String scopeTeamName;
    private final Map<String, Object> shared;
    private final Supplier<List<CertificateDto>> latestCerts;
    private final Supplier<Map<String, InventoryRow>> inventory;
    private final Supplier<Map<Long, String>> teamNames;
    private final Supplier<Map<String, InventoryRow>> scopedInventory;
    private final Supplier<List<CertificateDto>> scopedLatest;

    /** Kurum geneli bağlam (bugünkü kurucu). */
    public ExecutiveSummaryContext(YearMonth month, Instant now, double availabilityTarget, int renewalTargetDays,
                                   Supplier<List<CertificateDto>> latestCerts,
                                   Supplier<Map<String, InventoryRow>> inventory,
                                   Supplier<Map<Long, String>> teamNames) {
        this(month, now, availabilityTarget, renewalTargetDays, latestCerts, inventory, teamNames, null, null, null);
    }

    /**
     * Kapsamlı bağlam.
     *
     * @param scopeTeamId   null = kurum geneli; dolu = yalnız bu takım
     * @param scopeTeamName kapsam takımının adı (posta/PDF başlığı; null olabilir)
     * @param shared        koşu boyu paylaşılan bellek (null = bağlama özel yeni harita)
     */
    public ExecutiveSummaryContext(YearMonth month, Instant now, double availabilityTarget, int renewalTargetDays,
                                   Supplier<List<CertificateDto>> latestCerts,
                                   Supplier<Map<String, InventoryRow>> inventory,
                                   Supplier<Map<Long, String>> teamNames,
                                   Long scopeTeamId, String scopeTeamName, Map<String, Object> shared) {
        this.month = month;
        this.now = now;
        this.from = monthStart(month);
        this.to = monthStart(month.plusMonths(1));
        this.prevFrom = monthStart(month.minusMonths(1));
        this.availabilityTarget = availabilityTarget;
        this.renewalTargetDays = renewalTargetDays;
        this.scopeTeamId = scopeTeamId;
        this.scopeTeamName = scopeTeamName;
        this.shared = shared == null ? new ConcurrentHashMap<>() : shared;
        this.latestCerts = memo(latestCerts);
        this.inventory = memo(inventory);
        this.teamNames = memo(teamNames);
        this.scopedInventory = memo(this::filterInventory);
        this.scopedLatest = memo(this::filterLatest);
    }

    /** Ayın İstanbul'daki ilk anı (UTC Instant). */
    public static Instant monthStart(YearMonth m) {
        return m.atDay(1).atStartOfDay(IST).toInstant();
    }

    public YearMonth month() { return month; }
    /** Ayın başı (dahil). */
    public Instant from() { return from; }
    /** Ayın sonu = sonraki ayın başı (hariç). */
    public Instant to() { return to; }
    /** Önceki ayın başı (önceki ay = [prevFrom, from)). */
    public Instant prevFrom() { return prevFrom; }
    public Instant now() { return now; }
    /** Ay bitti mi? */
    public boolean complete() { return !now.isBefore(to); }
    /** Ölçümün bittiği an: ay bittiyse ay sonu, değilse şimdi. */
    public Instant measuredUntil() { return complete() ? to : (now.isBefore(from) ? from : now); }
    /** Ayın şimdiye dek geçen (ya da tamamı) dakika sayısı. */
    public long elapsedMinutes() { return Math.max(0, Duration.between(from, measuredUntil()).toMinutes()); }
    /** Ayın gün sayısı (tam ay) ya da şimdiye dek başlamış gün sayısı (devam eden ay). */
    public int elapsedDays() {
        if (complete()) return month.lengthOfMonth();
        long d = Duration.between(from, measuredUntil()).toDays() + 1;
        return (int) Math.max(1, Math.min(month.lengthOfMonth(), d));
    }
    public double availabilityTarget() { return availabilityTarget; }
    /** Yenileme hedef süresi (gün); 0 = Vade Takvimi'nin tier bazlı yenileme süresi. */
    public int renewalTargetDays() { return renewalTargetDays; }

    // ── Kapsam ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Takım kapsamlı özet mi? */
    public boolean teamScoped() { return scopeTeamId != null; }
    /** Kapsam takımı (kurum geneli → null). */
    public Long scopeTeamId() { return scopeTeamId; }
    /** Kapsam takımının adı (kurum geneli → null). */
    public String scopeTeamName() { return scopeTeamName; }

    /** Takım kimliği taşıyan satır kapsamda mı: kurum geneli → her satır (sahipsiz dahil); takım → yalnız o takım. */
    public boolean teamInScope(Long teamId) {
        return scopeTeamId == null || Objects.equals(scopeTeamId, teamId);
    }

    /** Envanter satırı kapsamda mı (SY ya da UG kapsam takımı). */
    public boolean inventoryInScope(InventoryRow r) {
        if (scopeTeamId == null) return true;
        return r != null && (Objects.equals(scopeTeamId, r.teamId()) || Objects.equals(scopeTeamId, r.ugTeamId()));
    }

    /** Alan adı kapsamdaki aktif envanterde mi (kurum geneli → her zaman true). */
    public boolean domainInScope(String domain) {
        if (scopeTeamId == null) return true;
        return domain != null && inventory().containsKey(domain);
    }

    /** Kapsamdaki son sertifika durumu (kurum geneli → hepsi). */
    public List<CertificateDto> latestCerts() { return scopedLatest.get(); }
    /** Kapsamdaki aktif envanter (kurum geneli → hepsi). */
    public Map<String, InventoryRow> inventory() { return scopedInventory.get(); }
    /** Süzülmemiş son sertifika durumu (alan adı → takım eşlemesi gibi işler için). */
    public List<CertificateDto> allLatestCerts() { return latestCerts.get(); }
    /** Süzülmemiş aktif envanter. */
    public Map<String, InventoryRow> allInventory() { return inventory.get(); }
    public Map<Long, String> teamNames() { return teamNames.get(); }

    /**
     * Koşu boyu paylaşılan tembel değer — aynı {@code key} ile ikinci çağrı yükleyiciyi ÇALIŞTIRMAZ. Yalnız kurum
     * geneli (süzülmemiş) ham veri için: takım süzmesi çağıranda yapılır, böylece aynı harita bütün takım bağlamlarına
     * verilebilir. Yükleyici null dönerse null saklanır (yeniden denenmez).
     */
    public <T> T shared(String key, Supplier<T> loader) {
        return sharedValue(shared, key, loader);
    }

    /** {@link #shared} gövdesi — servis bağlamı kurmadan önce aynı haritaya taban veriyi (envanter vb.) bağlar. */
    @SuppressWarnings("unchecked")
    static <T> T sharedValue(Map<String, Object> map, String key, Supplier<T> loader) {
        // computeIfAbsent DEĞİL: yükleyici başka bir paylaşılan değeri isteyebilir (ConcurrentHashMap'te özyinelemeli
        // güncelleme IllegalStateException). Eşzamanlı iki ilk çağrı iki kez yükleyebilir; ilk yazılan kazanır.
        Object v = map.get(key);
        if (v == null) {
            Holder h = new Holder(loader == null ? null : loader.get());
            Object prev = map.putIfAbsent(key, h);
            v = prev == null ? h : prev;
        }
        return (T) ((Holder) v).value;
    }

    /** Koşu boyu harita (servis yeni takım bağlamlarına aynı haritayı verir). */
    Map<String, Object> sharedMap() { return shared; }

    private record Holder(Object value) { }

    private Map<String, InventoryRow> filterInventory() {
        Map<String, InventoryRow> all = inventory.get();
        if (all == null) return Map.of();
        if (scopeTeamId == null) return all;
        Map<String, InventoryRow> out = new LinkedHashMap<>();
        for (Map.Entry<String, InventoryRow> e : all.entrySet()) {
            if (inventoryInScope(e.getValue())) out.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    private List<CertificateDto> filterLatest() {
        List<CertificateDto> all = latestCerts.get();
        if (all == null) return List.of();
        if (scopeTeamId == null) return all;
        Map<String, InventoryRow> inv = inventory();
        List<CertificateDto> out = new ArrayList<>();
        for (CertificateDto d : all) {
            if (d != null && d.getDomain() != null && inv.containsKey(d.getDomain())) out.add(d);
        }
        return Collections.unmodifiableList(out);
    }

    public String fromIso() { return UTC_ISO.format(from); }
    public String toIso() { return UTC_ISO.format(to); }
    public String prevFromIso() { return UTC_ISO.format(prevFrom); }
    public String nowIso() { return UTC_ISO.format(now); }

    /** Tek seferlik tembel değer — bölümler aynı veriyi ikinci kez okumasın. */
    private static <T> Supplier<T> memo(Supplier<T> s) {
        return new Supplier<>() {
            private boolean done;
            private T value;
            @Override
            public synchronized T get() {
                if (!done) {
                    value = s == null ? null : s.get();
                    done = true;
                }
                return value;
            }
        };
    }
}
