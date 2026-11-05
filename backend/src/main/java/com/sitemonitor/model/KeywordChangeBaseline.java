package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Anahtar kelime izlemesinin İÇERİK DEĞİŞİMİ temeli (kw sözleşmesi §3.3, D2) — izleme başına tek satır. Tablo ddl-auto ile
 * doğar; ad kalıcıdır.
 *
 * <p>Yazımlar varlık kaydıyla DEĞİL, depodaki koşullu SQL ile ({@code KeywordChangeBaselineService}): otomatik alım
 * insert-or-refresh-stale (iki pod aynı anda alırsa tek satır kalır; eskimiş özetli satır yenilenir), elle alım upsert, silme izleme silinince / değişim kapatılınca /
 * değişim ayarı değişince. {@code spec_hash} güncel ayarın özetiyle uyuşmazsa temel YOK sayılır.
 */
@Entity
@Table(name = "keyword_change_baselines")
@Data
@NoArgsConstructor
@DynamicUpdate
public class KeywordChangeBaseline {

    @Id
    @Column(name = "monitor_id")
    private Long monitorId;

    /** Normalize + maskeli TÜM metnin SHA-256'sı (onaltılık). */
    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    /** Maske + seçici + algoritma sürümünün özeti (16 onaltılık). */
    @Column(name = "spec_hash", length = 16)
    private String specHash;

    /** Normalize + maskeli metin (≤ 64 000 karakter) — fark görünümü için. */
    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    @Column(name = "text_truncated")
    private Boolean textTruncated;

    /** Temel alındığında okunan ham gövde boyutu (bayt). */
    @Column(name = "bytes")
    private Long bytes;

    /** UTC ISO ({@code yyyy-MM-dd'T'HH:mm:ss}). */
    @Column(name = "taken_at", length = 30)
    private String takenAt;

    /** Alan kullanıcı adı; otomatik alımda {@code system}. */
    @Column(name = "taken_by", length = 100)
    private String takenBy;

    /** Alanın görünen adı (alım anındaki); otomatik alımda null. */
    @Column(name = "taken_by_name")
    private String takenByName;

    /** {@code AUTO} | {@code MANUAL}. */
    @Column(name = "source", length = 10)
    private String source;

    @Version
    @Column(name = "version")
    private Long version;
}
