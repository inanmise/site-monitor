package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Senaryo (k6) alt süreçleri için İŞLEM DÜZEYİ sertleştirme (2026-10-31, k6 inceleme A2 / G1).
 *
 * <p><b>Tehdit.</b> k6 JVM ile AYNI kullanıcıyla (UID 1000) koşar. Linux'ta aynı UID'nin süreçleri birbirinin
 * {@code /proc/<pid>/environ|mem|maps|fd} dosyalarını okuyabilir — bir betik JVM'in ortamındaki pod sırlarını
 * (şifreleme anahtarı, DB parolası) ve kardeş k6 süreçlerinin ortamındaki BAŞKA takımların çözülmüş gizli değerlerini
 * okuyabilirdi. Dosya okuma taraması sezgiseldir; bu sınıf kalıcı savunmanın uygulama tarafıdır:
 * <ul>
 *   <li><b>JVM dump-edilemez</b> — açılışta, İLK k6 sürecinden ÖNCE {@code prctl(PR_SET_DUMPABLE, 0)} (Java 25 FFM;
 *       glibc + musl). Çekirdek bundan sonra {@code /proc/<jvm>/…} dosyalarının sahipliğini root yapar; aynı UID'deki k6
 *       okuyamaz. JVM'in kendisi kendi {@code /proc/self} dosyalarını okumaya devam eder (aynı iş parçacığı grubu).</li>
 *   <li><b>k6 ikilisi root sahipli + 0111</b> (Dockerfile): Linux okunamayan bir ikiliyi {@code execve} eden süreci de
 *       dump-edilemez yapar — kardeş k6 süreçlerinin ortamları birbirine kapanır.</li>
 * </ul>
 *
 * <p><b>Yan etkiler</b> (belgeye): {@code kubectl exec … jcmd 1 …} artık bağlanamaz (iş parçacığı dökümü için
 * {@code kill -3 1}); çekirdek dökümü üretilmez; {@code -XX:+HeapDumpOnOutOfMemoryError} çalışmaya devam eder.
 *
 * <p><b>{@code oom_score_adj}</b> ({@link #afterStart}): bellek dolunca çekirdek JVM yerine k6'yı öldürsün diye alt
 * sürecin değeri 1000 yapılmaya ÇALIŞILIR (en iyi çaba, hata DEBUG). DİKKAT: okunamaz (0111) k6 ikilisiyle başlayan süreç
 * dump-edilemez olduğu için {@code /proc/<k6>/oom_score_adj} root sahiplidir ve yazılamaz — sertleştirilmiş imajda bu adım
 * çekirdek tarafından reddedilir (açılışta bir kez INFO). İkisi birlikte isteniyorsa ayrıcalıklı bir yardımcı ya da ayrı
 * bellek cgroup'u gerekir (bkz. RUNBOOK).
 *
 * <p>Anahtar {@code site.monitor.scripted.process-hardening} (açılış anı, env {@code SCRIPTED_PROCESS_HARDENING},
 * varsayılan true). Linux değilse hiçbir şey yapmaz (DEBUG). Başarı INFO, hata WARN — açılışı ASLA durdurmaz.
 */
@Slf4j
@Component
public class ScriptedProcessHardening {

    /** {@code <linux/prctl.h>}. */
    static final int PR_GET_DUMPABLE = 3;
    static final int PR_SET_DUMPABLE = 4;

    /** {@link #apply} sonucu (test + sistem sağlığı). */
    public enum Outcome { NOT_ATTEMPTED, DISABLED, NOT_LINUX, APPLIED, FAILED }

    /** prctl çağrısı soyutlaması — birim testte taklit edilir. */
    @FunctionalInterface
    interface Prctl {
        int call(int option, long arg2) throws Throwable;
    }

    private final boolean enabled;
    private final String osName;
    private final Prctl prctl;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NOT_ATTEMPTED);
    private volatile boolean oomAdjustWarned = false;

    public ScriptedProcessHardening(@Value("${site.monitor.scripted.process-hardening:true}") boolean enabled) {
        this(enabled, System.getProperty("os.name", ""), null);
    }

    /** Test kurucusu: işletim sistemi adı + prctl taklidi (null = gerçek FFM çağrısı). */
    ScriptedProcessHardening(boolean enabled, String osName, Prctl prctl) {
        this.enabled = enabled;
        this.osName = osName == null ? "" : osName;
        this.prctl = prctl;
    }

    public Outcome outcome() { return outcome.get(); }

    /** Linux mu (yalnız ad — sürüm / dağıtım önemsiz). */
    static boolean isLinux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("linux");
    }

    /**
     * Bir kez uygular (sonraki çağrılar sonucu döndürür). k6 alt süreci başlatan her yol bundan ÖNCE geçer
     * ({@link ScriptedCheckerService#init}). Hiçbir koşulda istisna fırlatmaz.
     */
    public Outcome apply() {
        Outcome prev = outcome.get();
        if (prev != Outcome.NOT_ATTEMPTED) return prev;
        synchronized (this) {
            prev = outcome.get();
            if (prev != Outcome.NOT_ATTEMPTED) return prev;
            Outcome o = decideAndApply();
            outcome.set(o);
            return o;
        }
    }

    private Outcome decideAndApply() {
        if (!enabled) {
            log.info("[K6] İşlem sertleştirmesi kapalı (site.monitor.scripted.process-hardening=false) — JVM dump-edilebilir "
                    + "kalır; aynı kullanıcıdaki k6 betikleri JVM ortamını okuyabilir.");
            return Outcome.DISABLED;
        }
        if (!isLinux(osName)) {
            log.debug("[K6] İşlem sertleştirmesi yalnız Linux'ta uygulanır ({}) — atlandı", osName);
            return Outcome.NOT_LINUX;
        }
        try {
            Prctl p = prctl != null ? prctl : nativePrctl();
            int rc = p.call(PR_SET_DUMPABLE, 0L);
            int now = p.call(PR_GET_DUMPABLE, 0L);
            if (rc == 0 && now == 0) {
                log.info("[K6] İşlem sertleştirmesi uygulandı: JVM dump-edilemez (PR_SET_DUMPABLE=0) — k6 betikleri "
                        + "/proc/1/environ vb. okuyamaz. jcmd bağlanamaz; iş parçacığı dökümü için kill -3 1.");
                return Outcome.APPLIED;
            }
            log.warn("[K6] İşlem sertleştirmesi uygulanamadı (prctl dönüşü {}, dump durumu {}) — açılış sürüyor", rc, now);
            return Outcome.FAILED;
        } catch (Throwable t) {
            log.warn("[K6] İşlem sertleştirmesi uygulanamadı ({}) — açılış sürüyor", t.toString());
            return Outcome.FAILED;
        }
    }

    /** Gerçek {@code prctl} — değişken argümanlı C işlevi (ikinci argüman {@code unsigned long}). */
    private static Prctl nativePrctl() {
        Linker linker = Linker.nativeLinker();
        MemorySegment fn = linker.defaultLookup().find("prctl")
                .orElseThrow(() -> new IllegalStateException("prctl sembolü bulunamadı"));
        MethodHandle h = linker.downcallHandle(fn,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
                Linker.Option.firstVariadicArg(1));
        return (option, arg2) -> (int) h.invokeExact(option, arg2);
    }

    /**
     * k6 alt süreci başladıktan hemen sonra {@code /proc/<pid>/oom_score_adj = 1000} (Linux, en iyi çaba, hata DEBUG).
     * Sertleştirilmiş imajda k6 dump-edilemez olduğundan yazım reddedilir — ilk retde bir kez INFO.
     */
    public void afterStart(Process process) {
        if (process == null || !isLinux(osName)) return;
        try {
            Path f = Path.of("/proc", String.valueOf(process.pid()), "oom_score_adj");
            Files.writeString(f, "1000", StandardCharsets.US_ASCII);
        } catch (Exception e) {
            if (!oomAdjustWarned) {
                oomAdjustWarned = true;
                log.info("[K6] k6 alt sürecinin oom_score_adj değeri yazılamadı ({}) — okunamaz (0111) k6 ikilisi "
                        + "dump-edilemez süreç üretir, /proc dosyası root sahiplidir; çekirdek varsayılanı geçerli.", e.toString());
            } else {
                log.debug("[K6] oom_score_adj yazılamadı: {}", e.toString());
            }
        }
    }
}
