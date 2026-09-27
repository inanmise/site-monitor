package com.sitemonitor.service;

import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.MailKit;

import java.util.List;

/**
 * Alarm e-postalarının altındaki "olay aksiyonu" butonları — olayın detayına git / olaya yorum yaz.
 *
 * <p>Bilinçli olarak <b>static</b> (Spring bean DEĞİL): şablon sınıflarının kurucuları değişmesin,
 * mevcut testler ({@code new EmailNotificationService(...)}) kırılmasın.
 *
 * <p>Buradaki "olay" = {@code AlertEvent} (makine üretimi alarm), uygulamada {@code ?tab=incidents}
 * ekranı. Ayrı ve ilgisiz olan manuel SRE kaydı ({@code IncidentRecord} / {@code incident-history})
 * ile karıştırılmamalı.
 *
 * <p>Olay kimliği yoksa hiçbir şey eklenmez (eski bağlamlar, fırtına postaları, domain'siz basit
 * alarmlar). Görünüm e-posta yeniden tasarımında (2026-09-26) {@link MailKit} buton grubuna
 * taşındı: iki outline buton masaüstünde yan yana, telefonda alt alta ve tam genişlik.
 */
final class MailCta {

    private MailCta() { }

    static final String LABEL_DETAILS = "Olay detayını görüntüle";
    static final String LABEL_COMMENT = "Olaya yorum yap";
    static final String CAPTION = "BU OLAY İÇİN HIZLI AKSİYONLAR";

    /** {@code <base>/?tab=incidents&incident=<id>} — kimlik yoksa "". */
    static String incidentDetailsUrl(String baseUrl, Object alertEventId) {
        String id = normId(alertEventId);
        if (id == null || baseUrl == null || baseUrl.isBlank()) return "";
        return trimBase(baseUrl) + "/?tab=incidents&incident=" + id;
    }

    /** Detay URL'i + {@code &action=comment} — açılışta yorum kutusu açılır. */
    static String incidentCommentUrl(String baseUrl, Object alertEventId) {
        String url = incidentDetailsUrl(baseUrl, alertEventId);
        return url.isEmpty() ? "" : url + "&action=comment";
    }

    /** İki ikincil (outline) buton — kimlik yoksa boş liste. */
    static List<MailKit.Btn> incidentButtons(String baseUrl, Object alertEventId) {
        String details = incidentDetailsUrl(baseUrl, alertEventId);
        if (details.isEmpty()) return List.of();
        return List.of(new MailKit.Btn(details, LABEL_DETAILS, MailKit.Variant.OUTLINE),
                       new MailKit.Btn(incidentCommentUrl(baseUrl, alertEventId), LABEL_COMMENT, MailKit.Variant.OUTLINE));
    }

    /** Belgeye olay aksiyon grubunu ekler (HTML + düz metin birlikte); kimlik yoksa dokunmaz. */
    static void appendIncidentActions(MailDoc d, String baseUrl, Object alertEventId) {
        List<MailKit.Btn> btns = incidentButtons(baseUrl, alertEventId);
        if (!btns.isEmpty()) d.buttons(CAPTION, btns);
    }

    /** Düz metin parite satırları — kimlik yoksa "". */
    static String incidentActionText(String baseUrl, Object alertEventId) {
        String details = incidentDetailsUrl(baseUrl, alertEventId);
        if (details.isEmpty()) return "";
        return "\nOlay detayı: " + details
             + "\nYorum yap:   " + incidentCommentUrl(baseUrl, alertEventId) + "\n";
    }

    /** Sondaki bölü işaretlerini at (ayar "https://x/" gibi bittiğinde "//?tab=" oluşmasın). */
    private static String trimBase(String base) {
        return base.replaceAll("/+$", "");
    }

    /** Yalnız pozitif tam sayı kabul: ctx'ten gelen "null"/boş/çöp değer link üretmesin. */
    private static String normId(Object id) {
        if (id == null) return null;
        String s = String.valueOf(id).trim();
        return s.matches("\\d+") && !s.equals("0") ? s : null;
    }
}
