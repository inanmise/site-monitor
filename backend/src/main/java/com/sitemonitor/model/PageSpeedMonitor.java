package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sayfa Hızı (Page Speed) monitörü: bir sayfanın NE KADAR SÜREDE yüklendiğini ve NE KADAR AĞIR olduğunu
 * düzenli aralıkla ölçer — TTFB, toplam yükleme süresi, transfer edilen bayt ve istek sayısı.
 *
 * <p>{@link PageMonitor} (Sayfa Bütünlüğü) ile aynı sayfayı gezer ama farklı soruyu sorar: o "kaynak KIRIK mı",
 * bu "kaynak NE KADAR AĞIR". {@link HttpMonitor} ise yalnız TEK isteğin yanıt süresini bilir — sayfaya 800 KB'lık
 * bir kütüphane eklendiğinde yeşil kalmaya devam eder; bu izleme tam olarak o boşluğu kapatır.
 *
 * <p><b>Ölçümün dürüst sınırı:</b> ölçüm gerçek bir tarayıcıda değil, HTML + alt kaynakların sunucudan
 * çekilmesiyle yapılır. JavaScript ÇALIŞMAZ; dolayısıyla LCP/CLS gibi Core Web Vitals metrikleri iddia
 * edilmez. Ölçülen şey "sunucu ne kadar sürede veriyor ve sayfa ne kadar ağır".
 *
 * <p><b>Ağ yolu:</b> ölçüm her zaman pod'dan DOĞRUDAN gider (proxy yok) — proxy üzerinden geçen bir ölçüm
 * proxy'nin gecikmesini sayfaya fatura eder ve rakamı yanıltıcı kılar.
 *
 * <p>İskeleti {@link PageMonitor} ile aynıdır: ISO String zaman, {@code teamId} sahiplik, eşik/recovery
 * durumu entity'de {@code confirm*}/{@code recovery*} olarak taşınır.
 */
