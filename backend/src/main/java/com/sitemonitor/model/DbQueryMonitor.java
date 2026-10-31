package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Veritabanı Sorgu İzlemesi (11. izleme türü, 2026-10-31): kayıtlı bir veritabanı bağlantısında ({@link DbConnectionProfile})
 * YALNIZ okuyan bir SELECT çalıştırır, sonucu kurallara göre değerlendirir ve kural ihlalinde / sorgu hatasında alarm üretir.
 *
 * <p>Sorgu kayıtta, denemede ve HER çalıştırmadan önce {@code DbQuerySqlGuard} ile denetlenir (tek ifade, yalnız SELECT / WITH,
 * DML / DDL / kilit / tehlikeli fonksiyon yok, MSSQL'de her tabloda NOLOCK). Satır verisi arayüzde gösterilmez; yalnız alarm
 * anındaki satırlar alarmla saklanır ({@link DbQueryAlertRows}) ve alarm maili / ayrıntısında görünür.
 *
 * <p>Zamanlama iki kiplidir: {@code INTERVAL} (N saniyede bir) ya da {@code CRON} (Europe/Istanbul); ikisinde de izinli
 * pencereler, yasak pencereler, tatil takvimi ve ek tarihler uygulanır. {@link #intervalSeconds} ETKİN aralıktır (CRON'da
 * sonraki tetiklerin medyanı) — genel bakış / "bugün" panelinin gecikme kuralı için; asıl vade {@link #nextRunAt}.
 *
 * <p>Alarm anahtarı ({@code AlertEvent.domain}) izleme ADIDIR (takım içinde tekil, k6 emsali); ad değişince açık alarmlar
 * yeniden anahtarlanır. Türe özgü JSON kolonlarının biçimi {@code service/dbquery} sınıflarında tanımlıdır.
 */
@Entity
@Table(name = "dbquery_monitors",
       indexes = @Index(name = "idx_dbqm_profile", columnList = "profile_id"))
@Data
@NoArgsConstructor
public class DbQueryMonitor implements MonitorAlertPrefs, MonitorSchedule, NocTarget {

    public static final String MODE_INTERVAL = "INTERVAL", MODE_CRON = "CRON";
    public static final String LOGIC_ANY = "ANY", LOGIC_ALL = "ALL";
    /**
     * Mail kolonları kipi (r177, Ö-6): {@code FIRST_N} = seçilen kolonlar, seçim yoksa ilk N kolon (bu sürümden önceki her
     * kayıt — null da bu); {@code SELECTED} = YALNIZ seçilen kolonlar, seçim boşsa satır tablosu yok (yalnız sayı) — yeni
     * izlemenin varsayılanı.
     */
    public static final String MAIL_COLUMNS_FIRST_N = "FIRST_N", MAIL_COLUMNS_SELECTED = "SELECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Alarm adı (TÜM takımlarda tekil, büyük-küçük harf duyarsız; PostgreSQL ux_dbqm_name_lower) — alarm anahtarı da budur. */
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "group_name")
    private String groupName;

    @Column(columnDefinition = "TEXT")
    private String tags;

    /** Aynı takımın kayıtlı bağlantısı (her yolda doğrulanır: oluştur / güncelle / takım değişimi / geri yükleme). */
    @Column(name = "profile_id")
    private Long profileId;

    /** "Bu alarm ne için?" — maile ve alarm ayrıntısına yazılır (5–2000 karakter). */
    @Column(columnDefinition = "TEXT")
    private String purpose;

    /** Çalıştırılacak sorgu (≤ 10 000 karakter). */
    @Column(name = "sql_text", columnDefinition = "TEXT")
    private String sqlText;

    /** Sorgunun SHA-256 öneki (16 hex) — değişiklik izi / kontrol satırında hangi sorgunun koştuğu. */
    @Column(name = "sql_hash", length = 16)
    private String sqlHash;

    // ── Zamanlama ────────────────────────────────────────────────────────────

    /** {@link #MODE_INTERVAL} (varsayılan) | {@link #MODE_CRON}. */
    @Column(name = "schedule_mode", length = 10)
    private String scheduleMode = MODE_INTERVAL;

    /** INTERVAL kipinde aralık; CRON kipinde ETKİN aralık (sonraki tetiklerin medyanı) — bkz. sınıf belgesi. */
    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 300;

    /** CRON kipinde 5 ya da 6 alanlı ifade ya da makro (@hourly, @daily, @weekly, @monthly). */
    @Column(name = "cron_expression", length = 120)
    private String cronExpression;

    /** İzinli pencereler JSON {@code [{"days":[1..7],"from":"HH:mm","to":"HH:mm"}]}; boş / null = her an. */
    @Column(name = "windows_json", columnDefinition = "TEXT")
    private String windowsJson;

    /** Yasak pencereler JSON (aynı biçim) — izinli pencereden sonra uygulanır. */
    @Column(name = "blackout_json", columnDefinition = "TEXT")
    private String blackoutJson;

    /** Tatil takviminde (holiday_days) yer alan günlerde çalışma. */
    @Column(name = "skip_holidays")
    private Boolean skipHolidays = false;

    /** Ek çalışılmayacak tarihler JSON {@code ["2026-12-31", …]} (≤ 100). */
    @Column(name = "skip_dates_json", columnDefinition = "TEXT")
    private String skipDatesJson;

    /**
     * Bir sonraki planlı çalışma (UTC ISO) — YALNIZ zamanlayıcının hedefli UPDATE'leri yazar ({@code updatable = false},
     * 2026-10-31 inceleme D4 / K6: varlık kaydı bayat okumayla planı geri yazmasın); null = henüz hesaplanmadı. Zamanlama
     * değişince sıfırlama {@code SchedulerService.dbQueryScheduleChanged}.
     */
    @Column(name = "next_run_at", length = 30, updatable = false)
    private String nextRunAt;

    /**
     * Art arda ATLANAN (eşzamanlılık izni yok / iç hata) çalıştırma sayısı — bir çalıştırma gerçekleşince 0 (2026-10-31
     * inceleme O5). Yalnız zamanlayıcının hedefli UPDATE'leri yazar; null = hiç atlanmadı (bu kolondan önceki satır).
     */
    @Column(name = "skipped_runs", insertable = false, updatable = false)
    private Integer skippedRuns;

    /**
     * İlk atlanan çalıştırmanın zamanı (UTC ISO) — bir çalıştırma gerçekleşince null. Genel bakış bundan
     * {@code MonitoringOverviewService.DBQUERY_SKIP_STALE_SECONDS} sonra izlemeyi "gecikmiş" gösterir: plan ileri yazıldığı
     * hâlde sorgu çalışamıyorsa izleme sessizce "güncel" görünmesin.
     */
    @Column(name = "skipped_since", length = 30, insertable = false, updatable = false)
    private String skippedSince;

    /**
     * Ardışık YAPILANDIRMA sorunuyla değerlendirilemeyen zamanlanmış çalıştırma sayısı (r177, H-2) — kolon yok / sayı değil /
     * zaman damgası değil … {@code site.monitor.dbquery.unknown-alert-runs}'a ulaşınca {@code DBQUERY_FAIL}
     * ({@code DB_RULE_UNEVALUABLE}) alarmı. Yalnız zamanlayıcının hedefli UPDATE'leri yazar (varlık kaydı ezmez); null = 0.
     */
    @Column(name = "unknown_runs", insertable = false, updatable = false)
    private Integer unknownRuns;

    // ── Çalıştırma sınırları ─────────────────────────────────────────────────

    /** Sorgu zaman aşımı (sn, 5–60; üst sınır ayardan). */
    @Column(name = "query_timeout_seconds")
    private Integer queryTimeoutSeconds = 30;

    /** Okunacak en çok satır (1–5000; üst sınır ayardan) — fazlası "≥ N" sayılır. */
    @Column(name = "max_rows")
    private Integer maxRows = 1000;

    // ── Değerlendirme ────────────────────────────────────────────────────────

    /** Kurallar JSON (≤ 10) — biçim {@code DbQueryRules}. */
    @Column(name = "rules_json", columnDefinition = "TEXT")
    private String rulesJson;

    /** {@link #LOGIC_ANY} (varsayılan: herhangi bir kural ihlali alarm) | {@link #LOGIC_ALL}. */
    @Column(name = "rule_logic", length = 8)
    private String ruleLogic = LOGIC_ANY;

    /** Yavaş sorgu eşiği (ms); null = kapalı. Kurallardan bağımsız DBQUERY_SLOW üretir. */
    @Column(name = "slow_query_ms")
    private Integer slowQueryMs;

    // ── Mail ─────────────────────────────────────────────────────────────────

    /** Alarm mailine (ve alarm ayrıntısına) sonuç satırları eklensin mi. */
    @Column(name = "mail_include_rows")
    private Boolean mailIncludeRows = true;

    /** Mailde gösterilecek kolonlar JSON {@code ["col", …]}; boş = ilk 20 kolon. */
    @Column(name = "mail_columns_json", columnDefinition = "TEXT")
    private String mailColumnsJson;

    /** {@link #MAIL_COLUMNS_FIRST_N} | {@link #MAIL_COLUMNS_SELECTED}; null = FIRST_N (eski kayıt). */
    @Column(name = "mail_columns_mode", length = 10)
    private String mailColumnsMode;

    /** Maskelenecek kolonlar JSON (büyük-küçük harf duyarsız) — değer "••••" yazılır, saklanmaz. */
    @Column(name = "masked_columns_json", columnDefinition = "TEXT")
    private String maskedColumnsJson;

    /** Özel mail konusu şablonu (≤ 200); boş = standart konu. Yer tutucular {@code DbQueryMailSubject}. */
    @Column(name = "mail_subject_template", length = 200)
    private String mailSubjectTemplate;

    // ── Standart alarm / bildirim alanları (diğer türlerle aynı) ─────────────

    @Column(name = "confirm_attempts")
    private Integer confirmAttempts = 1;

    @Column(name = "confirm_interval_seconds")
    private Integer confirmIntervalSeconds = 30;

    @Column(name = "recovery_checks")
    private Integer recoveryChecks = 1;

    @Column(name = "recovery_interval_seconds")
    private Integer recoveryIntervalSeconds = 60;

    @Column(name = "notify_email")
    private Boolean notifyEmail = true;

    @Column(name = "notify_webhook")
    private Boolean notifyWebhook = true;

    /** Sorgu HATASI alarmının seviyesi (WARNING null / HIGH / CRITICAL). Eşik alarmının seviyesi kuraldan gelir. */
    @Column(name = "alert_level", length = 16)
    private String alertLevel;

    @Column(name = "notification_group_id")
    private Long notificationGroupId;

    @Column(name = "noc_notify")
    private Boolean nocNotify;

    @Column(name = "noc_group_ids", length = 500)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String nocGroupIds;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;

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

    @Override public String scheduleType() { return "DBQUERY"; }
    @Override public String scheduleTarget() { return null; }
}
