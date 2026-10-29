package com.sitemonitor.service.checks;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import com.sitemonitor.util.Msg;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dağıtık kontrol iş türleri (2026-10-29, çok pod Sürüm 3-3 P0, tasarım R3-3 §1.2 / §5.1). Adlar KALICI: tablo
 * {@code monitor_check_jobs.job_type}, ayar {@code site.monitor.checks.distributed.types} ve küme vade anahtarlarının öneki
 * ({@code SchedulerService.checkDue("<tür>", id, …)} → {@code monitor_check_schedule.monitor_key = "<tür>:<id>"}) aynı
 * dizelerdir = {@link ManualEvalInbox} {@code TYPE_*} değerleri.
 *
 * <p>Dağıtılan sekiz tür: {@link #PORT}, {@link #PING}, {@link #HTTP}, {@link #KEYWORD}, {@link #PAGE} (yalnız SINGLE_PAGE
 * sık tarama), {@link #PAGESPEED}, {@link #DNS}, {@link #SCRIPTED}. Liderde kalanlar (U-5): uptime / ACCESSIBILITY turu, derin
 * sayfa taraması, HTTP / keyword SSL + alan adı bitiş turları, alan adı kaydı ve 16:00 kritik geçişi — bunların adı bu listede
 * yoktur ve ayarda yazılamaz.
 *
 * <p>Saf (durumsuz) — veritabanı, günlük, iş parçacığı yok.
 */
public final class CheckJobTypes {

    private CheckJobTypes() {
    }

    public static final String PORT = ManualEvalInbox.TYPE_PORT;
    public static final String PING = ManualEvalInbox.TYPE_PING;
    public static final String HTTP = ManualEvalInbox.TYPE_HTTP;
    public static final String KEYWORD = ManualEvalInbox.TYPE_KEYWORD;
    public static final String PAGE = ManualEvalInbox.TYPE_PAGE;
    public static final String PAGESPEED = ManualEvalInbox.TYPE_PAGESPEED;
    public static final String DNS = ManualEvalInbox.TYPE_DNS;
    public static final String SCRIPTED = ManualEvalInbox.TYPE_SCRIPTED;

    /**
     * Sekiz tür, KANONİK sırada (= ayarın varsayılan sırası = RUNBOOK açma sırasına yakın: port + ping → http + keyword →
     * page + pagespeed → dns → scripted). {@link #parseLenient} sonucu da bu sırayla döner.
     */
    public static final List<String> ALL = List.of(PORT, PING, HTTP, KEYWORD, PAGE, PAGESPEED, DNS, SCRIPTED);

    /** Ayarın varsayılanı: sekiz tür, virgülle. */
    public static final String DEFAULT_CSV = String.join(",", ALL);

    /** {@code AppSettingsService.save} alan hatasının adı (400 {@code VALIDATION_FAILED} + {@code field}). */
    public static final String FIELD = "distributed_types";

    /** Ayar metninin en uzun hâli (karakter) — bölmeden ÖNCE denetlenir. */
    public static final int MAX_CSV_CHARS = 1_000;

    // ── İş yaşam döngüsü sabitleri (tasarım §3.4; A / B / C aynı sayıları kullanır) ─────────────────────────────────────

    /** port, ping, http, keyword, dns, page, pagespeed iş kirası (ms): izleme zaman aşımı tavanı 120 sn + pay. */
    public static final long LEASE_MS_DEFAULT = 150_000L;
    /** scripted (k6) iş kirası (ms): {@code scripted.max-timeout-seconds} ≤ 180 + 30 sn (izin beklemesi yok — izinle alınır). */
    public static final long LEASE_MS_SCRIPTED = 210_000L;
    /** İşçi kontrolü kiradan bu kadar ÖNCE keser ({@code orTimeout(lease − margin)}) → {@code worker_timeout} sonucu. */
    public static final long WORKER_TIMEOUT_MARGIN_MS = 10_000L;
    /** Bir iş en çok bu kadar sahiplenilir (ölen işçinin işi bir kez yeniden koşar; sonsuz zehirli döngü yok). */
    public static final int MAX_ATTEMPTS = 2;

    /** Tür bilinen sekiz addan biri mi (birebir, küçük harf). */
    public static boolean isKnown(String type) {
        return type != null && ALL.contains(type);
    }

    /** Türün iş kirası (ms) — bilinmeyen tür {@link #LEASE_MS_DEFAULT}. */
    public static long leaseMs(String type) {
        return SCRIPTED.equals(type) ? LEASE_MS_SCRIPTED : LEASE_MS_DEFAULT;
    }

    /** İşçinin kontrolü kestiği süre (ms) = kira − {@link #WORKER_TIMEOUT_MARGIN_MS}. */
    public static long workerTimeoutMs(String type) {
        return leaseMs(type) - WORKER_TIMEOUT_MARGIN_MS;
    }

    /**
     * Ayar metnini HOŞGÖRÜLÜ okur (çalışma anı): virgülle böler, boşlukları kırpar, küçük harfe çevirir ({@link Locale#ROOT}),
     * bilinmeyen / boş parçaları ATLAR, tekrarları birleştirir. Sonuç {@link #ALL} sırasında, değiştirilemez. null / boş /
     * {@link #MAX_CSV_CHARS}'ı aşan metin → boş küme (güvenli taraf: hiçbir tür dağıtılmaz → klasik tarama). Asla fırlatmaz.
     */
    public static Set<String> parseLenient(String csv) {
        if (csv == null || csv.isBlank() || csv.length() > MAX_CSV_CHARS) return Set.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : csv.split(",", -1)) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (isKnown(t)) seen.add(t);
        }
        return canonical(seen);
    }

    /** Verilen türlerin {@link #ALL} sırasındaki değiştirilemez kopyası (bilinmeyenler atılır). */
    public static Set<String> canonical(Collection<String> types) {
        if (types == null || types.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String t : ALL) if (types.contains(t)) out.add(t);
        return Collections.unmodifiableSet(out);
    }

    /**
     * Ayar KAYDI denetimi ({@code AppSettingsService.save} çağırır; boş değer = varsayılana dön, buraya gelmez): metin
     * {@link #MAX_CSV_CHARS}'ı aşamaz, her ad sekiz türden biri olmalı (büyük / küçük harf ve boşluk serbest), en az bir ad
     * bulunmalı (hiç tür dağıtmamak için ana anahtar kapatılır). Hata → {@link GlobalExceptionHandler.FieldValidationException}
     * alan {@link #FIELD}, ileti {@link Msg#t} ile arayüz dilinde.
     */
    public static void validateSetting(String csv) {
        if (csv == null || csv.isBlank()) return;
        if (csv.length() > MAX_CSV_CHARS) {
            throw new GlobalExceptionHandler.FieldValidationException(FIELD, Msg.t(
                    "Dağıtık kontrol türleri çok uzun (en çok " + MAX_CSV_CHARS + " karakter). İzinli adlar: " + DEFAULT_CSV,
                    "The distributed check types are too long (" + MAX_CSV_CHARS + " characters at most). Allowed names: "
                            + DEFAULT_CSV));
        }
        int names = 0;
        for (String part : csv.split(",", -1)) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (!isKnown(t)) {
                String shown = part.trim().length() > 40 ? part.trim().substring(0, 40) + "…" : part.trim();
                throw new GlobalExceptionHandler.FieldValidationException(FIELD, Msg.t(
                        "Bilinmeyen dağıtık kontrol türü: \"" + shown + "\". İzinli adlar (virgülle): " + DEFAULT_CSV
                                + " — listeyi düzeltip yeniden kaydedin.",
                        "Unknown distributed check type: \"" + shown + "\". Allowed names (comma separated): " + DEFAULT_CSV
                                + " — correct the list and save again."));
            }
            names++;
        }
        if (names == 0) {
            throw new GlobalExceptionHandler.FieldValidationException(FIELD, Msg.t(
                    "Dağıtık kontrol türleri listesi boş — en az bir tür yazın (ör. port,ping) ya da ana anahtarı kapatın.",
                    "The distributed check type list is empty — enter at least one type (e.g. port,ping) or turn the master "
                            + "switch off."));
        }
    }
}
