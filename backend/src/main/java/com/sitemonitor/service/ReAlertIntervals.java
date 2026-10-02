package com.sitemonitor.service;

import com.sitemonitor.util.Msg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tür bazlı yeniden uyarı sıklığı (2026-10-01, opt-in). Her izleme ailesi ({@link MonitorTypeCatalog#ORDER}: cert,
 * domain, http, ping, port, dns, keyword, page, pagespeed, scripted) için DAKİKA cinsinden isteğe bağlı geçersiz kılma:
 * {@code site.monitor.realert.<aile>-minutes}.
 *
 * <p><b>Ürün güvencesi.</b> "Yeniden uyarının varsayılanı bugünkü 24 saat kalır." Değer 0 (varsayılan) = ailenin geçersiz
 * kılması YOK → karar bugünkü {@code EscalationService.reAlertDue(son, şimdi, globalSaat)} çağrısının KENDİSİdir (eşik
 * tablosundaki {@code reAlertIntervalHours}, varsayılan 24). Değer verilirse yalnız o ailenin alarmları bu aralıkla
 * hatırlatılır; diğer aileler etkilenmez. İzin verilen: 0 ya da 15–10080 (15 dk – 7 gün).
 *
 * <p>Kapsam: bireysel alarmların e-posta / kişi webhook'u yeniden uyarısı (sertifika süpürmesi, açılış telafisi, izleme
 * süpürmeleri). Fırtına (storm) toplu hatırlatması takım düzeyindedir ve kendi günlük kadansını korur; kişi push kanalı
 * kendi günlük RE_ALERT tekilleştirmesini korur.
 */
public final class ReAlertIntervals {

    private ReAlertIntervals() {}

    public static final String PREFIX = "site.monitor.realert.";
    public static final String SUFFIX = "-minutes";
    /** 0 dışındaki en küçük / en büyük değer (dakika). */
    public static final int MIN_MINUTES = 15;
    public static final int MAX_MINUTES = 10080;

    /** Aile → ayar anahtarı (sıra {@link MonitorTypeCatalog#ORDER}). */
    public static final Map<String, String> KEYS = buildKeys();

    private static Map<String, String> buildKeys() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String family : MonitorTypeCatalog.ORDER) m.put(family, PREFIX + family + SUFFIX);
        return java.util.Collections.unmodifiableMap(m);
    }

    public static boolean isKey(String key) {
        return key != null && KEYS.containsValue(key);
    }

    /** Alarm tipinin ailesinin anahtarı; ailesi bilinmeyen tip → null (genel aralık). */
    public static String keyForAlertType(String alertType) {
        String family = MonitorTypeCatalog.typeOfAlert(alertType);
        return family == null ? null : KEYS.get(family);
    }

    /**
     * Saklanan / ortamdan gelen ham değerin ETKİN karşılığı: ≤0 → 0 (geçersiz kılma yok); 1–14 → 15; &gt;10080 → 10080.
     * Kaydetme yolu aralık dışını zaten reddeder; bu kıskaç elle yazılmış ortam değişkenine karşı savunmadır.
     */
    public static int effective(int raw) {
        if (raw <= 0) return 0;
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, raw));
    }

    /** Kaydetme doğrulaması: 0 ya da 15–10080; aksi hâlde 400 (kullanıcının dilinde). Boş değer (varsayılana dön) geçerli. */
    public static void validate(String key, String val) {
        if (val == null || val.isBlank()) return;
        int v;
        try { v = Integer.parseInt(val.trim()); }
        catch (Exception e) { throw new IllegalArgumentException(key + Msg.t(": tam sayı olmalı", ": must be a whole number")); }
        if (v == 0) return;
        if (v < MIN_MINUTES || v > MAX_MINUTES) {
            throw new IllegalArgumentException(key + Msg.t(
                    ": 0 (genel aralığı kullan) ya da " + MIN_MINUTES + "–" + MAX_MINUTES + " dakika olmalı",
                    ": must be 0 (use the global interval) or " + MIN_MINUTES + "–" + MAX_MINUTES + " minutes"));
        }
    }
}
