package com.sitemonitor.service.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * İzleme ölçümlerinin TEK kataloğu (2026-10-18, kullanıcı isteği: "SiteMonitor'ü Grafana'da uçtan uca izleyeyim").
 * {@link MonitoringMetricsExporter} bu adlarla yayar, {@code GrafanaDashboardGenerator} panoyu bu adlarla kurar,
 * {@code GrafanaMetricCoverageGateTest} ikisinin ayrışmadığını denetler.
 *
 * <p><b>Ad kuralı.</b> Micrometer adı noktalı yazılır ({@code sitemonitor.monitor.state}); Prometheus'ta nokta alt çizgiye
 * döner ({@code sitemonitor_monitor_state}, {@link #prom}). Gauge adları {@code _total} / {@code _count} / {@code _sum} /
 * {@code _bucket} / {@code _info} / {@code _created} ile BİTMEZ — Prometheus istemcisi bu son ekleri sayaç / histogram için
 * ayırır ve gauge adından keser (kapı: {@code MonitoringMetricsScrapeTest}).
 *
 * <p><b>Etiket kümeleri sabittir</b> (Prometheus kayıt defteri aynı adın satırlarında aynı anahtarları ister). Değeri
 * olmayan etiket boş dizedir ({@code group=""}, {@code ug_team=""}) — PromQL'de etiketsizle aynıdır.
 */
public final class MonitoringMetricNames {

    private MonitoringMetricNames() {}

    /** Ölçümün kapsamı: tek kayıt (izleme / sertifika / alarm) mı, toplam mı, pod'un kendisi mi. */
    public enum Scope {
        /** İzleme / sertifika / alan adı / alarm başına satır — {@code site.monitor.metrics.export.per-monitor} kapatır. */
        ENTITY,
        /** Takım / tür / durum toplamı — her zaman yayılır (anahtar kapalıysa da). */
        AGGREGATE,
        /** Küme geneli tek değer (bütün pod'lar aynı değeri yayar → panoda {@code max}). */
        CLUSTER,
        /** Pod'a özgü değer (tarama lideri, aktarıcının kendi sağlığı) — panoda {@code sum} / pod başına. */
        POD
    }

    /** Ölçüm tanımı. {@code labels} sırası yayın sırasıdır; {@code help} Prometheus HELP satırı (İngilizce, sabit). */
    public record Def(String name, Scope scope, List<String> labels, String help) {
        public String prom() { return MonitoringMetricNames.prom(name); }
    }

    /** İzleme satırlarının ortak etiketleri. */
    public static final List<String> MONITOR_LABELS =
            List.of("type", "team", "team_id", "group", "monitor", "monitor_id", "target");
    /** Sertifika satırlarının ortak etiketleri. */
    public static final List<String> CERT_LABELS =
            List.of("domain", "team", "team_id", "ug_team", "ug_team_id", "tier", "group", "source");
    /** Alan adı kaydı satırlarının etiketleri. */
    public static final List<String> DOMAIN_LABELS =
            List.of("domain", "monitor", "monitor_id", "team", "team_id", "group");

    // ── İzleme başına (ENTITY) ───────────────────────────────────────────────────────────────────────────────
    public static final String MONITOR_STATE = "sitemonitor.monitor.state";
    public static final String MONITOR_UP = "sitemonitor.monitor.up";
    public static final String MONITOR_RESPONSE_MS = "sitemonitor.monitor.response.ms";
    public static final String MONITOR_AVG_RESPONSE_MS = "sitemonitor.monitor.avg.response.ms.24h";
    public static final String MONITOR_LAST_CHECK = "sitemonitor.monitor.last.check.timestamp.seconds";
    public static final String MONITOR_CHECKS = "sitemonitor.monitor.checks.24h";
    public static final String MONITOR_FAILED = "sitemonitor.monitor.failed.checks.24h";
    public static final String MONITOR_OPEN_ALERTS = "sitemonitor.monitor.open.alerts";
    public static final String MONITOR_ALERT_SEVERITY = "sitemonitor.monitor.open.alert.severity";
    public static final String MONITOR_OPEN_SINCE = "sitemonitor.monitor.open.alert.since.timestamp.seconds";
    public static final String MONITOR_INTERVAL = "sitemonitor.monitor.interval.seconds";

    // ── İzleme toplamları (AGGREGATE) ────────────────────────────────────────────────────────────────────────
    public static final String MONITORS = "sitemonitor.monitors";
    public static final String MONITORS_CHECKS = "sitemonitor.monitors.checks.24h";
    public static final String MONITORS_FAILED = "sitemonitor.monitors.failed.checks.24h";
    public static final String TEAMS = "sitemonitor.teams";

    // ── Alarmlar ──────────────────────────────────────────────────────────────────────────────────────────
    public static final String ALERTS_OPEN = "sitemonitor.alerts.open";
    public static final String ALERTS_OPEN_BY_LEVEL = "sitemonitor.alerts.open.by.level";
    public static final String ALERTS_OLDEST = "sitemonitor.alerts.oldest.open.timestamp.seconds";
    public static final String ALERT_OPEN = "sitemonitor.alert.open.since.timestamp.seconds";

    // ── Sertifikalar ──────────────────────────────────────────────────────────────────────────────────────
    public static final String CERT_DAYS = "sitemonitor.certificate.days.remaining";
    public static final String CERT_STATUS = "sitemonitor.certificate.status";
    public static final String CERT_TLS_GRADE = "sitemonitor.certificate.tls.grade";
    public static final String CERT_LAST_CHECK = "sitemonitor.certificate.last.check.timestamp.seconds";
    public static final String CERTS = "sitemonitor.certificates";
    public static final String CERTS_EXPIRY = "sitemonitor.certificates.expiry";
    public static final String CERTS_GRADE = "sitemonitor.certificates.tls.grade";

    // ── Alan adı kaydı ───────────────────────────────────────────────────────────────────────────────────
    public static final String DOMAIN_DAYS = "sitemonitor.domain.days.remaining";
    public static final String DOMAINS_EXPIRY = "sitemonitor.domains.expiry";

    // ── Fırtınalar ve bildirimler ──────────────────────────────────────────────────────────────────────────
    public static final String STORMS_ACTIVE = "sitemonitor.storms.active";
    public static final String STORMS_ACTIVE_ALL = "sitemonitor.storms.active.all";
    public static final String STORM_MEMBERS = "sitemonitor.storms.active.members";
    public static final String NOTIFICATIONS = "sitemonitor.notifications.24h";
    public static final String PUSH_DELIVERIES = "sitemonitor.push.deliveries.24h";
    public static final String PUSH_PENDING = "sitemonitor.push.outbox.pending";
    public static final String PUSH_STUCK = "sitemonitor.push.outbox.stuck";
    public static final String PUSH_CIRCUIT = "sitemonitor.push.circuit.open";
    public static final String PUSH_ENABLED = "sitemonitor.push.enabled";

    // ── SiteMonitor'ün kendi sağlığı ─────────────────────────────────────────────────────────────────────────
    public static final String WATCHDOG_STATUS = "sitemonitor.watchdog.status";
    public static final String WATCHDOG_CHECK = "sitemonitor.watchdog.check.status";
    public static final String WATCHDOG_EVALUATED = "sitemonitor.watchdog.evaluated.timestamp.seconds";
    public static final String SWEEP_LEADER = "sitemonitor.sweep.leader";
    public static final String SWEEP_LEASE = "sitemonitor.sweep.leader.lease.remaining.seconds";
    public static final String SCHEDULER_LOCK = "sitemonitor.scheduler.lock.held";
    public static final String DATABASE_STATUS = "sitemonitor.database.status";
    public static final String SYSTEM_MAINTENANCE = "sitemonitor.system.maintenance.state";

    // ── Çok pod çalışma ölçümleri (2026-10-23, R2b) ─────────────────────────────────────────────────────────
    /** Bu pod'un açılıştan beri yenilemede kaybettiği iş kilitleri (birikimli; gauge — {@code _total} son eki YOK). */
    public static final String CLUSTER_LOCK_LOST = "sitemonitor.cluster.lock.lost";
    /** Bu pod'un açılıştan beri gördüğü tarama liderliği geçişleri, nedene göre (birikimli). */
    public static final String SWEEP_LEADER_TRANSITIONS = "sitemonitor.sweep.leader.transitions";
    /** Elle kontrol gelen kutusunda (takipçiden lidere) bekleyen satır — küme geneli. */
    public static final String EVAL_INBOX_DEPTH = "sitemonitor.eval.inbox.depth";
    /** Bu pod'un açılıştan beri push kira tazeleme hataları, nedene göre (birikimli). */
    public static final String PUSH_LEASE_RENEW_FAILED = "sitemonitor.push.lease.renew.failed";
    /** Bu pod'un saati − veritabanı saati (sn; pozitif = pod ileride). */
    public static final String CLOCK_SKEW = "sitemonitor.clock.skew.seconds";

    // ── Kalıcı değerlendirme durumu (2026-10-23, çok pod R3-1, plan 3a — hepsi bu pod'un kaydedicisinden) ───────────
    /** Kayıt kipi: 0 kapalı ({@code off}), 1 yalnız yazma ({@code write}), 2 yazma + doğrulama ({@code verify}). */
    public static final String EVAL_STATE_MODE = "sitemonitor.eval.state.mode";
    /** Yazıcının boşaltmayı bekleyen (birleştirilmiş) işlemleri. */
    public static final String EVAL_STATE_PENDING = "sitemonitor.eval.state.pending";
    /** Başarısız boşaltmalar (açılıştan beri birikimli; gauge — {@code _total} son eki YOK). */
    public static final String EVAL_STATE_WRITE_FAILURES = "sitemonitor.eval.state.write.failures";
    /** Son doğrulama turunun tür başına uyuşmazlığı — yalnız bu pod yazan liderken ve tur BİLİNİYORSA (bilinmeyen ≠ 0). */
    public static final String EVAL_STATE_VERIFY_MISMATCHES = "sitemonitor.eval.state.verify.mismatches";
    /** Son doğrulama turunun anı (sn) — yalnız bu pod yazan liderken (bilinmeyen tur da tur sayılır). */
    public static final String EVAL_STATE_VERIFY_AT = "sitemonitor.eval.state.verify.timestamp.seconds";

    // ── Dağıtık kontroller (2026-10-29, çok pod R3-3, paket D1; tasarım §7.4) — anahtar KAPALIYKEN hiçbiri yayılmaz ─────
    /** Kontrol işi kuyruğu, durum ({@code pending | claimed | done}) × tür (KÜME; yalnız kurum ailelerini yayan pod okur). */
    public static final String CHECK_JOBS = "sitemonitor.check.jobs";
    /** En eski bekleyen ({@code pending}, oluşturmadan) / değerlendirilmeyi bekleyen ({@code done}, bitişten) işin yaşı (sn; KÜME). */
    public static final String CHECK_JOBS_OLDEST = "sitemonitor.check.jobs.oldest.seconds";
    /** Bu pod'un kontrol yuvaları: {@code busy} (koşan) / {@code total} (yapılandırılan; işçi kapalıysa 0). */
    public static final String CHECK_WORKER_SLOTS = "sitemonitor.check.worker.slots";
    /**
     * Bu pod'un kontrol işi sonuçları, açılıştan beri birikimli (gauge — {@code _total} son eki YOK): {@code completed} (sonucu
     * yazdı), {@code late} (kirası kaybedildi, sonuç düştü), {@code lost} (liderken biçilen — iki kez sahiplenilip
     * tamamlanmayan iş), {@code dropped} (liderken değerlendirilmeden düşen sonuç: bayat, sıra dışı, pasif, hedef değişti,
     * tür kapalı, envanter dışı).
     */
    public static final String CHECK_WORKER_JOBS = "sitemonitor.check.worker.jobs";
    /** Bu pod'un son 256 sahiplenmesinin gecikmesi p95 (sn; gönderimden sahiplenmeye — yoksa yayılmaz). */
    public static final String CHECK_CLAIM_LAG = "sitemonitor.check.claim.lag.p95.seconds";

    // ── Aktarıcının kendisi ──────────────────────────────────────────────────────────────────────────────
    public static final String EXPORTER_ENABLED = "sitemonitor.exporter.enabled";
    public static final String EXPORTER_PER_MONITOR = "sitemonitor.exporter.per.monitor";
    public static final String EXPORTER_LAST_REFRESH = "sitemonitor.exporter.last.refresh.timestamp.seconds";
    public static final String EXPORTER_DURATION = "sitemonitor.exporter.refresh.duration.seconds";
    public static final String EXPORTER_SOURCE = "sitemonitor.exporter.source.up";
    public static final String EXPORTER_SERIES = "sitemonitor.exporter.series";
    public static final String EXPORTER_CAPPED = "sitemonitor.exporter.capped";
    /**
     * Bu pod kurum geneli aileleri (izleme / alarm / sertifika / … — POD dışı kapsamlar) dışa aktardı mı (1 / 0; çok pod R3-1,
     * plan 3e §3.6): tarama lideri, başka bir pod'un taze {@code metrics-org} gözlemini görünce yalnız POD ailelerini yayar.
     */
    public static final String EXPORTER_ORG_FAMILIES = "sitemonitor.exporter.org.families";

    /** HTTP sunucu gecikme histogramı (Timer, uç etiketi YOK) — {@link HttpLatencyMetrics}. */
    public static final String HTTP_DURATION = "sitemonitor.http.server.duration";

    /** Tüm gauge tanımları (Timer hariç) — yayın, pano ve kapı bu listeden okur. */
    public static final List<Def> ALL;
    private static final Map<String, Def> BY_NAME;

    static {
        List<Def> l = new ArrayList<>();
        // İzleme başına
        l.add(new Def(MONITOR_STATE, Scope.ENTITY, MONITOR_LABELS,
                "Monitor state code: 0 up, 1 stale (check overdue), 2 down, 3 unknown (never checked), 4 paused"));
        l.add(new Def(MONITOR_UP, Scope.ENTITY, MONITOR_LABELS,
                "1 when the active monitor is up, 0 when it is down; absent while stale, unknown or paused"));
        l.add(new Def(MONITOR_RESPONSE_MS, Scope.ENTITY, MONITOR_LABELS, "Response time of the last check in milliseconds"));
        l.add(new Def(MONITOR_AVG_RESPONSE_MS, Scope.ENTITY, MONITOR_LABELS,
                "Average response time over the last 24 hours in milliseconds"));
        l.add(new Def(MONITOR_LAST_CHECK, Scope.ENTITY, MONITOR_LABELS, "Time of the last check, seconds since epoch"));
        l.add(new Def(MONITOR_CHECKS, Scope.ENTITY, MONITOR_LABELS, "Checks run in the last 24 hours"));
        l.add(new Def(MONITOR_FAILED, Scope.ENTITY, MONITOR_LABELS, "Failed checks in the last 24 hours"));
        l.add(new Def(MONITOR_OPEN_ALERTS, Scope.ENTITY, MONITOR_LABELS, "Open alerts linked to the monitor"));
        l.add(new Def(MONITOR_ALERT_SEVERITY, Scope.ENTITY, MONITOR_LABELS,
                "Highest open alert level: 0 none, 1 warning, 2 high, 3 critical"));
        l.add(new Def(MONITOR_OPEN_SINCE, Scope.ENTITY, MONITOR_LABELS,
                "Opening time of the oldest open alert of the monitor, seconds since epoch"));
        l.add(new Def(MONITOR_INTERVAL, Scope.ENTITY, MONITOR_LABELS, "Configured check interval in seconds"));
        // Toplamlar
        l.add(new Def(MONITORS, Scope.AGGREGATE, List.of("type", "team", "team_id", "state"),
                "Monitors per type, team and state (up, stale, down, unknown, paused)"));
        l.add(new Def(MONITORS_CHECKS, Scope.AGGREGATE, List.of("type", "team", "team_id"), "Checks in the last 24 hours"));
        l.add(new Def(MONITORS_FAILED, Scope.AGGREGATE, List.of("type", "team", "team_id"),
                "Failed checks in the last 24 hours"));
        l.add(new Def(TEAMS, Scope.AGGREGATE, List.of("team", "team_id", "active"),
                "Teams known to SiteMonitor (constant 1); feeds the dashboard team filter"));
        // Alarmlar
        l.add(new Def(ALERTS_OPEN, Scope.AGGREGATE,
                List.of("family", "alert_type", "team", "team_id", "ug_team", "ug_team_id", "level", "acknowledged"),
                "Open alerts by monitor family, alert type, owning teams, level and acknowledgement"));
        l.add(new Def(ALERTS_OPEN_BY_LEVEL, Scope.AGGREGATE, List.of("level"),
                "Open alerts by level across all teams (each alert counted once)"));
        l.add(new Def(ALERTS_OLDEST, Scope.AGGREGATE, List.of("family", "team", "team_id", "ug_team", "ug_team_id", "level"),
                "Opening time of the oldest open alert, seconds since epoch"));
        l.add(new Def(ALERT_OPEN, Scope.ENTITY,
                List.of("alert_id", "family", "alert_type", "target", "team", "team_id", "ug_team", "ug_team_id", "level", "acknowledged"),
                "One series per open alert; value = opening time, seconds since epoch"));
        // Sertifikalar
        l.add(new Def(CERT_DAYS, Scope.ENTITY, CERT_LABELS, "Days until the certificate expires (negative = expired)"));
        l.add(new Def(CERT_STATUS, Scope.ENTITY, CERT_LABELS,
                "Certificate state code: 0 valid, 1 warning, 2 high, 3 critical, 4 expired, 5 check error"));
        l.add(new Def(CERT_TLS_GRADE, Scope.ENTITY, CERT_LABELS, "TLS configuration grade rank: 6 A+, 5 A, 4 B, 3 C, 2 D, 1 F"));
        l.add(new Def(CERT_LAST_CHECK, Scope.ENTITY, CERT_LABELS, "Time of the last certificate check, seconds since epoch"));
        l.add(new Def(CERTS, Scope.AGGREGATE, List.of("team", "team_id", "ug_team", "ug_team_id", "status"),
                "Certificates by owning teams and state (valid, warning, high, critical, expired, error)"));
        l.add(new Def(CERTS_EXPIRY, Scope.AGGREGATE, List.of("team", "team_id", "ug_team", "ug_team_id", "bucket"),
                "Certificates by expiry bucket (expired, d7, d30, d90, later, unknown)"));
        l.add(new Def(CERTS_GRADE, Scope.AGGREGATE, List.of("team", "team_id", "ug_team", "ug_team_id", "grade"),
                "Certificates by TLS grade (A+ … F, none = not graded)"));
        // Alan adı
        l.add(new Def(DOMAIN_DAYS, Scope.ENTITY, DOMAIN_LABELS, "Days until the domain registration expires"));
        l.add(new Def(DOMAINS_EXPIRY, Scope.AGGREGATE, List.of("team", "team_id", "bucket"),
                "Domain registrations by expiry bucket (expired, d7, d30, d90, later, unknown)"));
        // Fırtına / bildirim
        l.add(new Def(STORMS_ACTIVE, Scope.AGGREGATE, List.of("team", "team_id"), "Active alert storms per team"));
        l.add(new Def(STORMS_ACTIVE_ALL, Scope.CLUSTER, List.of(), "Active alert storms across all teams"));
        l.add(new Def(STORM_MEMBERS, Scope.AGGREGATE, List.of("team", "team_id"), "Alerts grouped in active storms per team"));
        l.add(new Def(NOTIFICATIONS, Scope.AGGREGATE, List.of("channel", "trigger", "status"),
                "Notification decisions in the last 24 hours by channel (email, webhook), trigger and status"));
        l.add(new Def(PUSH_DELIVERIES, Scope.AGGREGATE, List.of("status"), "Personal push deliveries in the last 24 hours"));
        l.add(new Def(PUSH_PENDING, Scope.CLUSTER, List.of(), "Personal push rows waiting in the outbox"));
        l.add(new Def(PUSH_STUCK, Scope.CLUSTER, List.of(), "Pending push rows that are more than 10 minutes overdue"));
        l.add(new Def(PUSH_CIRCUIT, Scope.POD, List.of(), "1 while the push gateway circuit breaker is open on this pod"));
        l.add(new Def(PUSH_ENABLED, Scope.CLUSTER, List.of(), "1 when personal push notifications are enabled"));
        // Kendi sağlığı
        l.add(new Def(WATCHDOG_STATUS, Scope.CLUSTER, List.of(),
                "Self-monitoring overall state: 0 up, 1 degraded, 2 down, 3 disabled, 4 never evaluated"));
        l.add(new Def(WATCHDOG_CHECK, Scope.CLUSTER, List.of("check"),
                "Self-monitoring check: 0 ok, 1 medium problem, 2 high problem, 3 critical problem, 4 not evaluated"));
        l.add(new Def(WATCHDOG_EVALUATED, Scope.CLUSTER, List.of(), "Last self-monitoring evaluation, seconds since epoch"));
        l.add(new Def(SWEEP_LEADER, Scope.POD, List.of(), "1 when this pod holds the monitoring sweep leadership"));
        l.add(new Def(SWEEP_LEASE, Scope.CLUSTER, List.of(),
                "Seconds until the sweep leader lease expires (negative = expired, nobody renews it)"));
        l.add(new Def(SCHEDULER_LOCK, Scope.CLUSTER, List.of("name"),
                "Held scheduler locks by job name (dynamic keys collapsed to prefix:*)"));
        l.add(new Def(DATABASE_STATUS, Scope.POD, List.of(), "Database health seen by this pod: 0 up, 1 degraded, 2 down"));
        l.add(new Def(SYSTEM_MAINTENANCE, Scope.CLUSTER, List.of(),
                "SiteMonitor maintenance: 0 none, 1 announced, 2 warning, 3 active, 4 recently ended"));
        // Çok pod çalışma ölçümleri
        l.add(new Def(CLUSTER_LOCK_LOST, Scope.POD, List.of(),
                "Job locks this pod lost while holding them (renewal found the row gone or owned by another pod), since the pod started"));
        l.add(new Def(SWEEP_LEADER_TRANSITIONS, Scope.POD, List.of("reason"),
                "Sweep leadership transitions seen by this pod since it started, by reason (GAINED, LOST, RELEASED)"));
        l.add(new Def(EVAL_INBOX_DEPTH, Scope.CLUSTER, List.of(),
                "Manual check results waiting in the follower-to-leader evaluation inbox"));
        l.add(new Def(PUSH_LEASE_RENEW_FAILED, Scope.POD, List.of("reason"),
                "Push outbox lease renewals that failed on this pod since it started (error = query failed, lost = another pod took the rows)"));
        l.add(new Def(CLOCK_SKEW, Scope.POD, List.of(),
                "This pod's clock minus the database clock in seconds (positive = pod ahead); absent while unmeasured"));
        // Kalıcı değerlendirme durumu (R3-1, plan 3a)
        l.add(new Def(EVAL_STATE_MODE, Scope.POD, List.of(),
                "Durable evaluation state mode on this pod: 0 off, 1 write, 2 write + verify (decisions always come from memory)"));
        l.add(new Def(EVAL_STATE_PENDING, Scope.POD, List.of(),
                "Durable evaluation state writes waiting for the writer thread on this pod (coalesced per key)"));
        l.add(new Def(EVAL_STATE_WRITE_FAILURES, Scope.POD, List.of(),
                "Durable evaluation state flushes that failed on this pod since it started (decisions are not affected)"));
        l.add(new Def(EVAL_STATE_VERIFY_MISMATCHES, Scope.POD, List.of("kind"),
                "Mismatches of the last memory vs table verify round by kind, while this pod is the writing leader; absent when the round was unknown"));
        l.add(new Def(EVAL_STATE_VERIFY_AT, Scope.POD, List.of(),
                "Time of the last verify round (any result) while this pod is the writing leader, seconds since epoch"));
        // Dağıtık kontroller (R3-3)
        l.add(new Def(CHECK_JOBS, Scope.CLUSTER, List.of("state", "type"),
                "Distributed check jobs by state (pending, claimed, done) and monitor type; absent while distributed checks are off"));
        l.add(new Def(CHECK_JOBS_OLDEST, Scope.CLUSTER, List.of("state"),
                "Age of the oldest pending job (since dispatch) and of the oldest result waiting for evaluation (since completion), in seconds; 0 = none waiting"));
        l.add(new Def(CHECK_WORKER_SLOTS, Scope.POD, List.of("kind"),
                "This pod's distributed check slots: busy (running) and total (configured; 0 when the worker is off on this pod)"));
        l.add(new Def(CHECK_WORKER_JOBS, Scope.POD, List.of("outcome"),
                "Distributed check jobs on this pod since it started: completed, late (lease lost, result dropped), lost (reaped while leader), dropped (results not evaluated while leader)"));
        l.add(new Def(CHECK_CLAIM_LAG, Scope.POD, List.of(),
                "p95 of the dispatch-to-claim delay of this pod's last 256 claims, in seconds; absent before the first claim"));
        // Aktarıcı
        l.add(new Def(EXPORTER_ENABLED, Scope.POD, List.of(), "1 when the monitoring metrics exporter is enabled"));
        l.add(new Def(EXPORTER_PER_MONITOR, Scope.POD, List.of(), "1 when per-monitor / per-certificate series are exported"));
        l.add(new Def(EXPORTER_LAST_REFRESH, Scope.POD, List.of(), "Last successful exporter refresh, seconds since epoch"));
        l.add(new Def(EXPORTER_DURATION, Scope.POD, List.of(), "Duration of the last exporter refresh in seconds"));
        l.add(new Def(EXPORTER_SOURCE, Scope.POD, List.of("source"),
                "1 when the source was read in the last refresh, 0 when it failed (its series are dropped, never faked)"));
        l.add(new Def(EXPORTER_SERIES, Scope.POD, List.of("family"), "Series exported per family in the last refresh"));
        l.add(new Def(EXPORTER_CAPPED, Scope.POD, List.of("family"), "1 when the family hit the series cap"));
        l.add(new Def(EXPORTER_ORG_FAMILIES, Scope.POD, List.of(),
                "1 when this pod exported the organisation-wide families in the last refresh; 0 when it exported only its own pod families"));
        ALL = Collections.unmodifiableList(l);
        Map<String, Def> m = new LinkedHashMap<>();
        for (Def d : l) {
            if (m.put(d.name(), d) != null) throw new IllegalStateException("Ölçüm adı iki kez tanımlı: " + d.name());
        }
        BY_NAME = Collections.unmodifiableMap(m);
    }

    /**
     * Aktarıcı BAYATKEN ({@code refresh-seconds}'ın 3 katı — en az 3 dk — tamamlanmış tur yok: iş parçacığı takılmış olabilir)
     * {@code /metrics/sitemonitor}'un yine yayınladığı aileler (Prometheus adları). İş değerleri ve {@code source_up} /
     * {@code series} / {@code capped} GİZLENİR — eski değerler "güncel" ya da panonun {@code guard()}'ı üzerinden sahte 0
     * görünmesin; pano tazelik şeridi bunlardan "bayat" der.
     */
    public static final java.util.Set<String> STALE_VISIBLE = java.util.Set.of(prom(EXPORTER_ENABLED), prom(EXPORTER_PER_MONITOR),
            prom(EXPORTER_LAST_REFRESH), prom(EXPORTER_DURATION));

    /** Ada göre tanım; bilinmiyorsa null. */
    public static Def def(String name) {
        return BY_NAME.get(name);
    }

    /** Micrometer adı → Prometheus adı (nokta → alt çizgi). Timer ise {@link #promTimer}. */
    public static String prom(String micrometerName) {
        return micrometerName.replace('.', '_').toLowerCase(Locale.ROOT);
    }

    /** Timer'ın Prometheus taban adı ({@code _seconds}; satırlar {@code _bucket}/{@code _count}/{@code _sum}/{@code _max}). */
    public static String promTimer(String micrometerName) {
        return prom(micrometerName) + "_seconds";
    }
}
