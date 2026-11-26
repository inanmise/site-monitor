package com.sitemonitor.service.mail;

import com.sitemonitor.service.PushText;
import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Stat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Nöbet rutini bildirimleri (2026-11-26) — günlük rutin tamamlandı e-postası, nöbet devri e-postası ve push metinleri TEK
 * kaynak. Takım posta kutusunun dili yoktur: zamanlanmış gönderim Türkçe yazar; İngilizce yapıcı push alıcısının diline göre
 * ve örnekler için. Görev açıklaması ve bağlantıları ASLA e-postaya / push'a girmez (bağlantılar belirteç taşıyabilir); push
 * metninde kullanıcı serbest metni (bölüm adı, not) yoktur.
 */
public final class OnCallRoutineMail {

    private OnCallRoutineMail() {}

    static final ZoneId TZ = ZoneId.of("Europe/Istanbul");
    static final int MAX_SKIPPED_ROWS = 50;
    static final int MAX_PROBLEM_ROWS = 20;
    static final int MAX_OPEN_PROBLEM_ROWS = 50;
    static final int MAX_DAY_NOTES = 7;
    private static final String[] MON_EN = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** Atlanan görev satırı. */
    public record Skipped(String section, String task, String reason) { }

    /** Sorun satırı ({@code day} = kaydedildiği gün). */
    public record Problem(String severity, String task, String day, String note) { }

    /** Tamamlama bildirimi içeriği. */
    public record Completion(String teamName, Long teamId, LocalDate date, String completerName, Instant completedAt,
                             String responsibleName, int required, int done, int skipped, int problemsToday, int problemsOpen,
                             int seq, boolean late, boolean partial, List<Skipped> skippedItems, List<Problem> todayProblems,
                             List<Problem> olderProblems, String dayNote, Integer weekDone, Integer weekTotal, String appUrl) {
        public Completion {
            skippedItems = skippedItems == null ? List.of() : List.copyOf(skippedItems);
            todayProblems = todayProblems == null ? List.of() : List.copyOf(todayProblems);
            olderProblems = olderProblems == null ? List.of() : List.copyOf(olderProblems);
        }
    }

    /** Devir günü satırı. */
    public record HandoverDay(LocalDate date, String status, Integer done, Integer required, Integer skipped, Integer problems,
                              String completerName) { }

    /** Haftalık / aylık görev satırı. */
    public record PeriodItem(String title, String state) { }

    /** Devir bildirimi içeriği. */
    public record Handover(String teamName, Long teamId, LocalDate from, LocalDate to, List<String> responsibleNames,
                           String nextName, int workdays, int completedDays, int skippedItems, int missedItems, int problemsOpen,
                           List<HandoverDay> days, List<PeriodItem> periodItems, List<Problem> openProblems, String note,
                           List<String[]> dayNotes, String appUrl) {
        public Handover {
            responsibleNames = responsibleNames == null ? List.of() : List.copyOf(responsibleNames);
            days = days == null ? List.of() : List.copyOf(days);
            periodItems = periodItems == null ? List.of() : List.copyOf(periodItems);
            openProblems = openProblems == null ? List.of() : List.copyOf(openProblems);
            dayNotes = dayNotes == null ? List.of() : List.copyOf(dayNotes);
        }
    }

    // ── Biçim ────────────────────────────────────────────────────────────────────────────────

    static String team(String teamName, boolean en) {
        return teamName == null || teamName.isBlank() ? (en ? "Team" : "Takım") : teamName.trim();
    }

    /** "26.11.2026" / "26 Nov 2026". */
    public static String date(LocalDate d, boolean en) {
        if (d == null) return "—";
        return en ? d.getDayOfMonth() + " " + MON_EN[d.getMonthValue() - 1] + " " + d.getYear()
                : pad(d.getDayOfMonth()) + "." + pad(d.getMonthValue()) + "." + d.getYear();
    }

    /** "26.11" / "26 Nov". */
    static String shortDate(LocalDate d, boolean en) {
        if (d == null) return "—";
        return en ? d.getDayOfMonth() + " " + MON_EN[d.getMonthValue() - 1] : pad(d.getDayOfMonth()) + "." + pad(d.getMonthValue());
    }

