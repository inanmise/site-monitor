package com.sitemonitor.service;

import java.util.Arrays;

/**
 * Alarm onaylama/çözme gerekçesinin TEK doğrulama tanımı.
 *
 * <p><b>Neden sunucuda.</b> Kural yalnız arayüzde dursaydı kozmetik kalırdı: API'ye doğrudan
 * boş notla istek atmak mümkün olur ve "her manuel onayın bir gerekçesi vardır" garantisi
 * çökerdi. Arayüz aynı kuralı ayrıca uygular — ama oradaki amaç anında geri bildirim, burada
 * ise garantinin kendisi.
 *
 * <p><b>Kural.</b> Kırpıldıktan sonra en az {@link #MIN_WORDS} kelime, her kelime en az
 * {@link #MIN_WORD_LEN} karakter, toplam en az {@link #MIN_CHARS} karakter. Bu üçlü, "a b c" ve
 * "ok ok ok" gibi zorunluluğu geçiştiren girdileri eler.
 *
 * <p><b>Sınır — bilerek.</b> "aaa bbb ccc" bu kuralı geçer. Bir metin kuralıyla anlamlılık
 * zorlanamaz; amaç tembel yolu zorlaştırıp dürüst kullanıcıyı yönlendirmek. Gerçek caydırıcılık
 * notun denetim günlüğüne, CSV'ye ve haftalık rapora adla birlikte düşmesinden gelir.
 */
public final class AlertActionNote {

    private AlertActionNote() {}

    public static final int MIN_WORDS = 3;
    public static final int MIN_WORD_LEN = 2;
    public static final int MIN_CHARS = 10;

    /** Kuralın sağlanıp sağlanmadığı. null/boş → false. */
    public static boolean isValid(String note) {
        if (note == null) return false;
        String trimmed = note.trim();
        if (trimmed.length() < MIN_CHARS) return false;
        return Arrays.stream(trimmed.split("\\s+"))
                .filter(w -> w.length() >= MIN_WORD_LEN)
                .count() >= MIN_WORDS;
    }

    /**
     * Doğrular ve kırpılmış hâlini döner; geçersizse {@link IllegalArgumentException}.
     * Çağıran uçlar bunu 400'e çeviriyor (mevcut hata işleyicisiyle).
     */
    public static String require(String note) {
        if (!isValid(note)) {
            throw new IllegalArgumentException(
                    "Gerekçe notu zorunlu: en az " + MIN_WORDS + " kelime (her biri en az "
                    + MIN_WORD_LEN + " karakter) ve toplam en az " + MIN_CHARS + " karakter.");
        }
        return note.trim();
    }
}
