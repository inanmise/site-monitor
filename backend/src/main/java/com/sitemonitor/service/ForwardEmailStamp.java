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
 * {@value #MAX_CHARS} karakteri aşmadan (sığmayan adres düşer, WARN). Çözüm postası yalnız bu adreslere gider — devirden /
 * iletim kaldırılmasından sonra açılışı hiç görmemiş biri "çözüldü"yü almaz, açılışı alan da çözümü kaçırmaz.
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
     * Eski damga + yeni adresler → yeni JSON metni (alıcı takım artan, adres sırası korunur, tekil). Sığmayan adres düşer ve
     * {@code dropped} sayacına eklenir. Değişiklik yoksa eskisiyle aynı içerik döner.
     */
    static String merge(String existingJson, Map<Long, List<String>> add, int[] dropped) {
        Map<Long, List<String>> merged = new TreeMap<>(parse(existingJson));
        Map<Long, Set<String>> keys = new LinkedHashMap<>();
        merged.forEach((t, l) -> {
            Set<String> s = new HashSet<>();
            for (String a : l) s.add(a.toLowerCase(Locale.ROOT));
            keys.put(t, s);
        });
        if (add != null) {
            for (Map.Entry<Long, List<String>> e : add.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                List<String> list = merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                Set<String> s = keys.computeIfAbsent(e.getKey(), k -> new HashSet<>());
                for (String a : e.getValue()) {
                    String v = a == null ? "" : a.trim();
                    if (v.isEmpty() || v.length() > MAX_ADDRESS) continue;
                    if (s.add(v.toLowerCase(Locale.ROOT))) list.add(v);
                }
            }
        }
        // Sığdır: tavanı aşan adresler SONDAN düşer (önce eklenenler — açılışı alanlar — korunur).
        Map<String, List<String>> out = new LinkedHashMap<>();
        merged.forEach((t, l) -> out.put(String.valueOf(t), new ArrayList<>(l)));
        String text = write(out);
        while (text.length() > MAX_CHARS) {
            String lastKey = null;
            for (Map.Entry<String, List<String>> e : out.entrySet()) if (!e.getValue().isEmpty()) lastKey = e.getKey();
            if (lastKey == null) break;
            List<String> l = out.get(lastKey);
            l.remove(l.size() - 1);
            if (l.isEmpty()) out.remove(lastKey);
            if (dropped != null && dropped.length > 0) dropped[0]++;
            text = write(out);
        }
        return text;
    }

    private static String write(Map<String, List<String>> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Damgaya ekler: tek hedefli okuma + tek hedefli yazma (varlık kaydı değil). {@code delivered} boş / depo yoksa hiçbir şey.
     * Hata yutulur — gönderim bozulmaz (çözüm postası bu adreslere gitmeyebilir; WARN).
     */
    public static void stamp(AlertEventRepository repo, Long alertEventId, Map<Long, List<String>> delivered) {
        if (repo == null || alertEventId == null || delivered == null || delivered.isEmpty()) return;
        boolean any = false;
        for (List<String> l : delivered.values()) if (l != null && !l.isEmpty()) { any = true; break; }
        if (!any) return;
        try {
            String prev = repo.findForwardEmails(alertEventId);
            int[] dropped = {0};
            String next = merge(prev, delivered, dropped);
            if (dropped[0] > 0) {
                log.warn("İletim damgası {} karakteri aştı — {} adres damgaya yazılmadı (olay {}; çözüm postası onlara gitmeyecek)",
                        MAX_CHARS, dropped[0], alertEventId);
            }
            if (next.equals(prev)) return;
            repo.setForwardEmails(alertEventId, next);
        } catch (Exception e) {
            log.warn("İletim alıcıları alarma yazılamadı (olay {}; çözüm postası iletim alıcılarına gitmeyebilir): {}",
                    alertEventId, e.toString());
        }
    }
}