    static String clock(Instant at) {
        if (at == null) return "—";
        LocalDateTime t = LocalDateTime.ofInstant(at, TZ);
        return pad(t.getHour()) + ":" + pad(t.getMinute());
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    static String severity(String s, boolean en) {
        if (s == null) return "—";
        return switch (s) {
            case "HIGH" -> en ? "High" : "Yüksek";
            case "MEDIUM" -> en ? "Medium" : "Orta";
            case "LOW" -> en ? "Low" : "Düşük";
            default -> s;
        };
    }

    static String dayStatus(String s, boolean en) {
        if (s == null) return "—";
        return switch (s) {
            case "COMPLETED" -> en ? "Completed" : "Tamamlandı";
            case "COMPLETED_PARTIAL" -> en ? "Completed with skipped tasks" : "Eksiklerle tamamlandı";
            case "INCOMPLETE" -> en ? "Incomplete" : "Eksik kaldı";
            case "MISSED" -> en ? "Not done" : "Yapılmadı";
            case "OPEN" -> en ? "Open" : "Açık";
            case "NOT_WORKDAY" -> en ? "Not a workday" : "İş günü değil";
            case "PAUSED" -> en ? "Paused" : "Duraklatıldı";
            default -> en ? "Not recorded" : "Kayıt yok";
        };
    }

    static String itemState(String s, boolean en) {
        if (s == null) return "—";
        return switch (s) {
            case "DONE" -> en ? "Done" : "Yapıldı";
            case "SKIPPED" -> en ? "Skipped" : "Atlandı";
            case "MISSED" -> en ? "Not done" : "Yapılmadı";
            default -> en ? "Open" : "Bekliyor";
        };
    }

    // ── Tamamlama ────────────────────────────────────────────────────────────────────────────

    public static String completionSubject(Completion in, boolean en) {
        String team = team(in.teamName(), en);
        List<String> flags = new ArrayList<>();
        if (in.seq() > 1) flags.add(en ? "updated" : "güncellendi");
        if (in.late()) flags.add(en ? "late" : "geç");
        if (in.partial()) flags.add(en ? "with skipped tasks" : "eksiklerle");
        String suffix = flags.isEmpty() ? "" : " (" + String.join(", ", flags) + ")";
        return (en ? "[Site Monitor] On-call routine done: " : "[Site Monitor] Nöbet rutini tamamlandı: ") + team + " · "
                + date(in.date(), en) + suffix;
    }

    public static MailDoc.Mail completion(Completion in, boolean en) {
        String team = team(in.teamName(), en);
        MailDoc d = MailDoc.create(completionSubject(in, en))
                .preheader(en ? in.done() + "/" + in.required() + " tasks done · " + in.problemsOpen() + " open problems"
                        : in.done() + "/" + in.required() + " görev tamamlandı · " + in.problemsOpen() + " açık sorun")
                .kicker(en ? "On-call routine" : "Nöbet rutini");
        d.badges(Badge.outline(team));
        String completer = in.completerName() == null ? "—" : in.completerName();
        String resp = in.responsibleName() == null ? (en ? "not assigned" : "atanmamış") : in.responsibleName();
        d.title(en ? team + " — daily routine of " + date(in.date(), true) : team + " — " + date(in.date(), false) + " günlük rutini",
                en ? "Completed by " + completer + " · " + clock(in.completedAt()) + ". On call: " + resp + "."
                        : "Tamamlayan: " + completer + " · " + clock(in.completedAt()) + ". Nöbetçi: " + resp + ".");
        d.stats(List.of(
                Stat.of(en ? "Done" : "Tamamlanan", in.done() + "/" + in.required()),
                Stat.of(en ? "Skipped" : "Atlanan", String.valueOf(in.skipped())),
                Stat.of(en ? "Problems recorded today" : "Bugün kaydedilen sorun", String.valueOf(in.problemsToday())),
                Stat.of(en ? "Open problems (total)" : "Açık sorun (toplam)", String.valueOf(in.problemsOpen()))));
        if (!in.skippedItems().isEmpty()) {
            d.heading(en ? "Skipped tasks" : "Atlanan görevler");
            List<List<Cell>> rows = new ArrayList<>();
            for (Skipped s : head(in.skippedItems(), MAX_SKIPPED_ROWS)) {
                rows.add(List.of(Cell.of(s.section() == null ? "—" : s.section()), Cell.of(s.task(), null, true),
                        Cell.of(s.reason() == null ? "—" : clip(s.reason(), 300))));
            }
            d.table(List.of(Col.of(en ? "Section" : "Bölüm"), Col.of(en ? "Task" : "Görev"), Col.of(en ? "Reason" : "Neden")),
                    rows, true);
            more(d, in.skippedItems().size(), MAX_SKIPPED_ROWS, en);
        }
        if (!in.todayProblems().isEmpty()) {
            d.heading(en ? "Problems recorded today" : "Bugün kaydedilen sorunlar");
            d.table(problemCols(en, false), problemRows(in.todayProblems(), MAX_PROBLEM_ROWS, en, false, 300), true);
            more(d, in.todayProblems().size(), MAX_PROBLEM_ROWS, en);
        }
        if (!in.olderProblems().isEmpty()) {
            d.heading(en ? "Open problems from earlier days" : "Önceki günlerden açık sorunlar");
            d.table(problemCols(en, true), problemRows(in.olderProblems(), MAX_PROBLEM_ROWS, en, true, 200), true);
            more(d, in.olderProblems().size(), MAX_PROBLEM_ROWS, en);
        }
        if (in.dayNote() != null && !in.dayNote().isBlank()) {
            d.heading(en ? "Note of the day" : "Günün notu");
            d.paragraph(clip(in.dayNote(), 2000));
        }
        if (in.weekTotal() != null && in.weekTotal() > 0) {
            d.paragraph(en ? "This week's tasks: " + in.weekDone() + "/" + in.weekTotal() + " done"
                    : "Bu haftanın görevleri: " + in.weekDone() + "/" + in.weekTotal() + " tamamlandı");
        }
        String base = base(in.appUrl());
        if (!base.isBlank() && in.teamId() != null) {
            d.button(base + "/?tab=oncallroutines&or_team=" + in.teamId() + "&or_day=" + in.date(),
                    en ? "Open the on-call routine →" : "Nöbet rutinini aç →");
        }
        d.note(en ? "Your team turned this notice on; it can be turned off in On-call routines → Settings."
                : "Bu bildirimi takımınız açtı; Nöbet Rutinleri → Ayarlar'dan kapatabilir.");
        String why = en ? "You received this e-mail because the on-call routine of the team " + team + " was completed in Site Monitor."
                : "Bu e-posta, " + team + " takımının nöbet rutini Site Monitor'de tamamlandığı için gönderildi.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — on-call routine" : "Site Monitor — nöbet rutini", "Site Monitor");
        return d.build();
    }

