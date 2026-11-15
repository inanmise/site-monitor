package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Stat;
import com.sitemonitor.service.oncall.OnCallRotation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Haftalık nöbet bildirimi (2026-11-15, kullanıcı isteği: "takımın nöbetçi bilgisini her hafta pazartesi 08:30'da push ve
 * mail üzerinden ekibe bildirelim"). Takımın alarm adresine giden e-posta ve takım üyelerine giden push metni — TEK kaynak.
 *
 * <p>İçerik: dilimden bir sonraki dilime kadar (7 gün) kim nöbette — her devir ve yer değiştirme ayrı satır (zaman çizelgesi
 * {@link OnCallRotation#timeline}'dan; burada yeniden türetilmez). E-postada nöbetçinin telefonu ve e-postası (biliniyorsa);
 * PASİF hesap "pasif — aranmaz" diye yazılır ve iletişim bilgisi GÖSTERİLMEZ (7/24 arama kâğıdı kuralı). Push kısa; bağlantı
 * içermez (push uygulaması kurum ağı dışında).
 *
 * <p>Takım posta kutusunun dili yoktur: zamanlanmış gönderim Türkçe yazar (iş kuralı {@code Msg.t} geri dönüşü); İngilizce
 * metin push alıcısının diline göre ve örnek / önizleme için.
 */
public final class OnCallWeeklyNoticeMail {

    private OnCallWeeklyNoticeMail() {}

    /** Nöbetçi kişi. {@code missing} = hesap silinmiş (ad yok). */
    public record Person(String name, String phone, String email, boolean active, boolean missing) { }

    /** Bir nöbet parçası; {@code person} null = bu aralıkta nöbetçi yok. */
    public record Segment(Instant start, Instant end, Person person, boolean override) { }

    /**
     * Bildirim içeriği: takım, kapsanan hafta ve parçalar. {@code omitted} = tavan ({@link OnCallRotation#MAX_TIMELINE})
     * yüzünden listeye girmeyen devir sayısı — e-posta ve push bunu açıkça söyler (sessiz kesme yok).
     */
    public record Info(String teamName, Instant from, Instant to, List<Segment> segments, String appUrl, int omitted) {
        public Info {
            segments = segments == null ? List.of() : List.copyOf(segments);
            omitted = Math.max(0, omitted);
        }

        public Info(String teamName, Instant from, Instant to, List<Segment> segments, String appUrl) {
            this(teamName, from, to, segments, appUrl, 0);
        }
    }

    /** "+3 devir daha gösterilmiyor" / "+3 more handovers not shown" — kesilmediyse null. */
    public static String omittedText(int omitted, boolean en) {
        if (omitted <= 0) return null;
        return en ? "+" + omitted + (omitted == 1 ? " more handover not shown" : " more handovers not shown")
                : "+" + omitted + " devir daha gösterilmiyor";
    }

    private static final String[] DAY_TR = {"Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz"};
    private static final String[] DAY_EN = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] MON_EN = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    // ── Konu / biçim ──────────────────────────────────────────────────────────────────────────

    public static String subject(String teamName, Instant from, boolean en) {
        String team = blank(teamName) ? (en ? "Team" : "Takım") : teamName.trim();
        return en ? "[Site Monitor] Weekly on-call: " + team + " · week of " + date(from, true)
                : "[Site Monitor] Haftalık nöbet: " + team + " · " + date(from, false) + " haftası";
    }

    /** "16.11.2026" / "16 Nov 2026" (İstanbul). */
    static String date(Instant at, boolean en) {
        if (at == null) return "—";
        LocalDateTime t = LocalDateTime.ofInstant(at, OnCallRotation.TZ);
        return en ? t.getDayOfMonth() + " " + MON_EN[t.getMonthValue() - 1] + " " + t.getYear()
                : pad(t.getDayOfMonth()) + "." + pad(t.getMonthValue()) + "." + t.getYear();
    }

    /** "Pzt 16.11.2026 08:30" / "Mon 16 Nov 2026 08:30" (İstanbul). */
    public static String dayTime(Instant at, boolean en) {
        if (at == null) return "—";
        LocalDateTime t = LocalDateTime.ofInstant(at, OnCallRotation.TZ);
        String d = (en ? DAY_EN : DAY_TR)[t.getDayOfWeek().getValue() - 1];
        return d + " " + date(at, en) + " " + pad(t.getHour()) + ":" + pad(t.getMinute());
    }

    /** Push için kısa "Çar 09:00" / "Wed 09:00". */
    static String shortDayTime(Instant at, boolean en) {
        if (at == null) return "—";
        LocalDateTime t = LocalDateTime.ofInstant(at, OnCallRotation.TZ);
        return (en ? DAY_EN : DAY_TR)[t.getDayOfWeek().getValue() - 1] + " " + pad(t.getHour()) + ":" + pad(t.getMinute());
    }

    /** Kişinin görünen adı (silinmiş / yok / pasif işaretli). */
    static String personLabel(Person p, boolean en) {
        if (p == null) return en ? "nobody on call" : "nöbetçi yok";
        if (p.missing()) return en ? "deleted account" : "silinmiş hesap";
        String n = blank(p.name()) ? "—" : p.name().trim();
        return p.active() ? n : n + (en ? " (inactive)" : " (pasif)");
    }

    // ── Push ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Kısa push metni. Tek kişi: "Takım - bu hafta nöbetçi: A". Devir varsa: "Takım - bu hafta nöbet: A; Çar 09:00 devir: B".
     * Uzunluk push servisinde canlı sınıra sığdırılır; özel karakter yok (push kanalı ISO-8859-9).
     */
    public static String pushText(Info in, boolean en) {
        String team = blank(in.teamName()) ? "" : in.teamName().trim() + " - ";
        List<Segment> segs = in.segments();
        if (segs.isEmpty()) return team + (en ? "on call this week: nobody" : "bu hafta nöbetçi yok");
        String more = omittedText(in.omitted(), en);
        if (segs.size() == 1) {
            return team + (en ? "on call this week: " : "bu hafta nöbetçi: ") + personLabel(segs.get(0).person(), en)
                    + (more == null ? "" : " (" + more + ")");
        }
        StringBuilder sb = new StringBuilder(team).append(en ? "on call this week: " : "bu hafta nöbet: ")
                .append(personLabel(segs.get(0).person(), en));
        for (int i = 1; i < segs.size(); i++) {
            Segment s = segs.get(i);
            sb.append("; ");
            if (en) sb.append("handover ").append(shortDayTime(s.start(), true)).append(": ");
            else sb.append(shortDayTime(s.start(), false)).append(" devir: ");
            sb.append(personLabel(s.person(), en));
        }
        if (more != null) sb.append(" (").append(more).append(')');
        return sb.toString();
    }

    // ── E-posta ──────────────────────────────────────────────────────────────────────────────

    public static MailDoc.Mail build(Info in, boolean en) {
        String team = blank(in.teamName()) ? (en ? "Team" : "Takım") : in.teamName().trim();
        List<Segment> segs = in.segments();
        Person first = segs.isEmpty() ? null : segs.get(0).person();
        Set<String> people = new LinkedHashSet<>();
        for (Segment s : segs) if (s.person() != null && !s.person().missing()) people.add(personLabel(s.person(), en));

        MailDoc d = MailDoc.create(subject(team, in.from(), en))
                .preheader((en ? "On call this week: " : "Bu hafta nöbetçi: ") + personLabel(first, en))
                .kicker(en ? "Weekly on-call notice" : "Haftalık nöbet bildirimi");
        d.badges(Badge.outline(team));
        d.title(en ? team + " — this week's on-call" : team + " — bu haftanın nöbetçisi",
                en ? "Who is on call from " + dayTime(in.from(), true) + " to " + dayTime(in.to(), true)
                        + ", with every handover and override. Times are Turkey time."
                   : dayTime(in.from(), false) + " – " + dayTime(in.to(), false)
                        + " arasında kim nöbette; her devir ve yer değiştirme ayrı satırda. Saatler Türkiye saatidir.");
        d.stats(List.of(Stat.of(en ? "On call now" : "Haftanın ilk nöbetçisi", personLabel(first, en)),
                Stat.of(en ? "People this week" : "Bu hafta nöbet tutan", String.valueOf(people.size()))));

        List<List<Cell>> rows = new ArrayList<>();
        for (Segment s : segs) {
            Person p = s.person();
            String who = personLabel(p, en) + (s.override() ? (en ? " · override" : " · yer değiştirme") : "");
            rows.add(List.of(
                    Cell.of(dayTime(s.start(), en), null, true),
                    Cell.of(dayTime(s.end(), en)),
                    Cell.of(who, null, p != null && p.active() && !p.missing()),
                    Cell.of(contact(p, en))));
        }
        d.table(List.of(Col.nw(en ? "From" : "Başlangıç"), Col.nw(en ? "Until" : "Bitiş"), Col.of(en ? "On call" : "Nöbetçi"),
                Col.of(en ? "Contact" : "İletişim")), rows, true);
        String more = omittedText(in.omitted(), en);
        if (more != null) {
            d.paragraph(en ? more + " — this week has more handovers than one e-mail lists. See the full schedule in Site Monitor."
                    : more + " — bu hafta bir e-postanın listeleyebileceğinden fazla devir var. Tam çizelge Site Monitor'de.");
        }
        d.note(en ? "The schedule and overrides are managed in Site Monitor (Teams → On-call schedule). Your team turned this "
                        + "notice on; the day, time and channels are changed — or the notice turned off — in the same window."
                : "Nöbet çizelgesi ve yer değiştirmeler Site Monitor'de yönetilir (Takımlar → Nöbet çizelgesi). Bu bildirimi takımınız "
                        + "açtı; günü, saati ve kanalları aynı pencereden değiştirebilir ya da bildirimi kapatabilirsiniz.");
        String base = in.appUrl() == null ? "" : in.appUrl().replaceAll("/+$", "");
        if (!base.isBlank()) d.button(base + "/?tab=admin&g_tab=teams", en ? "Open Teams →" : "Takımlar'ı aç →");
        String why = en ? "You received this e-mail because the team " + team + " turned on its weekly on-call notice in Site Monitor."
                : "Bu e-posta, " + team + " takımı Site Monitor'de haftalık nöbet bildirimini açtığı için gönderildi.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — on-call notice" : "Site Monitor — nöbet bildirimi", "Site Monitor");
        return d.build();
    }

    /** İletişim hücresi: telefon · e-posta (biliniyorsa). Pasif / silinmiş / boş → bilgi yok. */
    static String contact(Person p, boolean en) {
        if (p == null || p.missing()) return "—";
        if (!p.active()) return en ? "Inactive account — do not call" : "Pasif hesap — aranmaz";
        List<String> parts = new ArrayList<>();
        if (!blank(p.phone())) parts.add(p.phone().trim());
        if (!blank(p.email())) parts.add(p.email().trim());
        return parts.isEmpty() ? (en ? "Not known" : "Kayıtlı değil") : String.join(" · ", parts);
    }

    private static String pad(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
