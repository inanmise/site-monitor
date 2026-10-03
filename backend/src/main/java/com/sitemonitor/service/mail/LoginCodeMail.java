package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Row;
import com.sitemonitor.service.mail.MailTokens.Tone;

/**
 * Kodla giriş e-postası (2026-10-02, kullanıcı isteği) — YALNIZ {@link MailDoc}/{@link MailKit} ile (Outlook-güvenli, mobil
 * duyarlı, tek CID logo, sol renk şeridi yok; {@code EmailResponsiveContractTest}).
 *
 * <p>İçerik: büyük tek kullanımlık kod, geçerlilik süresi, istek zamanı (İstanbul), IP ve cihaz özeti, "siz değilseniz"
 * uyarısı. <b>Kod KONUDA ve ön başlıkta (preheader) YOKTUR</b> — kilit ekranı / posta listesi önizlemesi kodu göstermesin.
 * Gövde iki dillidir (Türkçe ana metin + İngilizce özet): giriş sayfasının dili bilinse de e-posta istemcisi farklı
 * olabilir; tek e-posta herkese anlaşılır. Marka e-postada AYRI yazılır ("Site Monitor").
 *
 * <p>Bu e-posta {@code notification_logs}'a YAZILMAZ ve gövdesi hiçbir log'a düşmez (gönderim hunisi yalnız alıcı +
 * konu + boyut loglar).
 */
public final class LoginCodeMail {

    private LoginCodeMail() {}

    public static final String SUBJECT = "[Site Monitor] Giriş kodunuz";

    /**
     * @param displayName kullanıcının görünen adı (boşsa selamlama satırı çizilmez)
     * @param code        6 haneli kod (baştaki sıfırlar korunur)
     * @param ttlSeconds  geçerlilik (sn)
     * @param requestedAt istek zamanı, İstanbul saatiyle biçimlenmiş ("02.10.2026 14:05:09")
     * @param ip          isteği yapan IP (boş → satır yok)
     * @param device      cihaz özeti ("Chrome · Windows"; boş → satır yok)
     */
    public record Info(String displayName, String code, int ttlSeconds, String requestedAt, String ip, String device) { }

    public static String subject() {
        return SUBJECT;
    }

    public static MailDoc.Mail build(Info i) {
        String code = i.code() == null ? "" : i.code();
        int ttl = i.ttlSeconds();
        MailDoc d = MailDoc.create(SUBJECT)
                .preheader("Site Monitor oturum açma isteğiniz için tek kullanımlık kod — " + ttl + " saniye geçerli.")
                .kicker("Hesap Güvenliği")
                .badges(Badge.tint("GİRİŞ KODU", Tone.INFO), Badge.outline("Site Monitor"))
                .title("Giriş kodunuz", "Site Monitor'e giriş için tek kullanımlık kodunuz aşağıdadır.");
        if (!blank(i.displayName())) {
            d.paragraphHtml("Sayın <strong>" + MailKit.esc(i.displayName()) + "</strong>,", "Sayın " + i.displayName() + ",");
        }
        // Büyük, eş aralıklı kod — elle yazarken karakterler karışmasın; kopyalanınca boşluk gelmesin diye tek parça.
        String codeHtml = "<p style=\"margin:0;font-family:" + MailTokens.MONO + ";font-size:36px;line-height:44px;font-weight:700;"
                + "letter-spacing:6px;color:" + MailTokens.FG + ";mso-line-height-rule:exactly\">" + MailKit.esc(code) + "</p>"
                + "<p style=\"margin:8px 0 0;font-size:13px;line-height:20px;color:" + MailTokens.MUTED + "\">"
                + ttl + " saniye geçerli · yalnız bir kez kullanılabilir</p>";
        d.card("Tek kullanımlık kod", null, codeHtml, code + "\n" + ttl + " saniye geçerli · yalnız bir kez kullanılabilir");
        d.keyValue("İstek ayrıntıları", MailDoc.rows(
                blank(i.requestedAt()) ? null : Row.of("İstek zamanı", i.requestedAt() + " (İstanbul saati)"),
                blank(i.ip()) ? null : Row.of("IP adresi", i.ip()),
                blank(i.device()) ? null : Row.of("Cihaz", i.device())));
        d.alert(Tone.WARNING, "Bu isteği siz yapmadıysanız",
                "Bu e-postayı dikkate almayın ve kodu kimseyle paylaşmayın. Kod süresi dolunca kendiliğinden geçersiz olur; "
                        + "tekrarlayan istekler görürseniz sistem yöneticinize haber verin.");
        d.note("Site Monitor yöneticileri ve destek ekibi bu kodu sizden hiçbir zaman istemez.");
        d.heading("English", null);
        d.paragraph("Your one-time Site Monitor sign-in code is " + code + ". It is valid for " + ttl
                + " seconds and can be used once. If you did not request it, ignore this e-mail and do not share the code.");
        String why = "Bu e-posta, Site Monitor giriş ekranında hesabınız için e-posta ile kod istendiği için gönderildi.";
        d.footerWhyCustom(why, why);
        d.footerMeta("Hesap Güvenliği", "Site Monitor");
        return d.build();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