    // ── Devir ────────────────────────────────────────────────────────────────────────────────

    public static String handoverSubject(String teamName, LocalDate from, LocalDate to, boolean en) {
        String team = team(teamName, en);
        return en ? "[Site Monitor] On-call handover: " + team + " · " + shortDate(from, true) + " – " + date(to, true)
                : "[Site Monitor] Nöbet devri: " + team + " · " + shortDate(from, false) + " – " + date(to, false);
    }

    public static MailDoc.Mail handover(Handover in, boolean en) {
        String team = team(in.teamName(), en);
        MailDoc d = MailDoc.create(handoverSubject(in.teamName(), in.from(), in.to(), en))
                .preheader(en ? in.completedDays() + "/" + in.workdays() + " days completed · " + in.problemsOpen() + " open problems"
                        : in.completedDays() + "/" + in.workdays() + " gün tamamlandı · " + in.problemsOpen() + " açık sorun")
                .kicker(en ? "On-call handover" : "Nöbet devri");
        d.badges(Badge.outline(team));
        String names = in.responsibleNames().isEmpty() ? "—" : String.join(", ", in.responsibleNames());
        String next = in.nextName() == null || in.nextName().isBlank() ? "—" : in.nextName();
        d.title(en ? team + " — on-call handover" : team + " — nöbet devri",
                en ? date(in.from(), true) + " – " + date(in.to(), true) + " · On call: " + names + ". Taking over: " + next
                        : date(in.from(), false) + " – " + date(in.to(), false) + " · Nöbetçi: " + names + ". Devralan: " + next);
        d.stats(List.of(
                Stat.of(en ? "Days completed" : "Tamamlanan gün", in.completedDays() + "/" + in.workdays()),
                Stat.of(en ? "Skipped tasks" : "Atlanan görev", String.valueOf(in.skippedItems())),
                Stat.of(en ? "Missed tasks" : "Kaçırılan görev", String.valueOf(in.missedItems())),
                Stat.of(en ? "Open problems" : "Açık sorun", String.valueOf(in.problemsOpen()))));
        if (!in.days().isEmpty()) {
            List<List<Cell>> rows = new ArrayList<>();
            for (HandoverDay x : in.days()) {
                rows.add(List.of(Cell.of(date(x.date(), en), null, true), Cell.of(dayStatus(x.status(), en)),
                        Cell.of(x.required() == null ? "—" : (x.done() == null ? 0 : x.done()) + "/" + x.required()),
                        Cell.of(x.skipped() == null ? "—" : String.valueOf(x.skipped())),
                        Cell.of(x.problems() == null ? "—" : String.valueOf(x.problems())),
                        Cell.of(x.completerName() == null ? "—" : x.completerName())));
            }
            d.table(List.of(Col.nw(en ? "Day" : "Gün"), Col.of(en ? "Status" : "Durum"), Col.nw(en ? "Done" : "Tamamlanan"),
                    Col.nw(en ? "Skipped" : "Atlanan"), Col.nw(en ? "Problems" : "Sorun"),
                    Col.of(en ? "Completed by" : "Tamamlayan")), rows, true);
        }
        if (!in.periodItems().isEmpty()) {
            d.heading(en ? "Weekly and monthly tasks" : "Haftalık ve aylık görevler");
            List<List<Cell>> rows = new ArrayList<>();
            for (PeriodItem p : head(in.periodItems(), MAX_OPEN_PROBLEM_ROWS)) {
                rows.add(List.of(Cell.of(p.title(), null, true), Cell.of(itemState(p.state(), en))));
            }
            d.table(List.of(Col.of(en ? "Task" : "Görev"), Col.of(en ? "Status" : "Durum")), rows, true);
            more(d, in.periodItems().size(), MAX_OPEN_PROBLEM_ROWS, en);
        }
        if (!in.openProblems().isEmpty()) {
            d.heading(en ? "Open problems" : "Açık sorunlar");
            d.table(problemCols(en, true), problemRows(in.openProblems(), MAX_OPEN_PROBLEM_ROWS, en, true, 200), true);
            more(d, in.openProblems().size(), MAX_OPEN_PROBLEM_ROWS, en);
        }
        if (in.note() != null && !in.note().isBlank()) {
            d.heading(en ? "Note to the next on-call" : "Sonraki nöbetçiye not");
            d.paragraph(clip(in.note(), 4000));
        }
        if (!in.dayNotes().isEmpty()) {
            d.heading(en ? "Day notes" : "Gün notları");
            List<String> lines = new ArrayList<>();
            for (String[] n : head(in.dayNotes(), MAX_DAY_NOTES)) {
                LocalDate day = parse(n[0]);
                lines.add((day == null ? n[0] : date(day, en)) + (n[2] == null ? "" : " · " + n[2]) + ": " + clip(n[1], 500));
            }
            d.bullets(lines);
        }
        String base = base(in.appUrl());
        if (!base.isBlank() && in.teamId() != null) {
            d.button(base + "/?tab=oncallroutines&or_team=" + in.teamId() + "&or_view=week",
                    en ? "Open the on-call routine →" : "Nöbet rutinini aç →");
        }
        d.note(en ? "Your team turned this notice on; it can be turned off in On-call routines → Settings."
                : "Bu bildirimi takımınız açtı; Nöbet Rutinleri → Ayarlar'dan kapatabilir.");
        String why = en ? "You received this e-mail because an on-call period of the team " + team + " ended in Site Monitor."
                : "Bu e-posta, " + team + " takımının bir nöbet dönemi Site Monitor'de sona erdiği için gönderildi.";
        d.footerWhyCustom(MailKit.esc(why), why);
        d.footerMeta(en ? "Site Monitor — on-call routine" : "Site Monitor — nöbet rutini", "Site Monitor");
        return d.build();
    }

