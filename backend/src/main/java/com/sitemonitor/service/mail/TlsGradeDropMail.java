package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Stat;
import com.sitemonitor.service.mail.MailTokens.Tone;
import com.sitemonitor.service.report.executive.ExecFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * TLS yapılandırma notu düşüşü — takım özeti (2026-10-11, kullanıcı isteği: "TLS notu düştüğünde yalnız uygulama içinde
 * görünüyor"). Bir bildirim turunda takımın düşen tüm sertifikaları TEK e-postada: alan adı, eski → yeni not, notu
 * sınırlayan nedenler (Türkçe adlar — Yönetici Özeti ile aynı sözlük {@link ExecFormat#codeLabel}), sertifika penceresine
 * derin bağlantı. Bağlantısız düz metin eşi de üretilir (MailDoc).
 */
public final class TlsGradeDropMail {

    private TlsGradeDropMail() {}

    /** Tek düşüş satırı. {@code reasons} = virgüllü neden kodları. */
    public record Row(String domain, String fromGrade, String toGrade, String reasons, String changedAt) { }

    public static String subject(String teamName, List<Row> rows) {
        int n = rows == null ? 0 : rows.size();
        if (n == 1) {
            Row r = rows.get(0);
            return "[Site Monitor] TLS notu düştü: " + r.domain() + " " + r.fromGrade() + " → " + r.toGrade();
        }
        return "[Site Monitor] TLS yapılandırma notu düştü · " + n + " sertifika" + (blank(teamName) ? "" : " · " + teamName);
    }

    public static MailDoc.Mail build(String teamName, List<Row> rows, String appUrl) {
        List<Row> all = rows == null ? List.of() : rows;
        String worst = worstGrade(all);
        Tone tone = toneOf(worst);
        MailDoc d = MailDoc.create(subject(teamName, all))
                .preheader(all.size() + " sertifikanın TLS yapılandırma notu düştü" + (worst == null ? "" : " · en düşük " + worst))
                .kicker("TLS yapılandırma notu");
        d.badges(Badge.tint(all.size() + " DÜŞÜŞ", tone), Badge.outline(blank(teamName) ? "Site Monitor" : teamName));
        d.title((blank(teamName) ? "" : teamName + " — ") + "TLS notu düştü",
                "Günlük TLS yapılandırma taramasında aşağıdaki sertifikaların notu düştü. Not; protokol sürümleri, şifre "
                        + "takımları, OCSP stapling, HSTS ve anahtar gücünden hesaplanır.");
        d.stats(List.of(Stat.of("Düşen sertifika", String.valueOf(all.size())),
                new Stat("En düşük not", worst == null ? "—" : worst, tone.strong, null)));
        String base = appUrl == null ? "" : appUrl.replaceAll("/+$", "");
        List<List<Cell>> cells = new ArrayList<>();
        for (Row r : all) {
            String url = base.isBlank() ? "" : base + "/?tab=dashboard&domain=" + urlEncode(r.domain()) + "&open=cert";
            cells.add(List.of(
                    url.isBlank() ? Cell.of(r.domain()) : Cell.html(MailKit.link(url, r.domain()), r.domain()),
                    Cell.of(nz(r.fromGrade()) + " → " + nz(r.toGrade()), toneOf(r.toGrade()).strong, true),
                    Cell.of(reasonText(r.reasons()))));
        }
        d.table(List.of(Col.of("Sertifika"), Col.nw("Not"), Col.opt("Neden")), cells);
        d.note("Bu bildirim not her düştüğünde bir kez gider; not yükselince ya da TLS taraması eksik kaldığında (bilgi "
                + "değişimi) gönderilmez. Ayrıntı ve düzeltme önerileri sertifika penceresinin Sağlık sekmesinde.");
        if (!base.isBlank()) d.button(base + "/?tab=all", "Tüm Sertifikalar'ı aç →");
        String why = "Bu e-posta, takımınızın sorumlu olduğu sertifikaların TLS yapılandırma notu düştüğü için gönderildi. "
                + "Bildirimi Ayarlar → Genel → TLS notu bölümünden kapatabilir ya da eşiğini değiştirebilirsiniz.";
        d.footerWhyCustom(why, why);
        d.footerMeta("Site Monitor — TLS notu", "Site Monitor");
        return d.build();
    }

    /** Neden kodlarını okunur Türkçe ada çevirir (bilinmeyen kod olduğu gibi). En çok 4 neden + "ve N daha". */
    static String reasonText(String csv) {
        if (blank(csv)) return "—";
        List<String> out = new ArrayList<>();
        String[] codes = csv.split(",");
        int shown = 0;
        for (String c : codes) {
            String code = c.trim();
            if (code.isEmpty()) continue;
            if (shown == 4) { out.add("ve " + (countNonBlank(codes) - 4) + " neden daha"); break; }
            out.add(ExecFormat.codeLabel("tls_reason", code));
            shown++;
        }
        return out.isEmpty() ? "—" : String.join(" · ", out);
    }

    public static String worstGrade(List<Row> rows) {
        String worst = null;
        for (Row r : rows) {
            if (r.toGrade() == null) continue;
            if (worst == null || rank(r.toGrade()) < rank(worst)) worst = r.toGrade();
        }
        return worst;
    }

    static int rank(String g) {
        return switch (g == null ? "" : g) {
            case "A+" -> 6; case "A" -> 5; case "B" -> 4; case "C" -> 3; case "D" -> 2; case "F" -> 1; default -> 0;
        };
    }

    static Tone toneOf(String grade) {
        int r = rank(grade);
        return r <= 1 ? Tone.DESTRUCTIVE : r <= 3 ? Tone.WARNING : Tone.INFO;
    }

    private static int countNonBlank(String[] a) {
        int n = 0;
        for (String s : a) if (s != null && !s.isBlank()) n++;
        return n;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String nz(String s) { return blank(s) ? "—" : s; }
}
