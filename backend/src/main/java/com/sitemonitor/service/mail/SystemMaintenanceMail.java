package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Row;
import com.sitemonitor.service.mail.MailTokens.Tone;

/**
 * Sistem Bakım Modu duyuru e-postaları (2026-10-02, kullanıcı kararı) — YALNIZ {@link MailDoc}/{@link MailKit} ile
 * (Outlook-güvenli, mobil duyarlı, tek CID logo, sol renk şeridi yok — BRAND.md §5.1; {@code EmailResponsiveContractTest}).
 *
 * <p>Dört tür: {@link Kind#ANNOUNCE} (planlamada bir kez), {@link Kind#UPDATED} (saat değişti / uzatıldı — düzeltme),
 * {@link Kind#CANCELLED} (iptal — düzeltme), {@link Kind#ENDED} (bakım tamamlandı — bitişte bir kez; 2026-10-02 kullanıcı
 * isteği: "planlı bakım sonlandığında kullanıcılara bir uyarı daha gönderilsin"). Gövde iki dillidir: Türkçe ana metin +
 * İngilizce özet (alıcıların dili bilinmez; tek e-posta herkese gider). Bakım nedeni boşsa varsayılan cümle yazılır
 * (ENDED'de boş neden hiç yazılmaz). Marka e-postada AYRI yazılır ("Site Monitor" — sözleşme testi bitişik yazımı reddeder).
 */
public final class SystemMaintenanceMail {

    private SystemMaintenanceMail() {}

    public enum Kind { ANNOUNCE, UPDATED, CANCELLED, ENDED }

    /** Gerçek bitişin plana göre yeri (yalnız {@link Kind#ENDED}). */
    public enum EndShift { ON_TIME, EARLY, EXTENDED }

    /**
     * @param window        "02.10.2026 22:00 – 23:00" (İstanbul saati) — ENDED'de GERÇEKLEŞEN pencere (gerçek bitişle)
     * @param duration      "1 sa 30 dk" gibi okunur süre (ENDED'de gerçek süre)
     * @param messageTr     yöneticinin TR açıklaması (boş → varsayılan)
     * @param messageEn     yöneticinin EN açıklaması (boş → varsayılan)
     * @param contact       isteğe bağlı iletişim bilgisi
     * @param appUrl        uygulamanın taban adresi (boşsa düğme çizilmez)
     * @param plannedWindow planlanan pencere (yalnız ENDED; boşsa satır çizilmez)
     * @param shift         erken bitti / uzatıldı / zamanında (yalnız ENDED; null = zamanında)
     */
    public record Info(Kind kind, String window, String duration, String messageTr, String messageEn,
                       String contact, String appUrl, String plannedWindow, EndShift shift) {
        public Info(Kind kind, String window, String duration, String messageTr, String messageEn, String contact,
                    String appUrl) {
            this(kind, window, duration, messageTr, messageEn, contact, appUrl, null, null);
        }
    }

    public static String subject(Info i) {
        return switch (i.kind()) {
            case ANNOUNCE -> "[Site Monitor] Planlı sistem bakımı · " + i.window();
            case UPDATED -> "[Site Monitor] Bakım saati güncellendi · " + i.window();
            case CANCELLED -> "[Site Monitor] Planlı bakım iptal edildi · " + i.window();
            case ENDED -> "[Site Monitor] Planlı bakım tamamlandı · " + i.window();
        };
    }

