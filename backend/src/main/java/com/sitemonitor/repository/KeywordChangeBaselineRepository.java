package com.sitemonitor.repository;

import com.sitemonitor.model.KeywordChangeBaseline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * İçerik değişimi temelleri (kw sözleşmesi §3.3). Yazımlar KOŞULLU SQL — varlık kaydı değil (iki pod aynı anda otomatik temel
 * alırsa tek satır kalır; bayat varlık bir başkasının temelini ezmez). Lehçe seçimi {@code KeywordChangeBaselineService}'te:
 * PostgreSQL {@code ON CONFLICT}, H2 / bilinmeyen lehçe {@code INSERT … SELECT … WHERE NOT EXISTS} + koşullu UPDATE.
 *
 * <p>Ekleme yöntemleri kendi işleminde ({@code REQUIRES_NEW}): olası tekillik ihlali (yalnız taşınabilir yolda, yarışta) çağıranın
 * işlemini zehirlemez (PostgreSQL 25P02).
 */
public interface KeywordChangeBaselineRepository extends JpaRepository<KeywordChangeBaseline, Long> {

    String COLS = "monitor_id, fingerprint, spec_hash, text, text_truncated, bytes, taken_at, taken_by, taken_by_name, source, version";

    /** PostgreSQL: yoksa ekle (yarışta tek satır; ihlal fırlamaz). */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "INSERT INTO keyword_change_baselines (" + COLS + ") VALUES (:id, :fp, :spec, :text, :truncated, :bytes, "
            + ":takenAt, :takenBy, :takenByName, :source, 0) ON CONFLICT (monitor_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsentPg(@Param("id") Long id, @Param("fp") String fingerprint, @Param("spec") String specHash,
                         @Param("text") String text, @Param("truncated") Boolean truncated, @Param("bytes") Long bytes,
                         @Param("takenAt") String takenAt, @Param("takenBy") String takenBy,
                         @Param("takenByName") String takenByName, @Param("source") String source);

    /**
     * PostgreSQL otomatik alım (kw son inceleme O-4): yoksa ekle; VARSA yalnız ayar özeti ({@code spec_hash}) FARKLIYSA yenile
     * (eskimiş algoritma / maske / seçiciyle alınmış temel "temel bekleniyor"da takılı kalmasın). Aynı özette iki pod → tek satır,
     * ikinci çağrı değiştirmez (0 satır). @return eklenen ya da yenilenen satır sayısı.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "INSERT INTO keyword_change_baselines (" + COLS + ") VALUES (:id, :fp, :spec, :text, :truncated, :bytes, "
            + ":takenAt, :takenBy, :takenByName, :source, 0) ON CONFLICT (monitor_id) DO UPDATE SET "
            + "fingerprint = EXCLUDED.fingerprint, spec_hash = EXCLUDED.spec_hash, text = EXCLUDED.text, "
            + "text_truncated = EXCLUDED.text_truncated, bytes = EXCLUDED.bytes, taken_at = EXCLUDED.taken_at, "
            + "taken_by = EXCLUDED.taken_by, taken_by_name = EXCLUDED.taken_by_name, source = EXCLUDED.source, "
            + "version = COALESCE(keyword_change_baselines.version, 0) + 1 "
            + "WHERE keyword_change_baselines.spec_hash IS DISTINCT FROM EXCLUDED.spec_hash", nativeQuery = true)
    int insertOrRefreshStalePg(@Param("id") Long id, @Param("fp") String fingerprint, @Param("spec") String specHash,
                               @Param("text") String text, @Param("truncated") Boolean truncated, @Param("bytes") Long bytes,
                               @Param("takenAt") String takenAt, @Param("takenBy") String takenBy,
                               @Param("takenByName") String takenByName, @Param("source") String source);

    /** Taşınabilir otomatik alımın ilk adımı (O-4): satır VARSA ve ayar özeti farklıysa (ya da boşsa) yenile; 0 = yok / aynı özet. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "UPDATE keyword_change_baselines SET fingerprint = :fp, spec_hash = :spec, text = :text, "
            + "text_truncated = :truncated, bytes = :bytes, taken_at = :takenAt, taken_by = :takenBy, "
            + "taken_by_name = :takenByName, source = :source, version = COALESCE(version, 0) + 1 "
            + "WHERE monitor_id = :id AND (spec_hash IS NULL OR spec_hash <> :spec)", nativeQuery = true)
    int refreshIfStale(@Param("id") Long id, @Param("fp") String fingerprint, @Param("spec") String specHash,
                       @Param("text") String text, @Param("truncated") Boolean truncated, @Param("bytes") Long bytes,
                       @Param("takenAt") String takenAt, @Param("takenBy") String takenBy,
                       @Param("takenByName") String takenByName, @Param("source") String source);

    /** H2 / bilinmeyen lehçe: yoksa ekle (yarışta birincil anahtar ihlali olası — çağıran yakalar). */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "INSERT INTO keyword_change_baselines (" + COLS + ") SELECT :id, :fp, :spec, :text, :truncated, :bytes, "
            + ":takenAt, :takenBy, :takenByName, :source, 0 FROM (VALUES (1)) v "
            + "WHERE NOT EXISTS (SELECT 1 FROM keyword_change_baselines b WHERE b.monitor_id = :id)", nativeQuery = true)
    int insertIfAbsentPortable(@Param("id") Long id, @Param("fp") String fingerprint, @Param("spec") String specHash,
                               @Param("text") String text, @Param("truncated") Boolean truncated, @Param("bytes") Long bytes,
                               @Param("takenAt") String takenAt, @Param("takenBy") String takenBy,
                               @Param("takenByName") String takenByName, @Param("source") String source);

    /** PostgreSQL: ekle ya da üzerine yaz (elle alım — son yazan kazanır). */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO keyword_change_baselines (" + COLS + ") VALUES (:id, :fp, :spec, :text, :truncated, :bytes, "
            + ":takenAt, :takenBy, :takenByName, :source, 0) ON CONFLICT (monitor_id) DO UPDATE SET "
            + "fingerprint = EXCLUDED.fingerprint, spec_hash = EXCLUDED.spec_hash, text = EXCLUDED.text, "
            + "text_truncated = EXCLUDED.text_truncated, bytes = EXCLUDED.bytes, taken_at = EXCLUDED.taken_at, "
            + "taken_by = EXCLUDED.taken_by, taken_by_name = EXCLUDED.taken_by_name, source = EXCLUDED.source, "
            + "version = COALESCE(keyword_change_baselines.version, 0) + 1", nativeQuery = true)
    int upsertPg(@Param("id") Long id, @Param("fp") String fingerprint, @Param("spec") String specHash,
                 @Param("text") String text, @Param("truncated") Boolean truncated, @Param("bytes") Long bytes,
                 @Param("takenAt") String takenAt, @Param("takenBy") String takenBy,
                 @Param("takenByName") String takenByName, @Param("source") String source);

    /** Var olan temelin üzerine yaz (taşınabilir upsert'in ilk adımı); 0 = satır yok. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE keyword_change_baselines SET fingerprint = :fp, spec_hash = :spec, text = :text, "
            + "text_truncated = :truncated, bytes = :bytes, taken_at = :takenAt, taken_by = :takenBy, "
            + "taken_by_name = :takenByName, source = :source, version = COALESCE(version, 0) + 1 WHERE monitor_id = :id",
            nativeQuery = true)
    int overwrite(@Param("id") Long id, @Param("fp") String fingerprint, @Param("spec") String specHash,
                  @Param("text") String text, @Param("truncated") Boolean truncated, @Param("bytes") Long bytes,
                  @Param("takenAt") String takenAt, @Param("takenBy") String takenBy,
                  @Param("takenByName") String takenByName, @Param("source") String source);

    /** İzlemenin temelini siler; silinen satır sayısı. */
    @Modifying
    @Transactional
    @Query("DELETE FROM KeywordChangeBaseline b WHERE b.monitorId = :id")
    int deleteByMonitorId(@Param("id") Long id);
}