@Entity
@Table(name = "pagespeed_monitors")
@Data
@NoArgsConstructor
public class PageSpeedMonitor implements MonitorAlertPrefs, MonitorSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    /** Mantıksal grup (ör. "Internet Şubesi") — filtreleme/gruplama; serbest-form. */
    @Column(name = "group_name")
    private String groupName;

    /** Sorumlu takım — alarm yönlendirmesi. */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    /** Kontrol aralığı. Varsayılan 30 dk; TABAN 5 dk ({@link #MIN_INTERVAL_SECONDS}) — bir kontrol onlarca
     *  istek demek, tek pod'da dakikalık ölçüm hem bizi hem izlenen sistemi boğar. */
    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 1800;

    /** İstek başına zaman aşımı (ana sayfa + her alt kaynak). */
    @Column(name = "timeout_ms")
    private Integer timeoutMs = 10000;

    // ── Alarm eşikleri ────────────────────────────────────────────────────────
    // HEPSİ opsiyoneldir: null bırakılan eşik alarm ÜRETMEZ. Böylece kullanıcı yalnız umursadığı
    // metriği bağlar ve geri kalanı gürültü yapmaz.

    /** Toplam yükleme süresi (ms) bu değeri aşarsa yavaşlık alarmı. */
    @Column(name = "max_load_ms")
    private Integer maxLoadMs;

    /** İlk bayta kadar geçen süre (ms) bu değeri aşarsa yavaşlık alarmı — "sunucu mu yavaş" sorusunun cevabı. */
    @Column(name = "max_ttfb_ms")
    private Integer maxTtfbMs;

    /** Toplam transfer (KB) bu değeri aşarsa şişme alarmı. */
    @Column(name = "max_page_kb")
    private Integer maxPageKb;

    /** Toplam istek sayısı bu değeri aşarsa şişme alarmı — yeni bir tracker/script eklendiğinde
     *  bayt az, istek sayısı çok artar; şişmenin en erken işareti budur. */
    @Column(name = "max_requests")
    private Integer maxRequests;

    // ── Gelişmiş ölçüm seçenekleri ────────────────────────────────────────────

    /** Gönderilecek User-Agent; boş/null ise {@link #DEFAULT_UA}. WAF/bot koruması olan sayfalar için gerekir. */
    @Column(name = "user_agent")
    private String userAgent;

    /** DNT (Do Not Track) başlığı gönderilsin mi. */
    @Column(name = "send_dnt")
    private Boolean sendDnt = false;

    /** Tracker/analytics kaynakları ölçüm DIŞI bırakılsın mı — açıkken bu kaynaklar ne indirilir ne sayılır,
     *  böylece "kendi sayfam ne kadar ağır" sorusu üçüncü-taraf gürültüsünden arınır. */
    @Column(name = "exclude_trackers")
    private Boolean excludeTrackers = false;

    /** Hariç tutulacak ek host/URL desenleri (satır ya da virgül ayrık) — yerleşik tracker listesine EKlenir. */
    @Column(name = "tracker_patterns", columnDefinition = "TEXT")
    private String trackerPatterns;

    // ── Kimlikli istekler ─────────────────────────────────────────────────────

    /** HTTP Basic auth kullanıcı adı (kimlik isteyen iç sayfalar için). */
    @Column(name = "basic_auth_user")
    private String basicAuthUser;

    /** Basic auth parolası — {@code SecretCipher} ile ŞİFRELİ saklanır ve API'den asla düz dönmez
     *  (yanıtta yalnız {@code hasBasicAuthPass} bayrağı görünür). */
    @Column(name = "basic_auth_pass_enc", columnDefinition = "TEXT")
    private String basicAuthPassEnc;

    /**
     * Ek istek başlıkları, satır başına {@code Ad: değer} — {@code SecretCipher} ile ŞİFRELİ.
     *
     * <p>YALNIZ global admin yazabilir: serbest başlık enjeksiyonu iç servislere doğru bir SSRF/yetki
     * yüzeyi açar (PORT {@code sendData} ile aynı gerekçe).
     *
     * <p>Şifreli saklanmasının sebebi: buraya en sık yazılan şey {@code Authorization: Bearer …}
     * türünden bir kimlik jetonudur. Düz saklanırsa hem veritabanında hem değişiklik geçmişi
     * anlık görüntülerinde okunabilir hâlde durur. API bu alanı asla düz döndürmez; yalnız
     * başlık ADLARI görünür (değerler değil).
     */
    @Column(name = "custom_headers_enc", columnDefinition = "TEXT")
    private String customHeadersEnc;

    /** Alt kaynak ölçümünde eşzamanlı istek sınırı (nezaket + tek-pod yük; varsayılan 5, clamp'li). */
    @Column(name = "resource_concurrency")
    private Integer resourceConcurrency = 5;

    // ── Alarm davranışı ───────────────────────────────────────────────────────

    /** Per-monitor teyit: alarm öncesi doğrulama denemesi sayısı (varsayılan 3; 0 = anında). */
    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 3;

    /** Per-monitor teyit: denemeler arası saniye (varsayılan 30). */
    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    /** Recovery period: alarmın otomatik kapanması için gereken ardışık başarılı kontrol sayısı. */
    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 3;

    /** Recovery aktif re-check aralığı (sn). */
    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 30;

    /** Serbest etiketler — virgülle ayrılmış (organizasyon/filtreleme). */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** E-posta bildirimi açık mı (varsayılan true). */
    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    /** Kişi-webhook (push) bildirimi açık mı (vars. true — üst katmanlar zaten vars. KAPALI, çifte emniyet). */
    @Column(name = "notify_webhook")
    private Boolean notifyWebhook = true;

    /** Alarm seviyesi (2026-09-19): WARNING (varsayılan, null) | HIGH | CRITICAL — süre-bitişi dışındaki tüm
     *  alarmlar bu seviyede açılır; HIGH/CRITICAL eskalasyon kontaklarını alıcıya ekler. Bkz. MonitorAlertPrefs. */
    @Column(name = "alert_level", length = 16)
    private String alertLevel;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

    // ── Kimlik künyesi ────────────────────────────────────────────────────────
    // "Bu izlemeyi kim kurdu?" sorusu geçmiş tablosuna gitmeden de cevaplanabilsin; monitor_change_log'dan
    // BAĞIMSIZ: biri retention ile temizlense de diğeri kalır.
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_ip", length = 50)
    private String createdIp;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_by_name")
    private String updatedByName;

    // ── Sabitler ──────────────────────────────────────────────────────────────

    /** Kontrol aralığı tabanı: 5 dakika. Hem sunucu tarafında hem formda uygulanır. */
    public static final int MIN_INTERVAL_SECONDS = 300;

    /** Ölçümün kendini tanıttığı varsayılan UA — kimlik açık, hedefin log'unda kim olduğumuz bellidir. */
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (compatible; SiteMonitor-PageSpeed/1.0; +https://sitemonitor)";

    /**
     * Bu izlemenin alarmlarinin gidecegi Bildirim Grubu — NULL ise zincirin kalani islet:
     * takimin varsayilan grubu, o da yoksa {@code Team.email} (bugunku davranis).
     */
    @jakarta.persistence.Column(name = "notification_group_id")
    private Long notificationGroupId;

    // MonitorSchedule (2026-09-19): "Sizin için — bugün" bayat-izleme kartı
    @Override public String scheduleType() { return "PAGESPEED"; }
    @Override public String scheduleTarget() { return url; }
}
