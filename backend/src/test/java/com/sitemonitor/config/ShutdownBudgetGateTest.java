package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-21, çok pod sürüm 1 — MP-07 / MP-08): pod'un kapanışı Kubernetes'in SIGKILL'inden ÖNCE bitmeli.
 *
 * <p>Kubelet preStop kancasını başlattığı anda {@code terminationGracePeriodSeconds} saymaya başlar. Spring'in
 * kapanışı sırayla: {@code ContextClosedEvent} dinleyicileri (ÖNCE tarama liderliği kirası bırakılır —
 * {@code SchedulerService.onContextClosed} en yüksek öncelikte —, sonra {@code NotifyExecutorDrain} bildirim havuzlarını
 * ≤ {@link WebConfig#NOTIFY_AWAIT_SECONDS} sn boşaltır), web zarif aşaması ({@code timeout-per-shutdown-phase}),
 * yürütücü yaşam döngüsü aşaması (aynı süre), {@code @Scheduled} havuzunun beklemesi ({@code await-termination-period}),
 * ardından {@code @PreDestroy} beklemeleri (~10 sn: e-posta yeniden deneme havuzu, manuel kontrol havuzu, …). Eski
 * değerlerle (30 + 30 + beklemeler &gt; 60 sn grace) pod sık sık {@code @PreDestroy}'dan önce öldürülüyor, tarama
 * liderliği kirası ve iş kilitleri bırakılmıyor, diğer pod izlemeyi ancak kira süresi (180 sn) dolunca devralıyordu.
 *
 * <p>Bu test üç kaynağı birlikte okur: {@code application.properties} varsayılanları, Helm {@code values.yaml}
 * (grace + preStop) ve {@code scripts/helm-template-check.sh}'in render denetiminde kullandığı bütçe sabiti. Biri
 * değişip diğerleri değişmezse kırmızı olur.
 */
class ShutdownBudgetGateTest {

    private static final Path PROPS = Path.of("src/main/resources/application.properties");
    private static final Path VALUES = Path.of("../helm/site-monitor/values.yaml");
    private static final Path DEPLOYMENT = Path.of("../helm/site-monitor/templates/deployment.yaml");
    private static final Path TEMPLATE_CHECK = Path.of("../scripts/helm-template-check.sh");

    /**
     * Yok etme anındaki TÜM havuz beklemelerinin ortak süresi (inceleme D-1, 2026-10-21): her bekleme
     * {@code min(kendi tavanı, kalan)} kadar bekler ({@link ShutdownDeadline#waitMs}) — sıralı beklemelerin toplamı (bildirim 20 +
     * giriş postası 20 + push 10 + kodla giriş 10 + e-posta 5 + olay 5 + elle kontrol 5 ≈ 75 sn) artık bu süreyi aşmaz.
     */
    private static final long PRE_DESTROY_SLACK_S = ShutdownDeadline.DESTROY_BUDGET_MS / 1000;

    /**
     * Çok pod Sürüm 3-2 (2026-10-28, paket C): {@code SchedulerService.onContextClosed} okuma ayarı açıkken kirayı bırakmadan
     * önce devir boşaltmasını bekler ({@code EvalStateSink.HANDOVER_BUDGET_MS} = 300 ms durulma + 700 ms boşaltma) — saniyeye
     * yukarı yuvarlanıp bütçeye eklenir (ayar kapalıyken bekleme yok; bütçe yine de en kötü durumu sayar).
     */
    private static final long HANDOVER_S =
            (com.sitemonitor.service.evalstate.EvalStateSink.HANDOVER_BUDGET_MS + 999) / 1000;

    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("^\\$\\{[A-Z0-9_]+:([^}]+)}$");

    private static Properties props() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(PROPS)) {
            p.load(in);   // .properties = ISO-8859-1, Spring ile aynı okuma
        }
        return p;
    }

    /** "20s" | "20000ms" | "1m" | "${ENV:20s}" → saniye (yer tutucuda varsayılan değer). */
    static long seconds(String raw) {
        String v = raw.trim();
        Matcher m = PLACEHOLDER_DEFAULT.matcher(v);
        if (m.matches()) v = m.group(1).trim();
        if (v.endsWith("ms")) return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2))).toSeconds();
        if (v.endsWith("s")) return Long.parseLong(v.substring(0, v.length() - 1));
        if (v.endsWith("m")) return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1))).toSeconds();
        return Duration.ofMillis(Long.parseLong(v)).toSeconds();   // Spring: birimsiz Duration = ms
    }

    private static long topLevelInt(String yaml, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + ":\\s*(\\d+)\\s*(#.*)?$").matcher(yaml);
        assertThat(m.find()).as("values.yaml üst düzey '%s:' sayısı", key).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    @DisplayName("seconds(): yer tutucu varsayılanı ve Spring Duration birimleri")
    void parsesDurations() {
        assertThat(seconds("${SHUTDOWN_PHASE_TIMEOUT:20s}")).isEqualTo(20);
        assertThat(seconds("30s")).isEqualTo(30);
        assertThat(seconds("15000ms")).isEqualTo(15);
        assertThat(seconds("1m")).isEqualTo(60);
        assertThat(seconds("20000")).isEqualTo(20);
    }

    @Test
    @DisplayName("Uygulama: zarif kapanış açık, aşama süresi 20 sn, @Scheduled havuzu çalışan işleri bekler")
    void applicationShutdownSettings() throws IOException {
        Properties p = props();
        assertThat(p.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(p.getProperty("spring.task.scheduling.shutdown.await-termination"))
                .as("çalışan zamanlanmış işler ContextClosedEvent'te kesilmesin (shutdownNow)").isEqualTo("true");
        assertThat(p.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .as("ortamdan ezilebilir (SHUTDOWN_PHASE_TIMEOUT)").startsWith("${SHUTDOWN_PHASE_TIMEOUT:");
        assertThat(p.getProperty("spring.task.scheduling.shutdown.await-termination-period"))
                .as("ortamdan ezilebilir (SCHEDULING_AWAIT_TERMINATION)").startsWith("${SCHEDULING_AWAIT_TERMINATION:");
        assertThat(seconds(p.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))).isEqualTo(20);
        assertThat(seconds(p.getProperty("spring.task.scheduling.shutdown.await-termination-period"))).isEqualTo(20);
    }

    @Test
    @DisplayName("Bütçe: preStop + web + yürütücü + @Scheduled bekleme + @PreDestroy payı < terminationGracePeriodSeconds")
    void springShutdownFitsInsideTheGracePeriod() throws IOException {
        Properties p = props();
        long phase = seconds(p.getProperty("spring.lifecycle.timeout-per-shutdown-phase"));
        long await = seconds(p.getProperty("spring.task.scheduling.shutdown.await-termination-period"));
        String values = Files.readString(VALUES, StandardCharsets.UTF_8);
        long grace = topLevelInt(values, "terminationGracePeriodSeconds");
        long preStop = topLevelInt(values, "preStopSleepSeconds");

        long spring = WebConfig.NOTIFY_AWAIT_SECONDS /* ContextClosedEvent: bildirim havuzu boşaltma */
                + phase /* web zarif aşaması */ + phase /* yürütücü yaşam döngüsü aşaması */ + await + PRE_DESTROY_SLACK_S
                + HANDOVER_S /* çok pod 3-2: kira bırakılmadan önce devir boşaltması (yalnız okuma ayarı açıkken) */;
        assertThat(preStop).as("preStop kancası açık (rollout'ta 502 olmasın)").isPositive();
        assertThat(preStop + spring)
                .as("preStop %d + Spring %d sn, grace %d sn'nin altında kalmalı", preStop, spring, grace)
                .isLessThan(grace);
        assertThat(grace).as("varsayılan: 10 + 20 + 20 + 20 + 20 + 10 + 1 = 101 < 120").isEqualTo(120);
        assertThat(spring).as("Spring kapanış bütçesi (3-2: + 1 sn devir)").isEqualTo(91);
        assertThat(preStop).as("plan varsayılanı").isEqualTo(10);

        // helm-template-check.sh render çıktısında aynı bütçeyi (grace − preStop ≥ SPRING_BUDGET_S) arar.
        Matcher m = Pattern.compile("(?m)^SPRING_BUDGET_S=(\\d+)\\s*$")
                .matcher(Files.readString(TEMPLATE_CHECK, StandardCharsets.UTF_8));
        assertThat(m.find()).as("scripts/helm-template-check.sh SPRING_BUDGET_S").isTrue();
        assertThat(Long.parseLong(m.group(1))).as("betikteki bütçe = uygulamanın kapanış bütçesi").isEqualTo(spring);
    }

    @Test
    @DisplayName("KAPI (D-1): yok etme anındaki her havuz beklemesi ortak süreden düşer — awaitTermination / setAwaitTermination* olan her dosya ShutdownDeadline.waitMs kullanır")
    void everyDestroyWaitUsesTheSharedDeadline() throws IOException {
        Path main = Path.of("src/main/java");
        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (var files = Files.walk(main)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                boolean waits = src.contains(".awaitTermination(") || src.contains("setAwaitTerminationSeconds(")
                        || src.contains("setAwaitTerminationMillis(");
                if (!waits || f.endsWith("ShutdownDeadline.java")) continue;
                if (!src.contains("ShutdownDeadline.waitMs(")) offenders.add(main.relativize(f).toString());
            }
        }
        assertThat(offenders).as("yok etme anında kendi tam süresini bekleyen havuz — ShutdownDeadline.waitMs(tavan) kullanın")
                .isEmpty();
        // WebConfig: kapanışta bekleyen her havuz ortak süreyi okuyan alt sınıftan
        String web = Files.readString(Path.of("src/main/java/com/sitemonitor/config/WebConfig.java"), StandardCharsets.UTF_8);
        assertThat(count(web, "setWaitForTasksToCompleteOnShutdown(true)")).isEqualTo(count(web, "new DeadlineAwareTaskExecutor("));
    }

    /**
     * Çok pod R2-A (2026-10-22, önbellek yolu inceleme bulgusu 2): {@code CacheBus}'ın kapanıştaki son yayını ortak yok etme
     * süresi içinde kalır — {@code ContextClosedEvent} dinleyicisi beklemez / veritabanına gitmez (yalnız bayrak), yok etmedeki
     * tek deneme {@code ShutdownDeadline.waitMs(tavan)} kadar beklenen ayrı bir iş parçacığında.
     */
    @Test
    @DisplayName("Önbellek yolu: kapanış dinleyicisi beklemez, yok etmedeki son yayın ortak süreyle sınırlı")
    void cacheBusFinalFlushUsesTheSharedDeadline() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/sitemonitor/service/cache/CacheBus.java"), StandardCharsets.UTF_8);
        assertThat(src).contains("ShutdownDeadline.waitMs(SHUTDOWN_FLUSH_CAP_MS)").contains(".join(budget)");
        int closed = src.indexOf("public void onContextClosed(");
        assertThat(closed).isPositive();
        String body = src.substring(closed, src.indexOf("\n    }", closed));
        assertThat(body).as("ContextClosedEvent dinleyicisi yayın / bekleme yapmaz").doesNotContain("flush").doesNotContain("join")
                .doesNotContain("stop(");
        assertThat(com.sitemonitor.service.cache.CacheBus.SHUTDOWN_FLUSH_CAP_MS)
                .as("tavan ortak yok etme süresinin altında").isLessThan(ShutdownDeadline.DESTROY_BUDGET_MS);
    }

    /**
     * Çok pod R3-1 3e (2026-10-23): uzun "her pod" işleri artık yan iş koşucusunda koşar. Kapanışta koşucu, iş kilitleri
     * ({@code releaseAllOwn}) bırakılmadan ÖNCE durdurulur — koşan işin kilidi başka pod'a geçip aynı iş iki kez koşmasın; bekleme
     * ortak yok etme süresinden düşer (tavan ortak sürenin altında).
     */
    @Test
    @DisplayName("Yan iş koşucusu: onShutdown'da iş kilitlerinden ÖNCE durur, beklemesi ortak süreyle sınırlı")
    void sideJobsStopBeforeOwnLocksAreReleased() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java"), StandardCharsets.UTF_8);
        int start = src.indexOf("void onShutdown() {");
        assertThat(start).isPositive();
        String body = src.substring(start, src.indexOf("\n    }", start));
        int stop = body.indexOf("stopSideJobs();");
        int release = body.indexOf("releaseAllOwn()");
        assertThat(stop).as("onShutdown yan işleri durdurur").isPositive();
        assertThat(release).isPositive();
        assertThat(stop).as("yan işler iş kilitlerinden ÖNCE durur").isLessThan(release);

        String runner = Files.readString(Path.of("src/main/java/com/sitemonitor/service/schedule/SideJobRunner.java"),
                StandardCharsets.UTF_8);
        assertThat(runner).contains("ShutdownDeadline.waitMs(SHUTDOWN_WAIT_CAP_MS)");
        // FX-SIDE #1: kısa cron şeridi AYNI bekleme bütçesinin kalanıyla beklenir (ikinci bir tam tavan değil)
        assertThat(runner).contains("awaitRest(shortEx, waitStart, waitMs)").contains("awaitRest(shortEx, unwindStart, unwindMs)");
        assertThat(com.sitemonitor.service.schedule.SideJobRunner.SHUTDOWN_WAIT_CAP_MS)
                .as("tavan ortak yok etme süresinin altında").isLessThan(ShutdownDeadline.DESTROY_BUDGET_MS);
        int closed = runner.indexOf("public void onContextClosed(");
        String closedBody = runner.substring(closed, runner.indexOf("\n    }", closed));
        assertThat(closedBody).as("ContextClosedEvent dinleyicisi beklemez (yalnız kabul kapanır)")
                .doesNotContain("await").doesNotContain("stopAndWait").doesNotContain("shutdown");
    }

    /**
     * Çok pod Sürüm 3-2 (2026-10-28, paket C; tasarım R3-2 §5.1): devir boşaltması kiradan ÖNCE. {@code onContextClosed} sırası:
     * hazır sorusu → devri aç → {@code shutdownStarted = true} → durulma → boşaltma ({@code flushForHandover}) → kirayı bırak →
     * (finally) devri kapat. Kira boşaltmadan önce bırakılırsa halef işaretsiz (eksik) durumu okur — tam devir hiç olmaz.
     */
    @Test
    @DisplayName("3-2: onContextClosed — hazır → devri aç → shutdownStarted → durulma → boşaltma → kirayı bırak → devri kapat (finally)")
    void handoverFlushRunsBeforeTheLeaseRelease() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java"), StandardCharsets.UTF_8);
        int start = src.indexOf("public void onContextClosed(");
        assertThat(start).isPositive();
        String body = src.substring(start, src.indexOf("\n    }", start));
        int ready = body.indexOf("askHandoverReady(");
        int open = body.indexOf("openEvalHandover(");
        int started = body.indexOf("shutdownStarted = true");
        int quiet = body.indexOf("awaitEvalQuiet()");
        int flush = body.indexOf("flushForHandover(");
        int release = body.indexOf("releaseSweepLeadership()");
        int fin = body.indexOf("finally");
        int end = body.indexOf("endEvalHandover()");
        assertThat(java.util.List.of(ready, open, started, quiet, flush, release, fin, end)).as("hepsi gövdede").allMatch(i -> i > 0);
        assertThat(ready).as("hazır sorusu kapanış kapısından önce").isLessThan(open);
        assertThat(open).as("devir shutdownStarted'dan ÖNCE açılır (kapanış sonrası biten işlemler de kaydedilir)").isLessThan(started);
        assertThat(started).as("yeni iş kapısı boşaltmadan önce").isLessThan(quiet);
        assertThat(quiet).isLessThan(flush);
        assertThat(flush).as("boşaltma kiradan ÖNCE").isLessThan(release);
        assertThat(release).as("devir kira bırakıldıktan SONRA ve finally içinde kapanır").isLessThan(fin);
        assertThat(fin).isLessThan(end);
        assertThat(body.indexOf("releaseSweepLeadership()", release + 1)).as("kira tek yerde bırakılır").isNegative();
        assertThat(body).as("boşaltma ve durulma P0 sabitleriyle sınırlı")
                .contains("EvalStateSink.HANDOVER_FLUSH_MS").doesNotContain("Thread.sleep");
        assertThat(src).contains("awaitEvalQuiescent(com.sitemonitor.service.evalstate.EvalStateSink.HANDOVER_QUIESCE_MS)");
    }

    /**
     * Devir adımı kirayı en çok {@code HANDOVER_BUDGET_MS} (1 sn) geciktirebilir: durulma + boşaltma ≤ bütçe; kaydedici boşaltmayı
     * yardımcı iş parçacığında koşturup en çok bütçe kadar BEKLER (asılan bir veritabanı kapanışı uzatmaz).
     */
    @Test
    @DisplayName("3-2: devir bütçesi sınırlı — 300 + 700 ≤ 1000 ms; kaydedici boşaltmayı bekleme sınırlı yardımcı iş parçacığında koşturur")
    void handoverBudgetIsBounded() throws IOException {
        long quiesce = com.sitemonitor.service.evalstate.EvalStateSink.HANDOVER_QUIESCE_MS;
        long flush = com.sitemonitor.service.evalstate.EvalStateSink.HANDOVER_FLUSH_MS;
        long budget = com.sitemonitor.service.evalstate.EvalStateSink.HANDOVER_BUDGET_MS;
        assertThat(quiesce + flush).as("durulma + boşaltma ≤ devir bütçesi").isLessThanOrEqualTo(budget);
        assertThat(budget).as("devir kirayı en çok 1 sn geciktirir").isLessThanOrEqualTo(1_000L);
        assertThat(quiesce).isPositive();
        assertThat(flush).isPositive();
        // Kaydedici devir boşaltmasını uyguluyorsa (paket A) yardımcı iş parçacığını sınırlı bekler — sonsuz join yok.
        String rec = Files.readString(Path.of("src/main/java/com/sitemonitor/service/evalstate/EvalStateRecorder.java"),
                StandardCharsets.UTF_8);
        if (rec.contains("HandoverResult flushForHandover(")) {
            assertThat(rec).as("kaydedicinin beklemeleri sınırlı (join(ms)) — sınırsız join() yok").doesNotContain(".join()")
                    .containsPattern("\\.join\\([^)]");
        }
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    @DisplayName("Helm şablonu: grace ve preStop değerden gelir, bellek oturumu + çok pod reddedilir")
    void deploymentTemplateUsesTheValues() throws IOException {
        String tpl = Files.readString(DEPLOYMENT, StandardCharsets.UTF_8);
        assertThat(tpl).as("sabit 60 sn geri gelmesin").doesNotContainPattern("terminationGracePeriodSeconds:\\s*\\d");
        assertThat(tpl).contains("terminationGracePeriodSeconds: {{ $grace }}")
                .contains(".Values.terminationGracePeriodSeconds | default 120")
                .contains("preStop:")
                .contains("sleep {{ $preStop }}");
        assertThat(tpl).as("çok pod + bellek oturum deposu koruması")
                .contains("(ne (lower (trim (toString .Values.config.sessionStoreType))) \"jdbc\")")
                .contains("(gt (int .Values.replicaCount) 1) .Values.autoscaling.enabled");
    }

    @Test
    @DisplayName("ContextClosedEvent: liderlik kirası İLK bırakılır (bildirim havuzu boşaltmasından önce)")
    void leaseIsReleasedBeforeTheNotifyDrain() throws NoSuchMethodException {
        var release = new org.springframework.context.event.ApplicationListenerMethodAdapter("schedulerService",
                com.sitemonitor.service.SchedulerService.class,
                com.sitemonitor.service.SchedulerService.class.getMethod("onContextClosed",
                        org.springframework.context.event.ContextClosedEvent.class));
        var drain = new org.springframework.context.event.ApplicationListenerMethodAdapter("notifyExecutorDrain",
                NotifyExecutorDrain.class, NotifyExecutorDrain.class.getMethod("onContextClosed"));
        assertThat(release.getOrder()).as("SchedulerService.onContextClosed @Order").isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        assertThat(release.getOrder()).as("kira, ≤ 20 sn süren boşaltmadan ÖNCE bırakılır (devir ≤ ~35 sn)")
                .isLessThan(drain.getOrder());
    }
}
