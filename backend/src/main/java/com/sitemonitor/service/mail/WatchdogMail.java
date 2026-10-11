package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Stat;
import com.sitemonitor.service.mail.MailTokens.Tone;

import java.util.ArrayList;
import java.util.List;

/**
 * Kendi kendini izleme bildirimi (2026-10-11) — bir değerlendirme turunun tüm geçişleri TEK e-postada: açılan sorunlar,
 * süren sorun hatırlatmaları, düzelenler. Her satır: kontrol adı, durum, önem, ayrıntı cümlesi (sayılar — ad / adres
 * yok). Sistem Sağlığı sayfasına bağlantı. Düz metin eşi MailDoc'tan.
 */
public final class WatchdogMail {

    private WatchdogMail() {}

    /** Tek satır: {@code kind} = ALERT | REMIND | RECOVER | TEST. */
    public record Item(String kind, String label, String severity, String detail) { }

    public static String subject(List<Item> items, boolean test) {
        if (test) return "[Site Monitor] Kendi kendini izleme — test bildirimi";
        long open = items.stream().filter(i -> !"RECOVER".equals(i.kind())).count();
        long recovered = items.size() - open;
        if (open > 0 && items.size() == 1) return "[Site Monitor] Uygulama sorunu: " + items.get(0).label();
        if (open == 0 && recovered == 1) return "[Site Monitor] Düzeldi: " + items.get(0).label();
        return "[Site Monitor] Kendi kendini izleme · " + open + " sorun" + (recovered > 0 ? " · " + recovered + " düzeldi" : "");
    }

    public static MailDoc.Mail build(List<Item> items, boolean test, String appUrl) {
        List<Item> all = items == null ? List.of() : items;
        boolean anyCritical = all.stream().anyMatch(i -> "CRITICAL".equals(i.severity()) && !"RECOVER".equals(i.kind()));
        long open = all.stream().filter(i -> !"RECOVER".equals(i.kind())).count();
        Tone tone = test ? Tone.INFO : anyCritical ? Tone.DESTRUCTIVE : open > 0 ? Tone.WARNING : Tone.SUCCESS;
        MailDoc d = MailDoc.create(subject(all, test))
                .preheader(test ? "Kendi kendini izleme bildirim kanalları çalışıyor." : open + " sorun, " + (all.size() - open) + " düzelme")
                .kicker("Kendi kendini izleme");
        d.badges(Badge.tint(test ? "TEST" : open > 0 ? open + " SORUN" : "DÜZELDİ", tone), Badge.outline("Site Monitor"));
        d.title(test ? "Test bildirimi" : open > 0 ? "Uygulamanın kendi işleyişinde sorun var" : "Uygulama sorunu düzeldi",
                test ? "Bu e-posta, kendi kendini izleme bildirimlerinin size ulaştığını doğrulamak için gönderildi."
                        : "Site Monitor kendi taramalarını, bildirim kanallarını ve veritabanını dakikada bir denetler. "
                        + "Aşağıdaki durum iki ardışık ölçümde doğrulandı.");
        if (!test) d.stats(List.of(Stat.of("Açık sorun", String.valueOf(open)), Stat.of("Düzelen", String.valueOf(all.size() - open))));
        List<List<Cell>> cells = new ArrayList<>();
        for (Item i : all) {
            Tone rt = "RECOVER".equals(i.kind()) ? Tone.SUCCESS : "CRITICAL".equals(i.severity()) ? Tone.DESTRUCTIVE : Tone.WARNING;
            cells.add(List.of(Cell.of(i.label()), Cell.of(kindText(i.kind()), rt.strong, true), Cell.of(i.detail())));
        }
        if (!cells.isEmpty()) d.table(List.of(Col.of("Kontrol"), Col.nw("Durum"), Col.opt("Ayrıntı")), cells);
        d.note("Sorun sürerse belirli aralıklarla hatırlatma gider; düzelince ayrıca bildirilir. Ayrıntı ve geçmiş: "
                + "Sistem Sağlığı → Kendi kendini izleme.");
        String base = appUrl == null ? "" : appUrl.replaceAll("/+$", "");
        if (!base.isBlank()) d.button(base + "/?tab=health", "Sistem Sağlığı'nı aç →");
        String why = "Bu e-posta, Site Monitor'ün kendi kendini izleme alıcıları arasında olduğunuz için gönderildi. Alıcıları "
                + "Ayarlar → Genel → Kendi kendini izleme bölümünden değiştirebilirsiniz.";
        d.footerWhyCustom(why, why);
        d.footerMeta("Site Monitor — kendi kendini izleme", "Site Monitor");
        return d.build();
    }

    static String kindText(String kind) {
        return switch (kind == null ? "" : kind) {
            case "ALERT" -> "Sorun";
            case "REMIND" -> "Sürüyor";
            case "RECOVER" -> "Düzeldi";
            case "TEST" -> "Test";
            default -> kind;
        };
    }
}
