package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService.SweepItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Kalıcı değerlendirme durumu tablolarının adları ve anahtar biçimleri — kaydedici (A1) ile doğrulayıcı (A2) AYNI
 * anahtarı üretsin diye tek kaynak (2026-10-23, çok pod Sürüm 3-1 P0; tasarım R3-1 §2.2). Saf, durumsuz; fırlatmaz.
 * Tablo adları, satır biçimleri ve {@link #GENERATION_ROW} KALICIDIR — yayından sonra yeniden adlandırılmaz.
 */
public final class EvalStateKeys {

    private EvalStateKeys() {}

    /** {@code monitor_eval_type_state} içindeki kuşak + yazıcı nabzı satırının {@code alert_type} değeri. */
    public static final String GENERATION_ROW = "*";

    /** Dört tablo (adlar kalıcı). Birleştirici ve {@link EvalStateVerification.Context#changedSince} bu ayrımı kullanır. */
    public enum Table {
        /** Alarm anahtarı başına kurtarma durumu — birleştirme anahtarı {@link #recoveryKey}. */
        EVAL_STATE("monitor_eval_state"),
        /** Kalem başına son gözlem — birleştirme anahtarı {@link #lastStatusKey}. */
        LAST_STATUS("monitor_last_status"),
        /** Tür başına bayraklar (+ {@link #GENERATION_ROW}) — birleştirme anahtarı alarm türü. */
        TYPE_STATE("monitor_eval_type_state"),
        /** Süren teyit zincirleri — birleştirme anahtarı MOS teyit anahtarı ({@code confirm_key}). */
        CONFIRMATIONS("monitor_confirmations");

        private final String tableName;

        Table(String tableName) {
            this.tableName = tableName;
        }

        public String tableName() {
            return tableName;
        }
    }

    /** MOS kurtarma anahtarı: {@code "tür:alan adı"} ({@code MonitoringOutageService} ile aynı biçim). */
    public static String recoveryKey(String alertType, String domain) {
        return alertType + ":" + domain;
    }

    /**
     * Kalemin {@code monitor_last_status.item_key} değeri: {@code bağlamda monitor_id varsa "m:<id>"}, yoksa
     * {@code "d:<alan adı>|<detay>"} (kimliksiz kalemler, ör. envanter ACCESSIBILITY {@code d:host|443}; detay null →
     * boş dize). Kimlik, MOS'un {@code monitorIdOf} kuralıyla okunur ({@code Number} → {@code long}).
     */
    public static String itemKey(SweepItem item) {
        Long id = monitorIdOf(item);
        if (id != null) return "m:" + id;
        String domain = item == null ? null : item.domain();
        String detail = item == null ? null : item.detail();
        return "d:" + (domain == null ? "" : domain) + "|" + (detail == null ? "" : detail);
    }

    /** {@code monitor_last_status} birleştirme anahtarı: {@code "tür|item_key"}. */
    public static String lastStatusKey(String alertType, String itemKey) {
        return alertType + "|" + itemKey;
    }

    /** {@code monitor_confirmations.key_hash}: teyit anahtarının SHA-256'sı (küçük harf onaltılık, 64 karakter). */
    public static String keyHash(String confirmKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((confirmKey == null ? "" : confirmKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 yok", e);   // her JVM'de zorunlu algoritma — olamaz
        }
    }

    /** Doğrulama raporunda anahtarın açık metni yerine gösterilen kısa özet: {@link #keyHash}'in ilk 12 karakteri. */
    public static String shortHash(String key) {
        return keyHash(key).substring(0, 12);
    }

    private static Long monitorIdOf(SweepItem it) {
        Object v = it == null || it.ctxExtra() == null ? null : it.ctxExtra().get("monitor_id");
        return v instanceof Number n ? n.longValue() : null;
    }
}
