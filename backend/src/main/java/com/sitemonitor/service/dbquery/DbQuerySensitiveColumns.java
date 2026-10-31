package com.sitemonitor.service.dbquery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Kişisel / gizli veri taşıması olası kolon adları (r177, Ö-6): alarm mailine giden sonuç satırlarında maskelenmesi önerilir.
 * YALNIZ ad üstünden tahmin (değer okunmaz) — öneri ve uyarı üretir, engellemez.
 *
 * <p>Ad Türkçe harflerden arındırılır (ş → s, ı / İ → i …), küçük harfe çevrilir ve parçalara bölünür (alt çizgi, tire, nokta,
 * boşluk, camelCase). Kısa, çok anlamlı belirteçler ({@code pan}, {@code kart}, {@code tel}, {@code cep} …) yalnız TAM parça
 * olarak eşleşir ({@code company} / {@code cardinality} yanlış eşleşmesin); uzun, ayırt edici kökler ({@code telefon},
 * {@code password}, {@code kimlikno} …) birleşik adın içinde de aranır ({@code musteri_telefonu}, {@code tckimlikno}).
 * Doğrusal; düzenli ifade yok; ad ≤ 128 karakter.
 */
public final class DbQuerySensitiveColumns {

    /** Tam parça eşleşen belirteçler. */
    static final Set<String> TOKENS = Set.of("tc", "tckn", "tcno", "tckno", "kimlik", "iban", "kart", "kartno", "card",
            "cardno", "pan", "cvv", "cvv2", "cvc", "ccv", "telefon", "tel", "phone", "gsm", "cep", "mobile", "msisdn", "email",
            "eposta", "mail", "parola", "password", "passwd", "pwd", "sifre", "token", "secret", "apikey", "maas", "salary",
            "dogum", "birth", "dob", "adres", "address", "ssn", "vkn", "pin");

    /** Birleşik adın İÇİNDE aranan uzun kökler (≥ 5 karakter, ayırt edici). */
    static final List<String> STEMS = List.of("tckimlik", "kimlikno", "telefon", "phone", "email", "eposta", "parola",
            "password", "passwd", "sifre", "secret", "salary", "apikey", "creditcard", "kredikart", "kartnumara",
            "cardnumber", "iban", "birthdate", "dogumtarih", "msisdn", "token", "tcknumara");

    static final int MAX_NAME = 128;

    private DbQuerySensitiveColumns() {}

    /** Kolon adı hassas veri taşıyor olabilir mi (büyük-küçük harf ve alt çizgi duyarsız). */
    public static boolean matches(String name) {
        if (name == null) return false;
        String raw = name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
        List<String> parts = parts(raw);
        StringBuilder joined = new StringBuilder();
        for (String p : parts) {
            if (TOKENS.contains(p)) return true;
            joined.append(p);
        }
        String all = joined.toString();
        for (String s : STEMS) if (all.contains(s)) return true;
        return false;
    }

    /** Verilen kolonlardan hassas olabilenler (sıra korunur, tekil). */
    public static List<String> filter(Collection<String> names) {
        Set<String> out = new LinkedHashSet<>();
        if (names != null) for (String n : names) if (n != null && matches(n)) out.add(n);
        return new ArrayList<>(out);
    }

    /** Türkçe katlama + küçük harf + parça bölme (ayırıcılar ve camelCase sınırı). */
    static List<String> parts(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean alnum = Character.isLetterOrDigit(c);
            if (!alnum) {
                flush(cur, out);
                prev = 0;
                continue;
            }
            // camelCase: küçük harften / rakamdan büyük harfe geçişte yeni parça
            if (Character.isUpperCase(c) && prev != 0 && (Character.isLowerCase(prev) || Character.isDigit(prev))) flush(cur, out);
            cur.append(fold(c));
            prev = c;
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static char fold(char c) {
        return switch (c) {
            case 'ş', 'Ş' -> 's';
            case 'ı', 'İ', 'I' -> 'i';
            case 'ğ', 'Ğ' -> 'g';
            case 'ü', 'Ü' -> 'u';
            case 'ö', 'Ö' -> 'o';
            case 'ç', 'Ç' -> 'c';
            default -> Character.toLowerCase(c);
        };
    }
}
