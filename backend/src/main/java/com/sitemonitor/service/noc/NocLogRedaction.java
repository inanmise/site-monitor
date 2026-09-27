package com.sitemonitor.service.noc;

import com.sitemonitor.model.NotificationLog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 7/24 (NOC) posta günlüğü satırlarının MASKELİ biçimi (2026-09-27, yayın öncesi inceleme).
 *
 * <p><b>Neden:</b> NOC satırları alarmın {@code alert_event_id}'sini taşır; alarmı görebilen HER takım üyesi
 * {@code /api/admin/alerts/{id}/notifications} ve SMTP gönderim günlüğü üzerinden satırın gövdesini ve alıcısını
 * okuyabiliyor. Gövdede arama listesinin TELEFONLARI (fırtınada on takıma kadar), alıcıda 7/24 grubunun ADRESLERİ
 * vardı — kapsamlı yöneticinin bile göremediği bilgiler.
 *
 * <p>Katmanlar: (1) yazarken gövde {@code NocMailComposer.redactForLog} ile maskelenir (telefon yalnız son iki hane,
 * {@code tel:} bağlantısı yok) ve alıcı alanına grup ADLARI + adres SAYISI yazılır; (2) okurken ({@link #forViewer},
 * {@link #scrubHtml}, {@link #recipients}) aynı biçim yeniden uygulanır — eski/elle yazılmış bir satır da sızdırmasın.
 */
public final class NocLogRedaction {

    private NocLogRedaction() {}

    /** NOC satırının posta günlüğü kategorisi ({@code notification_logs.recipient_role}). */
    public static final String ROLE = "NOC";

    /** Maskeli telefonun öneki — gerçek bir AD değeri bununla başlamaz; e-posta kurucusu bunu düz metin çizer. */
    public static final String MASK = "••••••••";

    private static final Pattern TEL_ANCHOR = Pattern.compile("(?is)<a\\b[^>]*href=\"tel:[^\"]*\"[^>]*>(.*?)</a>");
    private static final Pattern TEL_TEXT = Pattern.compile("(?i)tel:[+]?[0-9]+");

    public static boolean isNoc(NotificationLog n) {
        return n != null && ROLE.equals(n.getRecipientRole());
    }

    /** "+90 555 000 00 12" → "••••••••12"; 3 haneden az rakam → "•••". Null/boş → null. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        StringBuilder d = new StringBuilder();
        for (char c : phone.toCharArray()) if (c >= '0' && c <= '9') d.append(c);
        if (d.length() < 3) return "•••";
        return MASK + d.substring(d.length() - 2);
    }

    public static boolean isMasked(String phone) {
        return phone != null && phone.startsWith("•");
    }

    /** Gövdedeki {@code tel:} bağlantılarını maskeli düz metne çevirir; kalan {@code tel:} izlerini siler. */
    public static String scrubHtml(String html) {
        if (html == null || html.isEmpty()) return html;
        Matcher m = TEL_ANCHOR.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1).replaceAll("<[^>]*>", "");
            String masked = maskPhone(inner);
            m.appendReplacement(sb, Matcher.quoteReplacement(masked == null ? "" : masked));
        }
        m.appendTail(sb);
        return TEL_TEXT.matcher(sb.toString()).replaceAll("tel:" + MASK);
    }

    /** Alıcı alanı: adres İÇERİYORSA yalnız adres sayısı döner ("3 adres"); grup adları olduğu gibi kalır. */
    public static String recipients(String stored) {
        if (stored == null || stored.indexOf('@') < 0) return stored;
        int n = 0;
        for (char c : stored.toCharArray()) if (c == '@') n++;
        return n + " adres";
    }

    /** Okuma yüzeyi için kopya: NOC satırında gövde ve alıcı maskeli; diğer satırlar AYNEN (aynı nesne). */
    public static NotificationLog forViewer(NotificationLog n) {
        if (!isNoc(n)) return n;
        NotificationLog c = new NotificationLog();
        c.setId(n.getId());
        c.setAlertEventId(n.getAlertEventId());
        c.setSentAt(n.getSentAt());
        c.setRecipientName(n.getRecipientName());
        c.setRecipientEmail(recipients(n.getRecipientEmail()));
        c.setRecipientRole(n.getRecipientRole());
        c.setSubject(n.getSubject());
        c.setMessage(scrubHtml(n.getMessage()));
        c.setEmailStatus(n.getEmailStatus());
        c.setWebhookStatus(n.getWebhookStatus());
        c.setTrigger(n.getTrigger());
        c.setEmailFrom(n.getEmailFrom());
        c.setCc(recipients(n.getCc()));
        return c;
    }
}
