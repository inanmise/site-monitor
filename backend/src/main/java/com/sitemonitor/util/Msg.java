package com.sitemonitor.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * İstek-dilinde kullanıcıya dönen kısa sunucu mesajları (tost/hata metni).
 *
 * <p>2026-09-10'a kadar ayar denetleyicileri ve doğrulama servisleri mesajı Türkçe sabit yazıyordu;
 * İngilizce arayüzde tost Türkçe çıkıyordu (QA ISSUE-001, sınıf-düzeyi). Tam bir MessageSource
 * altyapısı kurmak yerine iki dil çift olarak yerinde tutulur: {@code Msg.t("Kaydedildi", "Saved")}.
 * Dil, SPA'nın her isteğe eklediği {@code X-Lang} başlığından (tr|en) okunur; başlık yoksa
 * {@code Accept-Language}; o da yoksa <b>tr</b> — eski istemciler bugünkü davranışı görür.
 *
 * <p>İstek bağlamı olmayan yerlerde (zamanlayıcı, boot) {@link RequestContextHolder} boştur → tr.
 * Denetim kayıtları (AuditDetail) ve log satırları bu yardımcıyı KULLANMAZ — onlar sistem dilidir.
 */
public final class Msg {

    public static final String HEADER = "X-Lang";

    private Msg() {}

    /** "en" ya da "tr" (varsayılan). */
    public static String lang() {
        try {
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            if (ra instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                String x = req.getHeader(HEADER);
                if (x != null && !x.isBlank()) return norm(x);
                String al = req.getHeader("Accept-Language");
                if (al != null && !al.isBlank()) return norm(al);
            }
        } catch (Exception ignored) {
            // bağlam yok / erişilemez → varsayılan
        }
        return "tr";
    }

    public static boolean isEn() {
        return "en".equals(lang());
    }

    /** İstek dili İngilizceyse {@code en}, aksi halde {@code tr}. */
    public static String t(String tr, String en) {
        return isEn() ? en : tr;
    }

    private static String norm(String v) {
        String s = v.trim().toLowerCase();
        return s.startsWith("en") ? "en" : "tr";
    }
}
