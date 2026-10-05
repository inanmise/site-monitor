package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.service.http.diagnose.HttpDiagFindings;

import java.util.List;
import java.util.Map;

/**
 * Sayfa Hızı uçtan uca tanılamasının BULGU KATALOĞU (2026-10-05). Arayüz {@code psdx.finding.<KOD>.title|body}'den çevirir;
 * ana sayfanın ağ/HTTP adımı bulguları HTTP kataloğundan ({@link HttpDiagFindings}, {@code httpdx.finding.*}) gelir. Kodlar
 * HTTP / keyword / Sayfa Bütünlüğü kataloglarıyla ÇAKIŞMAZ ({@code PageDiagFindingsI18nGateTest}).
 *
 * <p>Sayfa Hızı'nda eşik aşımı bir KESİNTİ DEĞİLDİR (izleme SLOW yazar, uptime'a işlemez): {@link #THRESHOLD_BREACH} ve
 * "neden yavaş" bulguları (faz, kaynak, yol) warn; sayfa hiç ölçülemediyse fail. Metrik / sınır türü {@code params.reason}
 * ile taşınır (arayüz varyant metnini seçer: {@code …title.LOAD}, {@code …body.time} …).
 */
public final class PageSpeedDiagFindings {

    private PageSpeedDiagFindings() {}

    /** Ölçüm eşiklerin içinde. */
    public static final String PAGESPEED_OK = "PAGESPEED_OK";
    /** Ham ölçüm sayfayı aldı ama izlemenin ölçümü alamadı (izleme DOWN yazar). */
    public static final String PAGESPEED_DOWN = "PAGESPEED_DOWN";
    /** Sayfa HTTP ≥ 400 (ya da takip edilemeyen yönlendirme) döndü. */
    public static final String PAGESPEED_HTTP_STATUS = "PAGESPEED_HTTP_STATUS";
    /** HTML gövdesi süre içinde bitmedi — izleme sayfayı ölçemez. */
    public static final String PAGESPEED_BODY_UNREAD = "PAGESPEED_BODY_UNREAD";
    /** Bir eşik aşıldı — {@code reason} = LOAD | TTFB | SIZE | REQUESTS; değer, sınır, fark. */
    public static final String THRESHOLD_BREACH = "THRESHOLD_BREACH";
    /** Ad çözümleme yavaş. */
    public static final String SLOW_DNS = "SLOW_DNS";
    /** TCP bağlantısı (ya da vekil tüneli) yavaş. */
    public static final String SLOW_CONNECT = "SLOW_CONNECT";
    /** TLS el sıkışması yavaş. */
    public static final String SLOW_TLS = "SLOW_TLS";
    /** Sunucu ilk bayta kadar uzun düşündü (TTFB). */
    public static final String SLOW_SERVER = "SLOW_SERVER";
    /** HTML gövdesinin inmesi uzun sürdü. */
    public static final String SLOW_DOWNLOAD = "SLOW_DOWNLOAD";
    /** HTML belgesinin kendisi çok büyük. */
    public static final String LARGE_BODY = "LARGE_BODY";
    /** Sayfa hızlı geldi; süreyi alt kaynaklar harcadı. */
    public static final String SLOW_RESOURCES = "SLOW_RESOURCES";
    /** Ağırlığı birkaç büyük kaynak oluşturuyor. */
    public static final String HEAVY_RESOURCES = "HEAVY_RESOURCES";
    /** İzleme vekilden ölçüyor; doğrudan yol belirgin hızlı. */
    public static final String PROXY_SLOWER = "PROXY_SLOWER";
    /** İzleme doğrudan ölçüyor; vekil yolu belirgin hızlı. */
    public static final String DIRECT_SLOWER = "DIRECT_SLOWER";
    /** Ölçüm bir sınıra takıldı (kaynak tavanı / bayt bütçesi / süre) — rakamlar alt sınır. */
    public static final String MEASUREMENT_PARTIAL = "MEASUREMENT_PARTIAL";
    /** Bazı alt kaynaklar indirilemedi (ağırlığa katılmadı). */
    public static final String RESOURCES_FAILED = "RESOURCES_FAILED";

    /** TAM liste — i18n kapısı bunu okur. */
    public static final List<String> CODES = List.of(
            PAGESPEED_OK, PAGESPEED_DOWN, PAGESPEED_HTTP_STATUS, PAGESPEED_BODY_UNREAD, THRESHOLD_BREACH,
            SLOW_DNS, SLOW_CONNECT, SLOW_TLS, SLOW_SERVER, SLOW_DOWNLOAD, LARGE_BODY, SLOW_RESOURCES, HEAVY_RESOURCES,
            PROXY_SLOWER, DIRECT_SLOWER, MEASUREMENT_PARTIAL, RESOURCES_FAILED);

    /** Eşik metrikleri ({@code THRESHOLD_BREACH} varyantları) — {@code PageSpeedRules.BREACH_*} ile aynı. */
    public static final List<String> METRICS = List.of("LOAD", "TTFB", "SIZE", "REQUESTS");

    /** Hüküm — Sayfa Bütünlüğü ile aynı kural, "ok" kodu {@link #PAGESPEED_OK}. */
    public static Map<String, Object> verdict(List<Map<String, Object>> findings, String monitorOutcome, String monitorFailedStep) {
        return PageDiagFindings.verdict(findings, monitorOutcome, monitorFailedStep, PAGESPEED_OK);
    }
}
