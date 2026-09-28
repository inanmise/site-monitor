package com.sitemonitor.controller;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kimlik izi maskesi — giriş IP'si, coğrafi konum, kuruluş, ters DNS ve tarayıcı parmak izi yalnız GLOBAL admin ve
 * AUDIT'e ({@link SystemController#identityVisible}) ve kişinin KENDİ satırında döner.
 *
 * <p><b>Neden yapı-bağımsız (2026-09-28c, B1).</b> Önceki maske yalnız dört üst düzey LİSTEYİ (active_users,
 * login_status, events, anomalies) geziyordu. Oysa Kullanıcı / Oturum payload'ında kimlik izi başka yerlerde de
 * duruyordu: {@code top_sources} (IP, ters DNS, kuruluş, o IP'nin kullanıcıları), {@code details.logins/failed/
 * anomalies} (IP, şehir, kuruluş, tarayıcı), {@code heatmaps[].cells} (IP, şehir) ve {@code anomalies} bir Map
 * ({@code {counts, recent:[…]}}) olduğu için {@code instanceof List} koşulu tutmuyor, maske sessizce no-op
 * kalıyordu. Kural artık ALAN ADINA bağlı: yük özyinelemeli gezilir, hangi derinlikte olursa olsun {@link #FIELDS}
 * anahtarı düşer (null değil — anahtar hiç yok; arayüz "gizli" durumunu anahtarın yokluğundan ve
 * {@value #FLAG} bayrağından okur). Yeni bir yüzey aynı alan adlarını kullandıkça otomatik kapsanır; yeni bir kimlik
 * alanı ADI eklenirse {@code IdentityMaskGateTest} (payload'ı JSON'a çevirip bilinen IP/UA değerlerini arar) kırılır.
 *
 * <p><b>Önbellek.</b> Özet 60 sn paylaşılan önbellekte ({@code user-activity-overview}) — maske ASLA yerinde
 * değiştirmez: yazma-anında-kopya (copy-on-write) ile yalnız değişen düğümler kopyalanır, değişmeyen alt ağaçlar
 * (ısı haritası matrisi, sayaçlar) aynı nesne olarak paylaşılır (tek pod, 100 eşzamanlı kullanıcı — istek başına
 * tüm ağacı kopyalamak gereksiz yük).
 */
public final class IdentityMask {

    private IdentityMask() { }

    /**
     * Kimlik izi taşıyan alan adları — TEK liste. {@code last_login_ip / prev_login_ip / last_failed_ip} giriş damgası
     * alanlarıdır (UserActivityService.putLoginStamp); {@code ip_address / ua_raw / ua_summary} kardeş yüzeylerin
     * (denetim / cihaz geçmişi) adlarıdır — bu yüke taşınırsa da yakalansın diye listede.
     */
    public static final Set<String> FIELDS = Set.of(
            "ip", "ip_address", "country", "city", "org", "reverse_dns",
            "user_agent", "ua_raw", "ua_summary",
            "last_login_ip", "prev_login_ip", "last_failed_ip",
            // Giriş sorunu bildirimleri camelCase Map anahtarlarıyla döner (LoginIssueController) — aynı iz.
            "ipAddress", "userAgent");

    /**
     * Kimlik izinin KENDİSİ olan listeler: satırları IP anahtarlıdır (IP düşünce satır anlamsızlaşır, arkasındaki
     * kullanıcı adları ise "kim kiminle aynı ağda" korelasyonunu verir) → global olmayan görüntüleyiciye hiç gitmez.
     */
    public static final Set<String> TRACE_LISTS = Set.of("top_sources");

    /**
     * Değeri JSON METNİ olan alanlar — iz metnin İÇİNDE durur (giriş sorunu bildiriminin otomatik bağlamı
     * {@code {"ip":…,"userAgent":…}} taşır). Metin ayrıştırılır, aynı kural uygulanır, geri yazılır; ayrıştırılamazsa
     * alan düşer (içeriği bilinmeyen metin iz taşıyabilir).
     */
    public static final Set<String> JSON_TEXT_FIELDS = Set.of("autoContextJson");

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    /**
     * Yanıt bayrağı: true → kimlik izi bu görüntüleyici için düşürüldü (arayüz "yetki gerekli" durumunu çizer). AYNI
     * anahtar alanı düşürülen her SATIRA da yazılır (2026-09-28c ek): geçmiş ekranları bayrağı üst düzeyden taşımadan
     * satırdan okur — "IP yok" (kayıt boş) ile "IP gizli" (yetki) ayrımı satır başına kesin.
     */
    public static final String FLAG = "identity_masked";

    /**
     * Kimlik izini görebilen oturum: GLOBAL admin ya da AUDIT (kapsamlı müdür — rol ADMIN ama takım-kapsamlı — DEĞİL).
     * Tek kaynak: Sistem Sağlığı ve bütün değişiklik geçmişi uçları bu kuralı buradan okur.
     */
    public static boolean visibleTo(jakarta.servlet.http.HttpSession session) {
        return session != null && (SessionScope.isGlobalAdmin(session) || "AUDIT".equals(session.getAttribute("systemRole")));
    }

    /** Oturuma göre maske: {@link #visibleTo} değilse izler düşer; oturum sahibinin kendi satırları korunur. */
    public static Map<String, Object> forSession(Map<String, Object> payload, jakarta.servlet.http.HttpSession session) {
        Object self = session == null ? null : session.getAttribute("username");
        return apply(payload, visibleTo(session), self == null ? null : String.valueOf(self));
    }

    /**
     * Payload'ın görüntüleyiciye göre kopyası. {@code visible} ise içerik aynen, yalnız {@value #FLAG}=false eklenir;
     * değilse {@link #FIELDS} her derinlikte, {@link #TRACE_LISTS} tümüyle düşer — {@code selfUsername}'in kendi
     * satırı ({@code username} ya da {@code actor} eşleşmesi, büyük/küçük harf duyarsız) hariç.
     */
    public static Map<String, Object> apply(Map<String, Object> payload, boolean visible, String selfUsername) {
        if (payload == null) return null;
        Map<String, Object> out = new LinkedHashMap<>(visible ? payload : asMap(strip(payload, selfUsername)));
        out.put(FLAG, !visible);
        return out;
    }

    /** Özyinelemeli yazma-anında-kopya: değişiklik yoksa AYNI nesne döner. */
    static Object strip(Object node, String self) {
        if (node instanceof Map<?, ?> m) return stripMap(m, self);
        if (node instanceof List<?> l) return stripList(l, self);
        return node;
    }

    private static Object stripMap(Map<?, ?> m, String self) {
        boolean own = self != null && (same(self, m.get("username")) || same(self, m.get("actor")));
        Map<String, Object> out = null;
        boolean traceDropped = false;
        int index = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            boolean field = !own && FIELDS.contains(key);
            boolean drop = TRACE_LISTS.contains(key) || field;
            traceDropped |= field;
            Object next = drop ? value : strip(value, self);
            if (!drop && !own && value instanceof String text && JSON_TEXT_FIELDS.contains(key)) {
                String cleaned = stripJsonText(text);
                if (!text.equals(cleaned)) { next = cleaned; traceDropped = true; }
                if (cleaned == null) drop = true;
            }
            if (out == null && (drop || next != value)) out = prefix(m, index);
            if (out != null && !drop) out.put(key, next);
            index++;
        }
        if (traceDropped) out.put(FLAG, true);   // satır işareti: bu satırın izi gizlendi
        return out == null ? m : out;
    }

    private static Object stripList(List<?> l, String self) {
        List<Object> out = null;
        int index = 0;
        for (Object value : l) {
            Object next = strip(value, self);
            if (out == null && next != value) {
                out = new ArrayList<>(l.size());
                Iterator<?> it = l.iterator();
                for (int i = 0; i < index; i++) out.add(it.next());
            }
            if (out != null) out.add(next);
            index++;
        }
        return out == null ? l : out;
    }

    /** JSON metnindeki iz alanlarını düşürür; boş metin aynen, ayrıştırılamayan metin {@code null} (alan düşer). */
    static String stripJsonText(String text) {
        if (text.isBlank()) return text;
        try {
            Object parsed = JSON.readValue(text, Object.class);
            Object cleaned = strip(parsed, null);
            return cleaned == parsed ? text : JSON.writeValueAsString(cleaned);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** İlk {@code n} girdinin (değişmemiş) kopyası — sıra korunur. */
    private static Map<String, Object> prefix(Map<?, ?> m, int n) {
        Map<String, Object> out = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (i++ >= n) break;
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static boolean same(String self, Object name) {
        return name != null && self.equalsIgnoreCase(String.valueOf(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
