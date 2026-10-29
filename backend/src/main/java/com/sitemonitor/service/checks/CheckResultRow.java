package com.sitemonitor.service.checks;

import java.util.Map;

/**
 * Değerlendiricinin ({@code CheckResultDrainer}, paket C) aldığı tamamlanmış iş (2026-10-29, çok pod Sürüm 3-3 P0, tasarım
 * R3-3 §3.2 "Take" / §4.3) — {@link CheckJobStore#take} kira-bağlı {@code DELETE … RETURNING} ile döner (satır tablodan
 * silinmiştir; tam bir kez değerlendirilir). {@link CheckResultApplier#apply} bir DALGA içinde (her (tür, izleme) için en çok
 * bir sonuç) alır.
 *
 * <p>{@link #resultJson} işçinin yazdığı, {@code recheck*} gövdesinin ŞEKİLLİ çıktısıdır ({@link CheckJobRunner#runCheckJob});
 * çözümü {@link CheckResultCodec#decode} — asla günlüğe yazılmaz (sayfa metni / k6 çıktısı taşıyabilir).
 *
 * @param id              iş kimliği
 * @param type            {@link CheckJobTypes} adı
 * @param monitorId       izleme kimliği
 * @param createdMs       gönderme anı (DB saati, ms)
 * @param claimedMs       sahiplenme anı (DB saati, ms; eski satırda null olabilir)
 * @param finishedMs      tamamlanma anı (DB saati, ms; null olabilir)
 * @param worker          tamamlayan pod'un örnek kimliği ({@code SchedulerService.instanceId()})
 * @param resultJson      şekilli sonuç JSON'u (null olabilir → {@link CheckResultCodec#decode} boş harita)
 * @param resultTruncated işçi sonucu sığdırmak için uzun metinleri kırptı mı ({@link CheckResultCodec#encode})
 */
public record CheckResultRow(long id, String type, long monitorId, long createdMs, Long claimedMs, Long finishedMs,
                             String worker, String resultJson, boolean resultTruncated) {

    /** Bitişten bu yana geçen süre (ms) — {@code finishedMs} yoksa {@code createdMs}'ten. Bayat sonuç atma kuralı için. */
    public long ageMs(long nowMs) {
        long base = finishedMs != null ? finishedMs : createdMs;
        return Math.max(0L, nowMs - base);
    }

    /** Çözülmüş sonuç (değiştirilebilir kopya; bozuk JSON → {@link CheckJobRunner#KEY_FAILED} işaretli harita). */
    public Map<String, Object> result() {
        return CheckResultCodec.decode(resultJson);
    }

    /** (tür, izleme) anahtarı — dalga bölmede kullanılır: {@code "<tür>:<izleme>"} (= küme vade anahtarı biçimi). */
    public String monitorKey() {
        return type + ":" + monitorId;
    }

    /** {@code toString} SONUCU YAZMAZ (günlüğe sızmasın) — yalnız kimlik alanları. */
    @Override
    public String toString() {
        return "CheckResultRow[id=" + id + ", type=" + type + ", monitorId=" + monitorId + ", createdMs=" + createdMs
                + ", finishedMs=" + finishedMs + ", worker=" + worker + ", truncated=" + resultTruncated + "]";
    }
}
