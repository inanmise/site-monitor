package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Alarm iletimi damgası (2026-11-05) — {@code alert_events.forward_emails}: açılış / yükselme / hatırlatma / elle gönderim /
 * sessiz saat özeti / fırtına postasını İLETİMLE GERÇEKTEN alan adresler, alıcı takım başına. Biçim JSON nesnesi
 * {@code {"<alıcıTakımId>":["a@x","b@y"]}}; yazım hep BİRLEŞTİREREK (eski + yeni, büyük/küçük harf duyarsız tekil) ve
 * {@value #MAX_CHARS} karakteri aşmadan: damgada ZATEN olan adresler korunur, sığmayan YENİ adres düşer (WARN — çözüm postası
 * ona gitmez). Çözüm postası yalnız bu adreslere gider — devirden / iletim kaldırılmasından sonra açılışı hiç görmemiş biri
 * "çözüldü"yü almaz, açılışı alan da çözümü kaçırmaz.
 *
 * <p>Yazım KOŞULLUDUR (2026-11-06, inceleme): iki gönderim (ör. anlık posta + sessiz saat özeti, iki pod) aynı alarmın
 * damgasını aynı anda birleştirirse "oku → birleştir → yaz" birinin adreslerini siliyordu. Artık yazım okunan değere bağlı
 * ({@code WHERE forward_emails = :eski} / {@code IS NULL}); tutmazsa yeniden okunup birleştirilir — en çok
 * {@value #MAX_ATTEMPTS} deneme.
 *
 * <p>Okuma bozuk / boş JSON'da boş harita döner (iletim yok sayılır — fazladan posta yerine sessizlik değil, yalnız damga yok
 * demektir). Yazım hatası gönderimi ASLA bozmaz (yutulur, WARN).
 */
@Slf4j
public final class ForwardEmailStamp {

    /** Sütun tavanı — JSON metninin en çok uzunluğu. */
    public static final int MAX_CHARS = 4000;
    /** Tek adres tavanı (RFC 5321 yol uzunluğu) — daha uzunu damgaya girmez. */
    private static final int MAX_ADDRESS = 254;
    /** Koşullu yazımın en çok deneme sayısı (her denemede yeniden okunur + birleştirilir). */
    static final int MAX_ATTEMPTS = 3;
    /** Tek birleştirmede denenecek en çok yeni adres (sınırlı döngü). */
    private static final int MAX_NEW_ADDRESSES = 2_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ForwardEmailStamp() { }

