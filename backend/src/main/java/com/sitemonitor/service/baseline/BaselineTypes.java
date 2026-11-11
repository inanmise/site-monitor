package com.sitemonitor.service.baseline;

import java.util.List;
import java.util.Set;

/**
 * Dinamik taban çizgisinin (yanıt-süresi anomalisi) desteklediği izleme türleri — "yavaş" (SLOW) alarmı OLAN türler.
 * Sayfa bütünlüğü (page) bir SLOW alarmına sahip değildir, bu yüzden listede yoktur. Adlar KALICI; her türün SLOW builder'ı
 * bu anahtarla eşleşir ({@code CheckJobTypes} / {@code ManualEvalInbox.TYPE_*} ile aynı küçük harf anahtar).
 */
public final class BaselineTypes {

    public static final String HTTP = "http", KEYWORD = "keyword", PING = "ping", PORT = "port",
            PAGESPEED = "pagespeed", SCRIPTED = "scripted", DNS = "dns";

    /** Desteklenen türler (kanonik sıra). */
    public static final List<String> ALL = List.of(HTTP, KEYWORD, PING, PORT, PAGESPEED, SCRIPTED, DNS);

    private static final Set<String> SET = Set.copyOf(ALL);

    private BaselineTypes() {}

    public static boolean supported(String type) {
        return type != null && SET.contains(type);
    }
}
