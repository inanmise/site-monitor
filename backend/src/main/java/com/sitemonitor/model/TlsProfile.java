package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir ağ uç noktasının TLS PROFİLİ (2026-10-10, TLS yapılandırma notu): sunucunun hangi protokol sürümlerini KABUL ETTİĞİ,
 * OCSP zımbalama (stapling) sunup sunmadığı ve zayıf şifre takımı kabul edip etmediği.
 *
 * <p><b>Neden ayrı ölçüm.</b> Saatlik sertifika kontrolü {@code TLS_MODE=browser} ile TLS 1.2'ye sabitli tek bir el
 * sıkışması yapar; anlaşılan sürüm sunucunun en iyisi değildir, TLS 1.0/1.1'in açık olup olmadığını ve zımbalamayı hiç
 * göstermez. Bu satır, günde bir kez çalışan ayrı ve hafif bir yoklamanın ({@code TlsProfileProbeService}: ham ClientHello,
 * el sıkışması TAMAMLANMAZ) sonucudur. JVM'in güvenlik ayarlarına dokunulmaz.
 *
 * <p>Üç değerli alanlar {@link #YES} / {@link #NO} / {@link #UNKNOWN}: yoklama başarısızsa (vekil engeli, zaman aşımı)
 * değer UNKNOWN kalır — tahmin edilmez. Alan adı başına TEK satır (latest_checks ile aynı anahtar); envanterden silinen
 * alanın satırını gece temizliği atar ({@code RetentionCatalog} "tls-profiles-orphan"). Yeni tablo — tüm kolonlar boş
 * olabilir (dolu tabloya NOT NULL eklenmez).
 */
@Entity
@Table(name = "tls_profiles")
@Data
@NoArgsConstructor
public class TlsProfile {

    public static final String YES = "YES";
    public static final String NO = "NO";
    public static final String UNKNOWN = "UNKNOWN";

    /** Yoklama sonucu: tüm sürümler belirlendi. */
    public static final String STATUS_OK = "OK";
    /** En az bir sürüm kabul edildi ama bazı sorular cevapsız kaldı (zaman aşımı vb.). */
    public static final String STATUS_PARTIAL = "PARTIAL";
    /** Hiçbir sürümle el sıkışması başlatılamadı (bağlantı yok, vekil reddetti, sunucu TLS konuşmuyor). */
    public static final String STATUS_FAILED = "FAILED";
    /** SSRF politikası hedefi engelledi ya da host çözülemedi — bağlanılmadı. */
    public static final String STATUS_BLOCKED = "BLOCKED";

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    @Id
    @Column(length = 253)
    private String domain;

    /** Yoklanan port (envanterin portu; değişirse profil yeniden yoklanır). */
    private Integer port;

    /** "direct" | "proxy" — kontrolün kendisiyle AYNI karar (use_proxy ∧ vekil tanımlı ∧ NO_PROXY dışı). */
    @Column(length = 10)
    private String via;

    @Column(length = 10)
    private String tls10;
    @Column(length = 10)
    private String tls11;
    @Column(length = 10)
    private String tls12;
    @Column(length = 10)
    private String tls13;

    /** Sunucu TLS 1.2'de CertificateStatus (OCSP yanıtı) gönderdi mi. TLS 1.3'te şifreli olduğundan ölçülemez → UNKNOWN. */
    @Column(length = 10)
    private String ocspStapling;

    /** İstemci YALNIZ zayıf takımlar (RC4/3DES/DES/EXPORT/NULL/anon) önerdiğinde sunucu birini kabul etti mi. */
    @Column(length = 10)
    private String weakCipher;

    /** Kabul edilen zayıf takımın adı (IANA). */
    @Column(length = 100)
    private String weakCipherSuite;

    /** Geniş öneri listesiyle TLS 1.2'de sunucunun SEÇTİĞİ takım (sunucu tercihi). */
    @Column(length = 100)
    private String preferredCipher;

    /** {@link #STATUS_OK} / {@link #STATUS_PARTIAL} / {@link #STATUS_FAILED} / {@link #STATUS_BLOCKED}. */
    @Column(length = 20)
    private String status;

    /** Kısa hata özeti (yalnız FAILED / BLOCKED / PARTIAL); asla yığın izi değil. */
    @Column(length = 255)
    private String error;

    /** Yoklama başına kısa JSON (sürüm, sonuç, uyarı kodu, süre) — ayrıntı penceresi için; ≤ 2000 karakter. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** ISO-8601 UTC (yyyy-MM-dd'T'HH:mm:ss). */
    @Column(length = 19)
    private String probedAt;

    private Integer durationMs;

    /** {@link #TRIGGER_SCHEDULED} | {@link #TRIGGER_MANUAL}. */
    @Column(length = 10)
    private String probeTrigger;

    /** Elle yeniden tarayan kullanıcı (zamanlanmışta null). */
    @Column(length = 100)
    private String probedBy;
}