    public static MailDoc.Mail build(Info i) {
        boolean cancelled = i.kind() == Kind.CANCELLED;
        boolean ended = i.kind() == Kind.ENDED;
        String lead = switch (i.kind()) {
            case ANNOUNCE -> i.window() + " (İstanbul saati) arasında planlı bakım yapılacaktır. Bakım süresince Site Monitor'e "
                    + "yalnız sistem yöneticileri giriş yapabilir; açık oturumlar bakım başlarken kapatılır.";
            case UPDATED -> "Planlı bakımın saati değişti. Yeni zaman: " + i.window() + " (İstanbul saati).";
            case CANCELLED -> i.window() + " (İstanbul saati) için duyurulan planlı bakım İPTAL edildi. Site Monitor normal "
                    + "şekilde kullanılabilir.";
            case ENDED -> i.window() + " (İstanbul saati) arasında yapılan planlı bakım tamamlandı. Site Monitor yeniden "
                    + "kullanılabilir; tüm kullanıcılar giriş yapabilir.";
        };
        String leadEn = switch (i.kind()) {
            case ANNOUNCE -> "Planned maintenance will take place " + i.window() + " (Istanbul time). During maintenance only "
                    + "system administrators can sign in to Site Monitor; open sessions are closed when it starts.";
            case UPDATED -> "The planned maintenance has been rescheduled. New time: " + i.window() + " (Istanbul time).";
            case CANCELLED -> "The planned maintenance announced for " + i.window() + " (Istanbul time) has been CANCELLED. "
                    + "Site Monitor can be used as usual.";
            case ENDED -> "The planned maintenance " + i.window() + " (Istanbul time) has been completed. Site Monitor is "
                    + "available again; everyone can sign in.";
        };
        Badge badge = switch (i.kind()) {
            case ANNOUNCE -> Badge.solid("PLANLI BAKIM", Tone.WARNING);
            case UPDATED -> Badge.tint("SAAT DEĞİŞTİ", Tone.WARNING);
            case CANCELLED -> Badge.tint("İPTAL EDİLDİ", Tone.SUCCESS);
            case ENDED -> Badge.solid("TAMAMLANDI", Tone.SUCCESS);
        };
        String title = switch (i.kind()) {
            case ANNOUNCE -> "Planlı sistem bakımı";
            case UPDATED -> "Bakım saati güncellendi";
            case CANCELLED -> "Planlı bakım iptal edildi";
            case ENDED -> "Planlı bakım tamamlandı";
        };
        MailDoc d = MailDoc.create(subject(i))
                .preheader(lead)
                .kicker("Sistem Bakımı")
                .badges(badge, Badge.outline("Site Monitor"))
                .title(title, lead);
        if (ended) {
            // Plan / gerçekleşen ayrımı: "Hemen bitir" ya da "Uzat" sonrası gerçek bitiş plandan farklıdır — açıkça yazılır.
            d.keyValue("Bakım penceresi", MailDoc.rows(
                    blank(i.plannedWindow()) ? null : Row.of("Planlanan", i.plannedWindow() + " (İstanbul saati)"),
                    Row.of("Gerçekleşen", i.window() + " (İstanbul saati)"),
                    blank(i.duration()) ? null : Row.of("Süre", i.duration()),
                    Row.of("Durum", shiftTr(i.shift()))));
            d.alert(Tone.SUCCESS, "Site Monitor yeniden kullanılabilir",
                    "Giriş herkese yeniden açıldı. Bakım sırasında oturumu kapatılan kullanıcılar yeniden giriş yaparak "
                            + "çalışmalarına devam edebilir.");
            if (!blank(i.messageTr())) d.alert(Tone.INFO, "Bakım nedeni", i.messageTr());
        } else {
            d.keyValue("Bakım penceresi", MailDoc.rows(
                    Row.of("Zaman", i.window() + " (İstanbul saati)"),
                    blank(i.duration()) ? null : Row.of("Süre", i.duration()),
                    Row.of("Durum", cancelled ? "İptal edildi" : "Planlandı")));
        }
        if (!cancelled && !ended) {
            String why = blank(i.messageTr()) ? "Altyapı ve sürüm güncellemeleri için planlı bakım." : i.messageTr();
            d.alert(Tone.INFO, "Bakım nedeni", why);
            d.bullets(java.util.List.of(
                    "Bakım başlamadan önce çalışmanızı kaydedin; içerideyseniz ekranda geri sayım göreceksiniz.",
                    "Bakım süresince alarmlar ve kontroller sistem tarafından yürütülmeye devam eder.",
                    "Bakım bitiş saatinde giriş yeniden kendiliğinden açılır."));
        }
        if (!blank(i.contact())) d.keyValue("İletişim", MailDoc.rows(Row.of("Bilgi için", i.contact())));
        d.heading("English", null);
        if (ended) {
            d.paragraph(leadEn + shiftEn(i.shift()) + (blank(i.messageEn()) ? "" : " Reason: " + i.messageEn()));
        } else {
            String whyEn = blank(i.messageEn()) ? "Planned maintenance for infrastructure and release updates." : i.messageEn();
            d.paragraph(leadEn + (cancelled ? "" : " Reason: " + whyEn));
        }
        if (!blank(i.appUrl())) d.button(i.appUrl(), "Site Monitor'ü aç");
        String why = ended
                ? "Bu bildirim, Site Monitor sistem yöneticisinin planladığı bakım tamamlandığı için ilgili kullanıcılara gönderildi."
                : "Bu duyuru, Site Monitor sistem yöneticisinin planladığı bakım için tüm ilgili kullanıcılara gönderildi.";
        d.footerWhyCustom(why, why);
        d.footerMeta("Sistem Bakımı", "Site Monitor");
        return d.build();
    }

    /** "Durum" satırı (ENDED) — gerçek bitişin plana göre yeri. */
    static String shiftTr(EndShift s) {
        if (s == EndShift.EARLY) return "Tamamlandı · planlanandan erken bitirildi";
        if (s == EndShift.EXTENDED) return "Tamamlandı · uzatıldı, planlanandan geç bitti";
        return "Tamamlandı · planlanan saatte";
    }

    static String shiftEn(EndShift s) {
        if (s == EndShift.EARLY) return " It ended earlier than planned.";
        if (s == EndShift.EXTENDED) return " It was extended and ended later than planned.";
        return "";
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
