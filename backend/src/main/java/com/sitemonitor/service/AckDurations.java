package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.util.Msg;

import java.util.List;

/**
 * Süreli alarm onayı (2026-11-19, kullanıcı kararı: "Süresiz + hazır süreler") — onay penceresinin sunduğu süreler TEK
 * kaynak. Ön yüz aynı listeyi gösterir ({@code alerts/ackDurationModel.js}, kapı testi Java listesini okur). Boş / 0 =
 * süresiz (bugünkü davranış aynen).
 */
public final class AckDurations {

    private AckDurations() { }

    /** İstek alanı adı (tekli ve toplu onay gövdesi). */
    public static final String FIELD = "duration_minutes";

    /** İzin verilen süreler (dakika): 1 sa, 4 sa, 8 sa, 1 gün, 3 gün. Sıra = arayüz sırası. */
    public static final List<Integer> ALLOWED_MINUTES = List.of(60, 240, 480, 1440, 4320);

    /**
     * İstek değerini çözer: null / boş / 0 → null (süresiz); listedeki tamsayı → değer; başka her şey 400
     * {@code VALIDATION_FAILED} alanı {@value #FIELD}.
     */
    public static Integer parse(Object raw) {
        if (raw == null) return null;
        String text = raw.toString().trim();
        if (text.isEmpty()) return null;
        Integer v = null;
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) <= Integer.MAX_VALUE) {
            v = n.intValue();
        } else if (text.length() <= 6 && text.chars().allMatch(Character::isDigit)) {
            v = Integer.parseInt(text);
        }
        if (v != null && v == 0) return null;
        if (v == null || !ALLOWED_MINUTES.contains(v)) {
            throw new GlobalExceptionHandler.FieldValidationException(FIELD, Msg.t(
                    "Onay süresi geçersiz — süresiz ya da 1 saat, 4 saat, 8 saat, 1 gün veya 3 gün seçin.",
                    "Invalid acknowledgement duration — choose no limit or 1 hour, 4 hours, 8 hours, 1 day or 3 days."));
        }
        return v;
    }
}
