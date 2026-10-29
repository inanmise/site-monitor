package com.sitemonitor.service.watchdog;

/**
 * Kendi kendini izleme kontrol kataloğu (2026-10-11) — TEK kaynak: anahtar, varsayılan önem, Türkçe / İngilizce ad ve
 * sorun cümlesi (e-posta, webhook, push ve günlük metni buradan). Ekran metinleri i18n'de ({@code wd.check.<key>.*},
 * kapı {@code WatchdogI18nGateTest}). Sıra = ekran ve e-posta sırası.
 */
public enum WatchdogCheck {

    /** Hiçbir pod tarama liderliğini tutmuyor → izleme taramaları koşmuyor. */
    SWEEP_LEADER("sweep_leader", Severity.CRITICAL,
            "Tarama liderliği", "Sweep leadership",
            "Hiçbir pod izleme taramalarının liderliğini almıyor; zamanlanmış kontroller koşmuyor olabilir.",
            "No pod holds the monitoring sweep leadership; scheduled checks may not be running."),
    /** Bir izleme türünde etkin izlemelerin çoğu zamanında kontrol edilmiyor. */
    MONITOR_SWEEP("monitor_sweep", Severity.HIGH,
            "İzleme taramaları", "Monitoring sweeps",
            "Bazı izleme türlerinde izlemelerin çoğu zamanında kontrol edilmiyor; tarama takılmış olabilir.",
            "Most monitors of some types are not being checked on time; a sweep may be stuck."),
    /** Sertifika taraması beklenen sürede koşmadı. */
    CERT_SWEEP("cert_sweep", Severity.HIGH,
            "Sertifika taraması", "Certificate sweep",
            "Sertifika taraması beklenen sürede sonuç üretmedi.",
            "The certificate sweep has not produced results within the expected time."),
    /** Bildirimlerin önemli kısmı gönderilemiyor (SMTP). */
    NOTIFICATIONS("notifications", Severity.HIGH,
            "Bildirim gönderimi", "Notification delivery",
            "Son yarım saatte e-posta bildirimlerinin önemli kısmı gönderilemedi.",
            "A large share of e-mail notifications failed in the last half hour."),
    /** Kişisel push kuyruğunda zamanı geçmiş, gönderilmemiş satırlar birikti. */
    PUSH_OUTBOX("push_outbox", Severity.MEDIUM,
            "Push kuyruğu", "Push queue",
            "Kişisel push kuyruğunda zamanı geçtiği hâlde gönderilmeyen bildirimler birikti.",
            "Personal push notifications are piling up in the queue past their send time."),
    /**
     * Veritabanı yavaş (DEGRADED) ya da erişilemez / salt okunur (DOWN → kritik). 2026-10-23 (çok pod R2b, MP-36): yalnız
     * veritabanı GENELİNDEKİ ölçümler (bağlantı, sorgu, yazılabilirlik) ve HER canlı pod'un örneğinden havuz doluluğu karar
     * verir; turu kazanan pod'un kendi havuzu ve açılış şema yaması sonucu yalnız bilgidir.
     */
    DATABASE("database", Severity.MEDIUM,
            "Veritabanı", "Database",
            "Veritabanı sağlık kontrolü sorun bildiriyor.",
            "The database health check reports a problem."),
    /**
     * Pod saatleri birbirinden kaymış (2026-10-23, çok pod R2b, MP-37c; inceleme W-4): kira ve kilit karşılaştırmaları pod
     * saatiyle yapılır — pod'lar arası fark 5 sn üstü orta, 30 sn üstü yüksek önem (R1'in güvenli kira payı aşınır). Her canlı
     * pod'un son örneğinden; veritabanı saatinin farkı yalnız bilgi.
     */
    CLOCK_SKEW("clock_skew", Severity.MEDIUM,
            "Saat uyumu", "Clock alignment",
            "Pod'ların saatleri birbirinden kaymış; liderlik kirası ve iş kilitleri yanlış zamanda düşebilir (çift tarama riski). "
                    + "Düğümlerin NTP / chrony eşitlemesini denetleyin.",
            "The pods' clocks have drifted apart; the leader lease and job locks may expire at the wrong time (risk of double sweeps). "
                    + "Check NTP / chrony synchronisation on the nodes."),
    /**
     * Dağıtık kontrol kuyruğu takıldı (2026-10-29, çok pod Sürüm 3-3, paket D1; tasarım §7.5): en eski bekleyen iş ya da en eski
     * değerlendirilmeyen sonuç 2 dk'dan eski, ya da kirası dolmuş sahiplenme var. Ana anahtar kapalıyken uygulanmaz (NA,
     * sorgu yok). Karar veritabanı genelindeki kuyruk sayımından (tek gruplu deyim) — değerlendiren pod'un belleğinden değil.
     */
    CHECK_QUEUE("check_queue", Severity.MEDIUM,
            "Dağıtık kontrol kuyruğu", "Distributed check queue",
            "Dağıtık kontrol işleri sahiplenilmiyor ya da sonuçları değerlendirilmiyor; izlemeler zamanında kontrol edilmiyor olabilir. "
                    + "Küme kartında işçileri ve tarama liderini denetleyin; gerekirse ana anahtarı kapatın (türler klasik taramaya döner).",
            "Distributed check jobs are not being claimed or their results are not being evaluated; monitors may not be checked on time. "
                    + "Check the workers and the sweep leader on the Cluster card; if needed, turn the master switch off (types return to the classic sweep).");

    public enum Severity { CRITICAL, HIGH, MEDIUM }

    private final String key;
    private final Severity severity;
    private final String labelTr;
    private final String labelEn;
    private final String problemTr;
    private final String problemEn;

    WatchdogCheck(String key, Severity severity, String labelTr, String labelEn, String problemTr, String problemEn) {
        this.key = key;
        this.severity = severity;
        this.labelTr = labelTr;
        this.labelEn = labelEn;
        this.problemTr = problemTr;
        this.problemEn = problemEn;
    }

    public String key() { return key; }
    public Severity severity() { return severity; }
    public String labelTr() { return labelTr; }
    public String labelEn() { return labelEn; }
    public String problemTr() { return problemTr; }
    public String problemEn() { return problemEn; }

    public static WatchdogCheck byKey(String key) {
        for (WatchdogCheck c : values()) if (c.key.equals(key)) return c;
        return null;
    }
}