    // ── Push ─────────────────────────────────────────────────────────────────────────────────

    /**
     * "{team} - {rest}" metnini sınıra sığdırır: önce takım adı kısalır (sözcük sınırında); 4 karakterden kısa kalırsa takım
     * adı düşer. Gövde (rest) asla kesilmez — gövdeler sınırın en düşük değerine (80) sığacak kadar kısadır.
     */
    static String fit(String teamName, Function<String, String> body, int limit) {
        String team = teamName == null ? "" : PushText.pushSafe(teamName.trim());
        String full = PushText.pushSafe(body.apply(team));
        if (limit <= 0 || full.length() <= limit) return full;
        int room = team.length() - (full.length() - limit);
        if (room >= 4) return PushText.pushSafe(body.apply(PushText.truncate(team, room)));
        return PushText.pushSafe(body.apply(""));   // takım adı düşer: yalnız gövde
    }

    /** Boş takım adı → yalnız gövde (başta "- " kalmaz). */
    private static Function<String, String> withTeam(String tail) {
        return team -> team == null || team.isEmpty() ? tail : team + " - " + tail;
    }

    public static String pushDone(String team, int done, int required, int skipped, int open, boolean en, int limit) {
        StringBuilder t = new StringBuilder(en ? "on-call routine done: " : "nöbet rutini tamamlandı: ").append(done).append('/').append(required);
        if (skipped > 0) t.append(en ? ", " + skipped + " skipped" : ", " + skipped + " atlandı");
        if (open > 0) t.append(en ? ", " + open + " open problems" : ", " + open + " açık sorun");
        return fit(team, withTeam(t.toString()), limit);
    }

