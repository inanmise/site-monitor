package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Stat;
import com.sitemonitor.service.mail.MailTokens.Tone;

import java.util.ArrayList;
import java.util.List;

/**
 * Olay aksiyonu hatırlatması (2026-10-11) — bir sahibe, son tarihi yaklaşan ya da geçen aksiyonlarının TEK listesi.
 * Satır: aksiyon, olay, son tarih, durum cümlesi (yarın / bugün / N gün gecikti). Olay & Hata Geçmişi'nin Aksiyonlar
 * görünümüne bağlantı. Düz metin eşi MailDoc'tan.
 */
public final class IncidentActionReminderMail {

    private IncidentActionReminderMail() {}

    /** {@code when}: SOON (yarın) | TODAY | OVERDUE; {@code daysLate}: yalnız OVERDUE'da. */
    public record Item(String title, String incidentTitle, String dueDate, String when, long daysLate, String priority) { }

    public static String subject(List<Item> items) {
        long late = items.stream().filter(i -> "OVERDUE".equals(i.when())).count();
        if (items.size() == 1) {
            Item i = items.get(0);
            return "[Site Monitor] " + ("OVERDUE".equals(i.when()) ? "Gecikmiş aksiyon: " : "Aksiyon son tarihi yaklaşıyor: ") + clip(i.title(), 120);
        }
        return "[Site Monitor] Olay aksiyonları · " + items.size() + " aksiyon" + (late > 0 ? " · " + late + " gecikmiş" : "");
    }

    public static MailDoc.Mail build(String ownerName, List<Item> items, String appUrl) {
        List<Item> all = items == null ? List.of() : items;
        long late = all.stream().filter(i -> "OVERDUE".equals(i.when())).count();
        long today = all.stream().filter(i -> "TODAY".equals(i.when())).count();
        Tone tone = late > 0 ? Tone.DESTRUCTIVE : today > 0 ? Tone.WARNING : Tone.INFO;
        MailDoc d = MailDoc.create(subject(all))
                .preheader(all.size() + " aksiyon" + (late > 0 ? ", " + late + " gecikmiş" : ""))
                .kicker("Olay aksiyonları");
        d.badges(Badge.tint(late > 0 ? late + " GECİKMİŞ" : today > 0 ? "BUGÜN" : "YARIN", tone), Badge.outline("Site Monitor"));
        d.title("Size atanan olay aksiyonları",
                (ownerName == null || ownerName.isBlank() ? "Merhaba" : "Merhaba " + ownerName) + ", olay kayıtlarından çıkan ve size "
                + "atanan aşağıdaki aksiyonların son tarihi yaklaştı ya da geçti. İş bittiyse aksiyonu Tamamlandı olarak işaretleyin; "
                + "tarih değişecekse olayın sorumlusuyla yeni tarih belirleyin.");
        d.stats(List.of(Stat.of("Aksiyon", String.valueOf(all.size())), Stat.of("Gecikmiş", String.valueOf(late)),
                Stat.of("Bugün", String.valueOf(today))));
        List<List<Cell>> cells = new ArrayList<>();
        for (Item i : all) {
            Tone rt = "OVERDUE".equals(i.when()) ? Tone.DESTRUCTIVE : "TODAY".equals(i.when()) ? Tone.WARNING : Tone.INFO;
            cells.add(List.of(Cell.of(i.title(), null, true), Cell.of(i.incidentTitle()), Cell.of(dueText(i), rt.strong, true)));
        }
        if (!cells.isEmpty()) d.table(List.of(Col.of("Aksiyon"), Col.opt("Olay"), Col.nw("Son tarih")), cells);
        String base = appUrl == null ? "" : appUrl.replaceAll("/+$", "");
        if (!base.isBlank()) d.button(base + "/?tab=incident-history&ih_view=actions&ih_amine=1", "Aksiyonlarımı aç →");
        String why = "Bu e-posta, olay kayıtlarında size atanmış aksiyonların son tarihi yaklaştığı ya da geçtiği için gönderildi. "
                + "Hatırlatma son tarihten bir gün önce, son tarih günü ve gecikmenin ilk günü, sonra haftada bir gelir.";
        d.footerWhyCustom(why, why);
        d.footerMeta("Site Monitor — olay aksiyonları", "Site Monitor");
        return d.build();
    }

    static String dueText(Item i) {
        String date = formatDay(i.dueDate());
        return switch (i.when() == null ? "" : i.when()) {
            case "SOON" -> date + " · yarın";
            case "TODAY" -> date + " · bugün";
            case "OVERDUE" -> date + " · " + i.daysLate() + " gün gecikti";
            default -> date;
        };
    }

    /** yyyy-MM-dd → GG.AA.YYYY (bozuksa olduğu gibi). */
    static String formatDay(String iso) {
        if (iso == null || iso.length() != 10 || iso.charAt(4) != '-' || iso.charAt(7) != '-') return iso == null ? "—" : iso;
        return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