    /** Damga metni → alıcı takım → adresler (sıra korunur). Bozuk / boş → boş harita. */
    public static Map<Long, List<String>> parse(String json) {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        if (json == null || json.isBlank() || json.length() > MAX_CHARS * 2) return out;
        try {
            Object raw = JSON.readValue(json, Object.class);
            if (!(raw instanceof Map<?, ?> m)) return out;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Long team;
                try {
                    team = Long.valueOf(String.valueOf(e.getKey()).trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (!(e.getValue() instanceof Collection<?> c)) continue;
                List<String> list = out.computeIfAbsent(team, k -> new ArrayList<>());
                Set<String> seen = new HashSet<>();
                for (String s : list) seen.add(s.toLowerCase(Locale.ROOT));
                for (Object o : c) {
                    String v = o == null ? "" : String.valueOf(o).trim();
                    if (!v.isEmpty() && v.length() <= MAX_ADDRESS && seen.add(v.toLowerCase(Locale.ROOT))) list.add(v);
                }
            }
        } catch (Exception ex) {
            log.debug("İletim damgası okunamadı (yok sayılır): {}", ex.toString());
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** Alarmın iletim damgası (varlıktaki salt okunur sütun). */
    public static Map<Long, List<String>> of(AlertEvent e) {
        return parse(e == null ? null : e.getForwardEmails());
    }

    /**
     * Eski damga + yeni adresler → yeni JSON metni (alıcı takım artan, adres sırası korunur, tekil). Damgada ZATEN olan adresler
     * hep korunur; tavana sığmayan YENİ adres düşer ve {@code dropped} sayacına eklenir (2026-11-06: eskiden sondan kırpılıyordu
     * — açılışı almış bir alıcı takımın adresleri, sonradan eklenen bir alıcı yüzünden damgadan düşebiliyordu). Eski damga tek
     * başına tavanı aşıyorsa (elle yazılmış) yeni adres eklenmez, eski metin aynen döner. Değişiklik yoksa eskisiyle aynı içerik.
     */
    static String merge(String existingJson, Map<Long, List<String>> add, int[] dropped) {
        if (existingJson != null && existingJson.length() > MAX_CHARS) {   // elle yazılmış uzun damga: dokunulmaz
            if (add != null) for (List<String> l : add.values()) if (l != null) count(dropped, l.size());
            return existingJson;
        }
        Map<Long, List<String>> merged = new TreeMap<>(parse(existingJson));
        Map<Long, Set<String>> keys = new LinkedHashMap<>();
        merged.forEach((t, l) -> {
            Set<String> s = new HashSet<>();
            for (String a : l) s.add(a.toLowerCase(Locale.ROOT));
            keys.put(t, s);
        });
        String text = write(asText(merged));
        if (text.length() > MAX_CHARS) {
            if (add != null) for (List<String> l : add.values()) if (l != null) count(dropped, l.size());
            return existingJson;
        }
        if (add == null) return text;
        int tried = 0;
        for (Map.Entry<Long, List<String>> e : new TreeMap<>(withoutNullKeys(add)).entrySet()) {
            if (e.getValue() == null) continue;
            for (String a : e.getValue()) {
                String v = a == null ? "" : a.trim();
                if (v.isEmpty() || v.length() > MAX_ADDRESS) continue;
                Set<String> s = keys.computeIfAbsent(e.getKey(), k -> new HashSet<>());
                if (s.contains(v.toLowerCase(Locale.ROOT))) continue;
                if (++tried > MAX_NEW_ADDRESSES) { count(dropped, 1); continue; }
                List<String> list = merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                list.add(v);
                String candidate = write(asText(merged));
                if (candidate.length() > MAX_CHARS) {   // sığmadı → bu YENİ adres düşer, mevcutlar korunur
                    list.remove(list.size() - 1);
                    if (list.isEmpty()) merged.remove(e.getKey());
                    count(dropped, 1);
                    continue;
                }
                s.add(v.toLowerCase(Locale.ROOT));
                text = candidate;
            }
        }
        return text;
    }

    private static Map<String, List<String>> asText(Map<Long, List<String>> m) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        m.forEach((t, l) -> { if (!l.isEmpty()) out.put(String.valueOf(t), new ArrayList<>(l)); });
        return out;
    }

    private static Map<Long, List<String>> withoutNullKeys(Map<Long, List<String>> m) {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        m.forEach((k, v) -> { if (k != null) out.put(k, v); });
        return out;
    }

    private static void count(int[] dropped, int n) {
        if (dropped != null && dropped.length > 0) dropped[0] += n;
    }

    private static String write(Map<String, List<String>> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Damgaya ekler: hedefli okuma + KOŞULLU hedefli yazma (varlık kaydı değil) — okunan değer bu arada değiştiyse (başka bir
     * gönderim yazdı) yeniden okunup birleştirilir, en çok {@value #MAX_ATTEMPTS} deneme. {@code delivered} boş / depo yoksa
     * hiçbir şey. Hata yutulur — gönderim bozulmaz (çözüm postası bu adreslere gitmeyebilir; WARN).
     */
    public static void stamp(AlertEventRepository repo, Long alertEventId, Map<Long, List<String>> delivered) {
        if (repo == null || alertEventId == null || delivered == null || delivered.isEmpty()) return;
        boolean any = false;
        for (List<String> l : delivered.values()) if (l != null && !l.isEmpty()) { any = true; break; }
        if (!any) return;
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                String prev = repo.findForwardEmails(alertEventId);
                int[] dropped = {0};
                String next = merge(prev, delivered, dropped);
                if (dropped[0] > 0) {
                    log.warn("İletim damgası {} karakteri aştı — {} yeni adres damgaya yazılmadı, mevcutlar korundu (olay {}; "
                            + "çözüm postası onlara gitmeyecek)", MAX_CHARS, dropped[0], alertEventId);
                }
                if (next == null || next.equals(prev)) return;
                int n = prev == null ? repo.setForwardEmailsIfNull(alertEventId, next)
                        : repo.setForwardEmailsIfUnchanged(alertEventId, next, prev);
                if (n > 0) return;
            }
            log.warn("İletim alıcıları alarma yazılamadı: damga {} denemede de eşzamanlı değişti ya da alarm yok (olay {}; çözüm "
                    + "postası bu adreslere gitmeyebilir)", MAX_ATTEMPTS, alertEventId);
        } catch (Exception e) {
            log.warn("İletim alıcıları alarma yazılamadı (olay {}; çözüm postası iletim alıcılarına gitmeyebilir): {}",
                    alertEventId, e.toString());
        }
    }
}
