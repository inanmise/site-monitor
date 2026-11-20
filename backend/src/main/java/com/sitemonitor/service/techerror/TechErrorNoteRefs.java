package com.sitemonitor.service.techerror;

import java.util.LinkedHashSet;

/**
 * Kanal notu metnindeki GÖRSEL BAŞVURULARI (2026-11-20). Biçim: düz metin {@code te-note-img:} + 1–18 ASCII rakam;
 * rakam dizisi 19 ya da daha uzunsa başvuru DEĞİLDİR. Markdown'da {@code ![açıklama](te-note-img:<kimlik>)} olarak
 * yazılır, ama burada metnin NERESİNDE geçerse geçsin sayılır — ekranda çizilenin üst kümesi: çizilen bir görselin bağı
 * asla yanlışlıkla düşmez.
 *
 * <p>Tarama DOĞRUSAL ({@code indexOf} döngüsü; düzenli ifade yok): imleç her adımda ilerler, rakam okuması 19 karakterle
 * sınırlıdır. Çağıran metni önce uzunluk sınırından geçirir. İstemcideki eşi {@code noteModel.noteImageRefs}; ikisi
 * aynı doğruluk tablosunu paylaşır ({@code TechErrorNoteRefsTest.REF_TABLE}).
 */
public final class TechErrorNoteRefs {

    private TechErrorNoteRefs() {}

    /** Başvuru öneki (büyük / küçük harf duyarlı). */
    public static final String PREFIX = "te-note-img:";
    /** Kimliğin en çok rakam sayısı (18 rakam {@code long}'a sığar). */
    public static final int MAX_DIGITS = 18;

    /** Metindeki görsel kimlikleri: ilk görülme sırasıyla, tekil. {@code null} / boş metin → boş küme. */
    public static LinkedHashSet<Long> ids(String body) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return out;
        int n = body.length();
        int from = 0;
        while (from < n) {
            int at = body.indexOf(PREFIX, from);
            if (at < 0) break;
            int start = at + PREFIX.length();
            int i = start;
            // En çok MAX_DIGITS + 1 rakam okunur: 19. rakam "çok uzun, başvuru değil" demeye yeter
            while (i < n && i - start <= MAX_DIGITS && isDigit(body.charAt(i))) i++;
            int len = i - start;
            if (len >= 1 && len <= MAX_DIGITS) out.add(Long.parseLong(body, start, i, 10));
            // İmleç her zaman ilerler (start > at ≥ from): okunan rakamlar yeni bir önek başlatamaz
            from = i;
        }
        return out;
    }

    /** Bu kimlik metinde anılıyor mu (silme denetimi). */
    public static boolean references(String body, long id) {
        return ids(body).contains(id);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
