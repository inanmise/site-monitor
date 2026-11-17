package com.sitemonitor.service.keyword.content;

import com.sitemonitor.model.KeywordChangeBaseline;
import com.sitemonitor.repository.KeywordChangeBaselineRepository;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * İçerik değişimi temeli servisi (kw sözleşmesi §3.3 / §7.2). Çok pod güvenli:
 * <ul>
 *   <li><b>Otomatik alım</b> ({@link #takeAuto}) — yalnız ZAMANLANMIŞ ve SAĞLIKLI kontrolde (klasik ya da dağıtık iş), temel
 *       yoksa ya da başka ayar özetiyle alınmışsa: insert-or-refresh-stale. İki pod aynı anda alırsa TEK satır kalır (PostgreSQL
 *       {@code ON CONFLICT … DO UPDATE … WHERE spec_hash IS DISTINCT FROM}; taşınabilir yolda koşullu UPDATE + {@code INSERT …
 *       WHERE NOT EXISTS} + yarışta birincil anahtar ihlali yakalanır).</li>
 *   <li><b>Elle alım</b> ({@link #takeManual}) — upsert, son yazan kazanır.</li>
 *   <li><b>Silme</b> ({@link #delete}) — izleme silinince, değişim kapatılınca ya da değişim ayarı değişince.</li>
 * </ul>
 * Elle "Şimdi kontrol et" ve teyit yeniden kontrolü temel ALMAZ (çağıranın kuralı — bkz. sözleşme §7.2).
 */
@Slf4j
@Service
public class KeywordChangeBaselineService {

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";
    /** Otomatik alımın {@code taken_by} değeri. */
    public static final String SYSTEM_USER = "system";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final KeywordChangeBaselineRepository repo;
    private final JdbcTemplate jdbc;
    private Clock clock = Clock.systemUTC();
    /** Lehçe — yalnız BİLİNEN cevap ezberlenir (okunamazsa sonraki çağrıda yeniden sorulur, taşınabilir yol kullanılır). */
    private volatile Boolean postgres;

    @Autowired
    public KeywordChangeBaselineService(KeywordChangeBaselineRepository repo,
                                        @Autowired(required = false) JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    /** Testler için saat. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Testler için lehçe zorlaması. */
    void forceDialect(Boolean postgres) {
        this.postgres = postgres;
    }

    private boolean isPostgres() {
        Boolean p = postgres;
        if (p != null) return p;
        p = jdbc == null ? null : DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (p != null) postgres = p;
        return Boolean.TRUE.equals(p);
    }

    /** Temel satırı (varsa). */
    public Optional<KeywordChangeBaseline> find(Long monitorId) {
        if (monitorId == null) return Optional.empty();
        return repo.findById(monitorId);
    }

    /**
     * Temel satırının METİNSİZ hâli ({@code text} null — kalıcılık bağlamına bağlı olmayan, yazılmayan nesne): liste satırı
     * ve tek satır özeti ({@code change_baseline} — {@link #toApi(KeywordChangeBaseline, boolean)} metinsiz) bunu kullanır;
     * ≤ 64 000 karakterlik metin okunmaz (performans 2026-11-17).
     */
    public Optional<KeywordChangeBaseline> findSummary(Long monitorId) {
        if (monitorId == null) return Optional.empty();
        return Optional.ofNullable(byMonitorIds(java.util.List.of(monitorId)).get(monitorId));
    }

    /** Liste satırı okumasında tek ifadedeki en çok kimlik ({@code IN} listesi). */
    static final int SUMMARY_CHUNK = 500;

    /**
     * Birden çok izlemenin temelleri METİNSİZ ({@code text} null; {@link #findSummary}) — liste ucu. Satır başına sorgu yok:
     * {@value #SUMMARY_CHUNK} kimlik başına tek okuma.
     */
    public java.util.Map<Long, KeywordChangeBaseline> byMonitorIds(java.util.Collection<Long> ids) {
        java.util.Map<Long, KeywordChangeBaseline> out = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        java.util.List<Long> list = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(ids));
        list.removeIf(java.util.Objects::isNull);
        for (int i = 0; i < list.size(); i += SUMMARY_CHUNK) {
            java.util.List<Object[]> rows = repo.findSummaries(list.subList(i, Math.min(list.size(), i + SUMMARY_CHUNK)));
            if (rows == null) continue;
            for (Object[] r : rows) {
                KeywordChangeBaseline b = summaryOf(r);
                if (b != null) out.put(b.getMonitorId(), b);
            }
        }
        return out;
    }

    /** {@code findSummaries} satırı → metinsiz temel nesnesi. */
    private static KeywordChangeBaseline summaryOf(Object[] r) {
        if (r == null || r.length < 10 || !(r[0] instanceof Number id)) return null;
        KeywordChangeBaseline b = new KeywordChangeBaseline();
        b.setMonitorId(id.longValue());
        b.setFingerprint((String) r[1]);
        b.setSpecHash((String) r[2]);
        b.setTextTruncated((Boolean) r[3]);
        b.setTakenAt((String) r[4]);
        b.setTakenBy((String) r[5]);
        b.setTakenByName((String) r[6]);
        b.setSource((String) r[7]);
        b.setBytes(r[8] instanceof Number n ? n.longValue() : null);
        b.setVersion(r[9] instanceof Number v ? v.longValue() : null);
        return b;
    }

    /**
     * Karşılaştırma görünümü; temel yoksa {@code null}. Metin TEMBEL (performans 2026-11-17): her değişim ölçümünde yalnız
     * metinsiz özet okunur; temel metni yalnız parmak izleri farklıyken (fark özeti) ve satır hâlâ aynı parmak iziyle
     * duruyorsa okunur — aynı parmak izi = aynı saklı metin, sonuç eskisiyle aynı. Satır bu arada başka içerikle yeniden
     * alınmış / silinmişse okuma istisna verir: değişim bu kontrolde değerlendirilmez (çağıranın genel kuralı).
     */
    public KeywordChangeDetector.BaselineView view(Long monitorId) {
        return findSummary(monitorId).map(b -> new KeywordChangeDetector.BaselineView(b.getFingerprint(), b.getSpecHash(),
                null, Boolean.TRUE.equals(b.getTextTruncated()), b.getTakenAt(), b.getSource(),
                new KeywordChangeDetector.LazyText(() -> textOf(b.getMonitorId(), b.getFingerprint())))).orElse(null);
    }

    /** Temel metni — satır hâlâ aynı parmak iziyle duruyorsa; değilse istisna (yeniden alınmış / silinmiş temel). */
    private String textOf(Long monitorId, String fingerprint) {
        if (fingerprint == null) throw new IllegalStateException("temel parmak izi yok: " + monitorId);
        java.util.List<Object[]> rows = repo.findTextIfFingerprint(monitorId, fingerprint);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("içerik temeli okuma sırasında değişti ya da silindi: " + monitorId);
        }
        Object[] r = rows.get(0);
        return r == null || r.length < 2 ? null : (String) r[1];
    }

    /**
     * Otomatik temel (kw son inceleme O-4) — yoksa ekle; varsa yalnız ayar özeti ({@code specHash}) FARKLIYSA yenile (eskimiş
     * algoritma / maske / seçiciyle alınmış satır, ayar değişimi ile "yeni temel al" yarışı, temel silme hatası "temel bekleniyor"da
     * takılı bırakmasın). Aynı özette iki pod → tek satır, ikinci çağrı değiştirmez. PostgreSQL {@code ON CONFLICT … DO UPDATE
     * … WHERE spec_hash IS DISTINCT FROM}; H2 / bilinmeyen lehçe koşullu UPDATE → yoksa {@code INSERT … WHERE NOT EXISTS}.
     *
     * @return bu çağrı satırı EKLEDİ ya da YENİLEDİYSE true (aynı özetle zaten varsa / yarışı kaybettiyse false)
     */
    public boolean takeAuto(Long monitorId, KeywordChangeDetector.Snapshot snap, long bytes, String specHash) {
        if (monitorId == null || snap == null) return false;
        String now = ISO.format(clock.instant());
        try {
            if (isPostgres()) {
                return repo.insertOrRefreshStalePg(monitorId, snap.fingerprint(), specHash, snap.text(), snap.textTruncated(),
                        bytes, now, SYSTEM_USER, null, SOURCE_AUTO) > 0;
            }
            if (repo.refreshIfStale(monitorId, snap.fingerprint(), specHash, snap.text(), snap.textTruncated(), bytes, now,
                    SYSTEM_USER, null, SOURCE_AUTO) > 0) return true;
            return repo.insertIfAbsentPortable(monitorId, snap.fingerprint(), specHash, snap.text(), snap.textTruncated(),
                    bytes, now, SYSTEM_USER, null, SOURCE_AUTO) > 0;
        } catch (DataIntegrityViolationException raced) {
            log.debug("Anahtar kelime içerik temeli yarışı (monitor {}) — öteki pod aldı", monitorId);
            return false;
        }
    }

    /** Elle temel — ekle ya da üzerine yaz (son yazan kazanır). @return yazılan satır. */
    public KeywordChangeBaseline takeManual(Long monitorId, KeywordChangeDetector.Snapshot snap, long bytes, String specHash,
                                            String takenBy, String takenByName) {
        if (monitorId == null || snap == null) throw new IllegalArgumentException("monitorId / snapshot boş");
        String now = ISO.format(clock.instant());
        String by = takenBy == null || takenBy.isBlank() ? SYSTEM_USER : clip(takenBy, 100);
        String name = takenByName == null || takenByName.isBlank() ? null : clip(takenByName, 255);
        if (isPostgres()) {
            repo.upsertPg(monitorId, snap.fingerprint(), specHash, snap.text(), snap.textTruncated(), bytes, now, by, name,
                    SOURCE_MANUAL);
        } else if (repo.overwrite(monitorId, snap.fingerprint(), specHash, snap.text(), snap.textTruncated(), bytes, now, by,
                name, SOURCE_MANUAL) == 0) {
            int inserted;
            try {
                inserted = repo.insertIfAbsentPortable(monitorId, snap.fingerprint(), specHash, snap.text(),
                        snap.textTruncated(), bytes, now, by, name, SOURCE_MANUAL);
            } catch (DataIntegrityViolationException raced) {
                inserted = 0;
            }
            if (inserted == 0) {   // araya otomatik alım girdi — elle alım kazanır
                repo.overwrite(monitorId, snap.fingerprint(), specHash, snap.text(), snap.textTruncated(), bytes, now, by,
                        name, SOURCE_MANUAL);
            }
        }
        return repo.findById(monitorId).orElseThrow(() -> new IllegalStateException("temel yazılamadı: " + monitorId));
    }

    /** Temeli siler. @return silinen satır var mıydı. */
    public boolean delete(Long monitorId) {
        if (monitorId == null) return false;
        return repo.deleteByMonitorId(monitorId) > 0;
    }

    /**
     * API biçimi ({@code {taken_at, taken_by_name, source, bytes, text_truncated, line_count, text}}). {@code withText} false →
     * {@code text} ve {@code line_count} yazılmaz (liste satırı {@code change_baseline}).
     */
    public static Map<String, Object> toApi(KeywordChangeBaseline b, boolean withText) {
        if (b == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taken_at", b.getTakenAt());
        m.put("taken_by_name", b.getTakenByName());
        m.put("source", b.getSource());
        m.put("bytes", b.getBytes());
        if (withText) {
            m.put("text_truncated", Boolean.TRUE.equals(b.getTextTruncated()));
            String t = b.getText() == null ? "" : b.getText();
            m.put("line_count", t.isEmpty() ? 0 : KeywordChangeDetector.splitLines(t).size());
            m.put("text", t);
        }
        return m;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
