package com.sitemonitor.service;

import com.sitemonitor.dto.CertificateDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tüm Sertifikalar tablosunun "Güven" hükmü (2026-10-09, kullanıcı isteği: "Güven sütununda 'Kısmen doğrulandı' var ama
 * süzgeçte yalnız 'Herhangi' ve 'Yalnız güvensiz' var — süzgeç sütunla uyuşmuyor").
 *
 * <p>Arayüzün {@code certtable/certTableModel.js#trustOf} kuralının BİREBİR eşi — sütun neyi gösteriyorsa sunucu süzgeci
 * de onu süzer (iki taraf aynı doğruluk tablosuyla sınanır: {@code CertTrustVerdictTest} ↔ {@code certTableModel.test.js}).
 * <ul>
 *   <li><b>bad</b> (Sorun): zincir VALID/UNKNOWN dışında, CA UNTRUSTED ya da iptal REVOKED — hangileri olduğu
 *       {@link #issues} ({@code chain} / {@code untrusted} / {@code revoked}).</li>
 *   <li><b>ok</b> (Tam): üç denetim de sonuçlandı ve sorun yok.</li>
 *   <li><b>partial</b> (Kısmen doğrulandı): sorun yok ama denetimlerin 1–2'si sonuçlanmadı (UNKNOWN / boş).</li>
 *   <li><b>unknown</b> (Bilinmiyor): hiçbir denetim sonuçlanmadı.</li>
 * </ul>
 * "Yalnız güvensiz" süzgeci ({@code filter_insecure}, {@code security_flags}) AYRI bir kavramdır (alan adı uyuşmazlığını
 * da kapsar) ve değişmedi.
 */
public final class CertTrustVerdict {

    private CertTrustVerdict() {}

    /** Süzgeç değerleri: dört hüküm + "Sorun"un üç alt türü. */
    public static final List<String> FILTERS = List.of("ok", "partial", "unknown", "bad", "chain", "untrusted", "revoked");
    private static final Set<String> ISSUE_FILTERS = Set.of("chain", "untrusted", "revoked");

    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }

    /** Sorunlar (arayüzle aynı sırada): chain, untrusted, revoked. */
    public static List<String> issues(CertificateDto c) {
        List<String> out = new ArrayList<>(3);
        if (c == null) return out;
        String chain = up(c.getChainStatus());
        if (!chain.isEmpty() && !"VALID".equals(chain) && !"UNKNOWN".equals(chain)) out.add("chain");
        if ("UNTRUSTED".equals(up(c.getTrustStatus()))) out.add("untrusted");
        if ("REVOKED".equals(up(c.getRevocationStatus()))) out.add("revoked");
        return out;
    }

    /** Hüküm: bad | ok | partial | unknown. */
    public static String tone(CertificateDto c) {
        if (!issues(c).isEmpty()) return "bad";
        int known = 0;
        if (c != null) {
            for (String s : new String[] { c.getChainStatus(), c.getTrustStatus(), c.getRevocationStatus() }) {
                String v = up(s);
                if (!v.isEmpty() && !"UNKNOWN".equals(v)) known++;
            }
        }
        if (known == 0) return "unknown";
        return known == 3 ? "ok" : "partial";
    }

    /** Süzgeç eşleşmesi; boş / tanınmayan değer = süzgeç yok. */
    public static boolean matches(CertificateDto c, String filter) {
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        if (f.isEmpty() || !FILTERS.contains(f)) return true;
        if (ISSUE_FILTERS.contains(f)) return issues(c).contains(f);
        return f.equals(tone(c));
    }

    /** Geçerli bir süzgeç değeri mi (bilinmeyen değer yok sayılır — bozuk bağlantı listeyi boşaltmasın). */
    public static boolean isKnownFilter(String filter) {
        return filter != null && FILTERS.contains(filter.trim().toLowerCase(Locale.ROOT));
    }
}
