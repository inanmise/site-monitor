package com.sitemonitor.service.checks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bir kontrol işinin GÖVDESİ (2026-10-29, çok pod Sürüm 3-3 P0, tasarım R3-3 §4.1) — paket B ({@code SchedulerService})
 * sağlar, paket A'nın işçisi ({@code CheckWorker}) bir {@code ObjectProvider} üzerinden çağırır.
 *
 * <p><b>Sözleşme.</b> {@link #runCheckJob} izlemeyi türünün tablosundan YENİDEN okur ({@code findById}, yalnız aktif) ve
 * klasik taramanın tedarikçi gövdesini AYNEN çağırır ({@code recheckPort(m)}, {@code recheckPing(m)} — {@code checked_at}
 * dahil —, {@code recheckHttp(m)}, {@code recheckKeyword(m)}, {@code recheckPage(m, false, "SINGLE_PAGE")},
 * {@code recheckPageSpeed(m, false)}, {@code persistScripted(m, run(m), false)} — SKIPPED olduğu gibi —, DNS için ayrıştırılan
 * kontrol + taban + {@code dns_records} kaydı): kontrol koşar, geçmiş satırı + etkinlik yazılır, ŞEKİLLİ çıktı döner. Çıktıya
 * {@link #KEY_TARGET} (hedef parmak izi, §3.5) eklenir. Dönen harita {@link CheckResultCodec#encode} ile JSON'a girer.
 *
 * <ul>
 *   <li>İzleme yok / pasif → {@link #inactive()} (kontrol YAPILMAZ).</li>
 *   <li>Gövde istisna fırlatırsa → {@link #failed(Throwable)} (yalnız sınıf adı, ileti YOK) = bugünkü "kontrol düştü, kalem
 *       yok". Uygulayıcı fırlatmamaya çalışır; işçi yine de her {@code Throwable}'ı bu işaretle tamamlar.</li>
 *   <li>Kiradan önce bitmezse işçi {@link #workerTimeout()} ile tamamlar ({@link CheckJobTypes#workerTimeoutMs}).</li>
 * </ul>
 *
 * <p><b>K-2:</b> gövde {@code MonitoringOutageService}'i ÇAĞIRMAZ — değerlendirme yalnız tarama liderinde, sonuçlar
 * {@link CheckResultApplier} üzerinden.
 */
public interface CheckJobRunner {

    /** Gövde istisna verdi — değer: istisnanın basit sınıf adı (ileti asla). Kalem üretilmez. */
    String KEY_FAILED = "failed";
    /** İşçi kontrolü kirasından önce kesti ({@code true}). Kalem üretilmez (= bugünkü 180 sn birleştirme tavanı). */
    String KEY_WORKER_TIMEOUT = "worker_timeout";
    /** İzleme bulunamadı / pasif ({@code true}) — kontrol yapılmadı. Kalem üretilmez. */
    String KEY_INACTIVE = "inactive";
    /**
     * Hedef parmak izi (§3.5): port {@code host|port|protocol}, http {@code url|method}, keyword {@code url|keyword}, ping
     * {@code host|ipVersion}, dns {@code domain|recordType}, page / pagespeed {@code url}, scripted {@code name|scriptVersion}.
     * Değerlendirici güncel varlığın parmak iziyle uyuşmazsa sonucu atar (yeniden adlandırılmış / düzenlenmiş hedef).
     */
    String KEY_TARGET = "target";

    /**
     * İşi koşturur — fırlatmamalıdır (bkz. arayüz açıklaması).
     *
     * @param type      {@link CheckJobTypes} adı
     * @param monitorId izleme kimliği
     * @return şekilli sonuç ya da işaret haritası (null dönmez; dönerse işçi {@link #failed} ile tamamlar)
     */
    Map<String, Object> runCheckJob(String type, long monitorId);

    /** {@code {"failed": "<SınıfAdı>"}} — ileti yazılmaz (gizli / uzun olabilir). */
    static Map<String, Object> failed(Throwable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_FAILED, t == null ? "Unknown" : t.getClass().getSimpleName());
        return m;
    }

    /** {@code {"worker_timeout": true}}. */
    static Map<String, Object> workerTimeout() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_WORKER_TIMEOUT, Boolean.TRUE);
        return m;
    }

    /** {@code {"inactive": true}}. */
    static Map<String, Object> inactive() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_INACTIVE, Boolean.TRUE);
        return m;
    }

    /**
     * Sonuç gerçek bir kontrol çıktısı mı — null / boş / {@link #KEY_FAILED} / {@link #KEY_WORKER_TIMEOUT} /
     * {@link #KEY_INACTIVE} işaretli harita kalem ÜRETMEZ (değerlendirici atlar).
     */
    static boolean carriesResult(Map<String, ?> result) {
        if (result == null || result.isEmpty()) return false;
        if (result.get(KEY_FAILED) != null) return false;
        if (Boolean.TRUE.equals(result.get(KEY_WORKER_TIMEOUT))) return false;
        return !Boolean.TRUE.equals(result.get(KEY_INACTIVE));
    }
}
