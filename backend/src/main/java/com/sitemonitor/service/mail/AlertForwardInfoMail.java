package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Row;
import com.sitemonitor.service.mail.MailTokens.Tone;

import java.util.ArrayList;
import java.util.List;

/**
 * Alarm iletimi bilgi postası (2026-11-05, kullanıcı kararı 8): bir takım alarmlarını başka bir takıma ya da o takımın seçilen
 * üyelerine iletmeye başlayınca alıcılara TEK SEFERLİK bilgi. Sahip takım, kapsam özeti (tür adları / izleme adları — en çok
 * {@value #MAX_SCOPE_ITEMS} + "+N"), alıcı, ekleyen ve not; "Site Monitor'de Alarm iletimi" bağlantısı. Sahip takımın adresleri
 * ve kişileri YAZILMAZ. Bağlantısız düz metin eşi de üretilir ({@link MailDoc}).
 */
public final class AlertForwardInfoMail {

    private AlertForwardInfoMail() {}

    /** Kapsam listesinde gösterilen en çok öğe (geri kalanı "+N"). */
    public static final int MAX_SCOPE_ITEMS = 20;

    /**
     * @param ownerTeam      alarmların sahibi takım
     * @param recipientTeam  alıcı takım
     * @param wholeTeam      takımın tamamına (true) ya da seçilen üyelere (false)
     * @param memberNames    seçilen üyelerin adları (wholeTeam iken boş)
     * @param scopeKind      ALL | TYPES | MONITORS
     * @param scopeItems     tür adları (TYPES) ya da izleme adları (MONITORS); ALL'da boş
     * @param addedBy        ekleyenin görünen adı
     * @param note           not (boş olabilir)
     * @param expiresOn      bitiş günü (yyyy-MM-dd; boş = süresiz)
     * @param appUrl         uygulama kökü (boşsa bağlantı yok)
     */
    public record Info(String ownerTeam, String recipientTeam, boolean wholeTeam, List<String> memberNames, String scopeKind,
                       List<String> scopeItems, String addedBy, String note, String expiresOn, String appUrl) {
        public Info {
            memberNames = memberNames == null ? List.of() : List.copyOf(memberNames);
            scopeItems = scopeItems == null ? List.of() : List.copyOf(scopeItems);
        }
    }

    public static String subject(String ownerTeam) {
        return "[Site Monitor] Alarm iletimi: " + (blank(ownerTeam) ? "bir takımın" : ownerTeam + " takımının")
                + " alarmları size de gelecek";
    }

    public static MailDoc.Mail build(Info in) {
        String owner = blank(in.ownerTeam()) ? "Bir takım" : in.ownerTeam();
        String recipient = blank(in.recipientTeam()) ? "takımınız" : in.recipientTeam();
        String scope = scopeSummary(in.scopeKind(), in.scopeItems().size());
        MailDoc d = MailDoc.create(subject(in.ownerTeam()))
                .wide()
                .preheader(owner + " takımının alarmları bundan sonra size de iletilecek · " + scope)
                .kicker("Alarm iletimi");
        d.badges(Badge.tint("YENİ İLETİM", Tone.INFO), Badge.outline(recipient));
        String target = in.wholeTeam() ? recipient + " takımına" : recipient + " takımının seçilen üyelerine";
        d.title(owner + " takımının alarmları size iletilecek",
                owner + " takımı, kendi izlemelerinin alarmlarını " + target + " iletmeye başladı. Bundan sonra bu alarmlar "
                        + "size ayrı bir e-postayla ve push bildirimiyle gelecek: açılış, seviye yükselmesi, günlük hatırlatma "
                        + "ve çözülme — " + owner + " takımı ne alıyorsa aynısı.");
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of("Alarmların sahibi", owner));
        rows.add(Row.of("Alıcı", in.wholeTeam() ? recipient + " (takımın tamamı)"
                : recipient + " — " + in.memberNames().size() + " seçilen üye"));
        rows.add(Row.of("Kapsam", scope));
        if (!blank(in.addedBy())) rows.add(Row.of("Ekleyen", in.addedBy()));
        rows.add(Row.of("Bitiş", blank(in.expiresOn()) ? "Süresiz" : in.expiresOn() + " (bu günün sonuna kadar)"));
        d.keyValue(rows);
        if (!in.wholeTeam() && !in.memberNames().isEmpty()) {
            d.heading("Seçilen üyeler");
            d.bullets(limited(in.memberNames()));
        }
        if (!in.scopeItems().isEmpty()) {
            d.heading("TYPES".equals(in.scopeKind()) ? "İletilen izleme türleri" : "İletilen izlemeler");
            d.bullets(limited(in.scopeItems()));
        }
        if (!blank(in.note())) d.card("Not", null, MailKit.esc(in.note()), in.note());
        d.note("İletimi yalnız " + owner + " takımı değiştirebilir ya da kaldırabilir. Site Monitor'de görüntüleme erişiminiz "
                + "yoksa alarmın ayrıntısı için " + owner + " takımına başvurun.");
        String base = in.appUrl() == null ? "" : stripSlashes(in.appUrl());
        if (!base.isBlank()) d.button(base + "/?tab=admin&g_tab=forwards", "Site Monitor'de Alarm iletimi →");
        String why = "Bu e-posta, " + owner + " takımı alarmlarını " + target + " iletmeye başladığı için bir kez gönderildi.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta("Site Monitor — Alarm iletimi", "Site Monitor");
        return d.build();
    }

    /** Kapsamın tek satırlık özeti. */
    public static String scopeSummary(String scopeKind, int count) {
        if ("TYPES".equals(scopeKind)) return count + " izleme türünün tüm izlemeleri";
        if ("MONITORS".equals(scopeKind)) return count + " seçilen izleme";
        return "Tüm izlemeler";
    }

    /** En çok {@value #MAX_SCOPE_ITEMS} öğe + "ve N tane daha". */
    static List<String> limited(List<String> all) {
        if (all.size() <= MAX_SCOPE_ITEMS) return all;
        List<String> out = new ArrayList<>(all.subList(0, MAX_SCOPE_ITEMS));
        out.add("ve " + (all.size() - MAX_SCOPE_ITEMS) + " tane daha");
        return out;
    }

    /** Sondaki eğik çizgileri atar (düzenli ifade yok). */
    private static String stripSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