    public static String pushReminder(String team, int pending, boolean firstSlot, boolean en, int limit) {
        String tail = en ? pending + " on-call routine tasks are open" : "nöbet rutininde " + pending + " görev bekliyor";
        String out = fit(team, withTeam(tail), limit - (firstSlot ? 0 : (en ? 10 : 13)));
        return firstSlot ? out : (en ? "Reminder: " : "Hatırlatma: ") + out;
    }

    public static String pushSection(String team, String hhmm, int n, boolean en, int limit) {
        String tail = en ? n + " tasks of the " + hhmm + " section are open" : hhmm + " hedefli bölümde " + n + " görev bekliyor";
        return fit(team, withTeam(tail), limit);
    }

    public static String pushDue(String team, int n, boolean en, int limit) {
        String tail = en ? n + " weekly/monthly tasks are due today" : "bugün son günü olan " + n + " haftalık/aylık görev var";
        return fit(team, withTeam(tail), limit);
    }

    public static String pushHandover(String team, int completed, int workdays, int open, boolean en, int limit) {
        String tail = en ? "handover: " + completed + "/" + workdays + " days done, " + open + " open problems"
                : "nöbet devri: " + completed + "/" + workdays + " gün tamamlandı, " + open + " açık sorun";
        return fit(team, withTeam(tail), limit);
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────

    private static List<Col> problemCols(boolean en, boolean withDay) {
        List<Col> cols = new ArrayList<>();
        cols.add(Col.nw(en ? "Severity" : "Önem"));
        cols.add(Col.of(en ? "Task" : "Görev"));
        if (withDay) cols.add(Col.nw(en ? "Day" : "Gün"));
        cols.add(Col.of(en ? "Note" : "Not"));
        return cols;
    }

    private static List<List<Cell>> problemRows(List<Problem> list, int max, boolean en, boolean withDay, int noteMax) {
        List<List<Cell>> rows = new ArrayList<>();
        for (Problem p : head(list, max)) {
            List<Cell> r = new ArrayList<>();
            r.add(Cell.of(severity(p.severity(), en), null, "HIGH".equals(p.severity())));
            r.add(Cell.of(p.task() == null ? (en ? "General" : "Genel") : p.task()));
            if (withDay) {
                LocalDate day = parse(p.day());
                r.add(Cell.of(day == null ? "—" : date(day, en)));
            }
            r.add(Cell.of(clip(p.note(), noteMax)));
            rows.add(r);
        }
        return rows;
    }

    private static void more(MailDoc d, int total, int shown, boolean en) {
        if (total > shown) d.paragraph(en ? "+" + (total - shown) + " more — see Site Monitor." : "+" + (total - shown) + " daha — Site Monitor'de.");
    }

    private static <T> List<T> head(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }

    private static LocalDate parse(String s) {
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String base(String appUrl) {
        return appUrl == null ? "" : appUrl.replaceAll("/+$", "");
    }

    private static String pad(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
