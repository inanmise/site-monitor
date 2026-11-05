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
 *   <li><b>Otomatik alım</b> ({@link #takeAuto}) — yalnız ZAMANLANMIŞ kontrolde (klasik ya da dağıtık iş), temel yoksa:
 *       insert-if-absent. İki pod aynı anda alırsa TEK satır kalır (PostgreSQL {@code ON CONFLICT DO NOTHING}; taşınabilir yolda
 *       {@code INSERT … WHERE NOT EXISTS} + yarışta birincil anahtar ihlali yakalanır).</li>
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

    /** Karşılaştırma görünümü; temel yoksa {@code null}. */
    public KeywordChangeDetector.BaselineView view(Long monitorId) {
        return find(monitorId).map(b -> new KeywordChangeDetector.BaselineView(b.getFingerprint(), b.getSpecHash(),
                b.getText(), Boolean.TRUE.equals(b.getTextTruncated()))).orElse(null);
    }

    /**
     * Otomatik temel — yoksa ekle. @return bu çağrı satırı EKLEDİYSE true (zaten varsa / yarışı kaybettiyse false).
     */
    public boolean takeAuto(Long monitorId, KeywordChangeDetector.Snapshot snap, long bytes, String specHash) {
        if (monitorId == null || snap == null) return false;
        String now = ISO.format(clock.instant());
        try {
            int n = isPostgres()
                    ? repo.insertIfAbsentPg(monitorId, snap.fingerprint(), specHash, snap.text(), snap.textTruncated(), bytes,
                            now, SYSTEM_USER, null, SOURCE_AUTO)
                    : repo.insertIfAbsentPortable(monitorId, snap.fingerprint(), specHash, snap.text(), snap.textTruncated(),
                            bytes, now, SYSTEM_USER, null, SOURCE_AUTO);
            return n > 0;
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
