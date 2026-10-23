package com.sitemonitor.service.schedule;

import java.util.function.BooleanSupplier;

/**
 * Tarama liderinden uzaklaştırılabilen bir yan iş (2026-10-23, çok pod Sürüm 3-1 P0; tasarım R3-1 §3.2 / §3.3). Kuran:
 * {@code @Scheduled} giriş metodu (paket C); koşturan: {@code SchedulerService.runSideJob(SideJob)} (paket C) —
 * gövde KİLİT / TUR / ÖĞE SAHİPLENME çağrılarını AYNEN taşır, yalnız hangi iş parçacığında ve (lider pod'da) ne zaman
 * koştuğu değişir. Gövdeyi tekrar koşturmak hiçbir zaman çift koşu üretmez — onu yine kilit / tur kaydı karar verir.
 *
 * @param name        iş adı — yan iş koşucusunda birleştirme anahtarı (ad başına en çok bir kuyrukta-ya-da-koşan örnek),
 *                    durum satırının adı; boş olamaz. Mümkünse işin kilit / tur adıyla aynı (ör. {@code cert-hourly}).
 * @param heavy       uzun iş mi — true ise gövde zamanlayıcı havuzu yerine pod'un yan iş koşucusunda koşar
 *                    ({@code SideJobRunner}); false = çağıranın iş parçacığında (bugün)
 * @param cronRound   cron turu adı ({@code round:<ad>} kaydının {@code <ad>} kısmı) — {@code alreadyDone} null ise
 *                    "bu tetik başka pod'da alındı mı" sorusu bununla sorulur ({@code SchedulerService.cronRoundTaken});
 *                    yoksa null
 * @param cron        cron ifadesi (Spring biçimi, 6 alan); null = sabit periyotlu / tek seferlik iş (lider ertelemesi YOK)
 * @param zone        cron saat dilimi (ör. {@code Europe/Istanbul}); {@code cron} null ise yok sayılır
 * @param alreadyDone ertelenen denemede "iş zaten yapıldı mı" (true → gövde koşmaz, atlandı sayılır); null = yalnız
 *                    {@code cronRound} ile karar (o da yoksa gövde koşar — kendi kilidi / sahiplenmesi karar verir)
 * @param body        işin gövdesi (bugünkü metot gövdesi — kilit + tur + iş); boş olamaz
 */
public record SideJob(String name, boolean heavy, String cronRound, String cron, String zone,
                      BooleanSupplier alreadyDone, Runnable body) {

    /**
     * Lider erteleme / tur bırakma kurallarının anahtarı ({@code AppSettingsCatalog}, BOOL, grup {@code scheduler},
     * GLOBAL_ONLY, CANLI) — kalıcı. false = 20.167.0 yerleşimi (koşucu yine kullanılır, yalnız erteleme / bırakma yok).
     */
    public static final String LEADER_YIELD_KEY = "site.monitor.scheduler.leader-yield.enabled";

    /** Ayar yoksa / okunamazsa (tasarım R3-1 §3.2). */
    public static final boolean LEADER_YIELD_DEFAULT = true;

    public SideJob {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("SideJob: ad boş");
        if (body == null) throw new IllegalArgumentException("SideJob: gövde yok (" + name + ")");
    }

    /** Cron tetikli iş mi (lider ertelemesi yalnız bunlara uygulanır). */
    public boolean cronTriggered() {
        return cron != null && !cron.isBlank();
    }

    /** Sabit periyotlu / tek seferlik uzun iş (cron yok, erteleme yok) — yalnız iş parçacığı değişir. */
    public static SideJob heavy(String name, Runnable body) {
        return new SideJob(name, true, null, null, null, null, body);
    }

    /**
     * Cron tetikli iş.
     *
     * @param heavy       uzun iş mi (yan iş koşucusu)
     * @param cronRound   tur adı ya da null
     * @param alreadyDone ertelenen denemenin ön denetimi ya da null
     */
    public static SideJob cron(String name, boolean heavy, String cronRound, String cron, String zone,
                               BooleanSupplier alreadyDone, Runnable body) {
        return new SideJob(name, heavy, cronRound, cron, zone, alreadyDone, body);
    }
}
