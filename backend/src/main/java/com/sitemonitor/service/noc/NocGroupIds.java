package com.sitemonitor.service.noc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code noc_group_ids} kolonunun biçimi: virgüllü pozitif kimlikler ("3,7"). Boş/null = varsayılan gruplar.
 * Tek yerde çözülür — on tablo, iki bağlama biçimi (Map gövdesi / varlık) ve CSV aynı kuralı kullanır.
 */
public final class NocGroupIds {

    private NocGroupIds() {}

    /** Ham değer → sıralı, tekil pozitif kimlikler. Çözülemeyen parçalar atlanır (bozuk kayıt 500 üretmez). */
    public static List<Long> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        Set<Long> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                long v = Long.parseLong(p);
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) { /* bozuk parça atlanır */ }
        }
        return new ArrayList<>(out);
    }

    /** Kimlik listesi → kolon değeri; boş liste = null (varsayılan gruplar). */
    public static String format(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) if (id != null && id > 0) seen.add(id);
        if (seen.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Long id : seen) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * İstek gövdesinden gelen ham değer (JSON dizi, sayı, virgüllü metin ya da null) → kimlik listesi.
     * Sayı olmayan öğe 400 üretir: sessizce atlamak, kullanıcının seçtiği grubun hiç kaydedilmemesi olurdu.
     */
    public static List<Long> fromBody(Object raw) {
        if (raw == null) return List.of();
        List<Long> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                if (o == null) continue;
                out.add(toId(o));
            }
        } else if (raw instanceof Number n) {
            out.add(n.longValue());
        } else {
            String s = raw.toString().trim();
            if (s.isEmpty()) return List.of();
            for (String part : s.split("[,;]")) {
                if (!part.isBlank()) out.add(toId(part.trim()));
            }
        }
        return parse(format(out));
    }

    private static Long toId(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Geçersiz 7/24 grup kimliği: " + o);
        }
    }

    /** Listeden bir kimliği çıkarır (grup silinince); sonuç boşsa null (varsayılana düşer). */
    public static String without(String csv, long removedId) {
        List<Long> ids = new ArrayList<>(parse(csv));
        ids.removeIf(id -> id == removedId);
        return format(ids);
    }
}
