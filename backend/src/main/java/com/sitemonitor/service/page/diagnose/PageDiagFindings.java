package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.service.http.diagnose.HttpDiagFindings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sayfa Bütünlüğü uçtan uca tanılamasının BULGU KATALOĞU + hüküm kuralı (2026-10-05, kullanıcı isteği: "tanılama ve
 * teşhisi eksik olan … izlemeler için tanılama ekleyelim … hata alındığında detaylıca ne hatası aldığını görelim").
 *
 * <p>İki kod ailesi bir arada akar, arayüz her birini kendi i18n ad alanından çevirir:
 * <ul>
 *   <li>bu sınıfın {@link #CODES}'u → {@code pgdx.finding.<KOD>.title|body};</li>
 *   <li>ana sayfanın ağ/HTTP adımı bulguları (DNS_FAIL, TCP_TIMEOUT, PATH_DIFFERS, CLIENT_MISMATCH …) → HTTP tanılamasının
 *       kataloğu {@link HttpDiagFindings} ({@code httpdx.finding.*}) — ham ölçüm aynı ({@code RawHttpProbe}).</li>
 * </ul>
 * Kod eklemek = TR + EN metnini AYNI değişiklikte eklemek ({@code PageDiagFindingsI18nGateTest}). Kodlar HTTP / keyword /
 * Sayfa Hızı kataloglarıyla ÇAKIŞMAZ (arayüz ad alanını koddan seçer).
 *
 * <p>Önem: kaynak sorunları izlemenin ALARMINA sayılıyorsa ({@code PageCheckerService.issueAlarmWorthy} — sweep'le aynı
 * kural) fail, sayılmıyorsa warn; açıklayıcı bulgular (kimin sorunu, belirsiz/yavaş kaynak) info/warn. Hüküm önceliği HTTP
 * ile aynı: {@code PATH_DIFFERS} / {@code BOTH_PATHS_FAIL} → izlemenin yolundaki ilk fail → ilk warn (önce izlemenin yolu) →
 * {@link #PAGE_OK}.
 */
public final class PageDiagFindings {

    private PageDiagFindings() {}

    /** Ana sayfa yüklendi, alarma sayılan kaynak sorunu yok. */
    public static final String PAGE_OK = "PAGE_OK";
    /** Ham ölçüm sayfayı aldı ama izlemenin istemcisi alamadı (izleme bu durumda DOWN yazar). */
    public static final String PAGE_DOWN = "PAGE_DOWN";
    /** Ana sayfa HTTP ≥ 400 (ya da takip edilemeyen yönlendirme) döndü — izleme DOWN yazar. */
    public static final String PAGE_HTTP_STATUS = "PAGE_HTTP_STATUS";
    /** Başlıklar geldi ama HTML gövdesi süre içinde bitmedi — izleme sayfayı ayrıştıramaz, DOWN yazar. */
    public static final String PAGE_BODY_UNREAD = "PAGE_BODY_UNREAD";
    /** Kırık kaynaklar (404/410, 5xx, bağlantı hatası). */
    public static final String RESOURCES_BROKEN = "RESOURCES_BROKEN";
    /** Süre içinde yanıt vermeyen kaynaklar. */
    public static final String RESOURCES_TIMEOUT = "RESOURCES_TIMEOUT";
    /** HTTPS sayfada http:// yüklenen kaynaklar. */
    public static final String MIXED_CONTENT = "MIXED_CONTENT";
    /** Kırık / yanıtsız kaynakların bir kısmı sayfanın KENDİ sitesinde (birinci taraf) — düzeltme sizde. */
    public static final String SAME_HOST_BROKEN = "SAME_HOST_BROKEN";
    /** Kırık / yanıtsız kaynakların TAMAMI başka sitelerde (üçüncü taraf) — sunucunuz sağlam. */
    public static final String THIRD_PARTY_ONLY = "THIRD_PARTY_ONLY";
    /** Erişimi engellenen / belirsiz kaynaklar (401/403/429/503, pod'un görüş açısı) — alarm üretmez. */
    public static final String RESOURCES_BLOCKED = "RESOURCES_BLOCKED";
    /** Yavaş kaynaklar (izlemenin yavaşlık eşiğinin üstünde) — alarm üretmez. */
    public static final String RESOURCES_SLOW = "RESOURCES_SLOW";
    /** Kontrol bir sınıra takıldı (kaynak tavanı / süre bütçesi) ya da yalnız başlangıç sayfası denendi — sonuç kısmi. */
    public static final String CRAWL_LIMIT = "CRAWL_LIMIT";

    /** TAM liste — i18n kapısı bunu okur. */
    public static final List<String> CODES = List.of(
            PAGE_OK, PAGE_DOWN, PAGE_HTTP_STATUS, PAGE_BODY_UNREAD, RESOURCES_BROKEN, RESOURCES_TIMEOUT, MIXED_CONTENT,
            SAME_HOST_BROKEN, THIRD_PARTY_ONLY, RESOURCES_BLOCKED, RESOURCES_SLOW, CRAWL_LIMIT);

    /** Hüküm: {@code {status, code, params, failed_step, path}} — HTTP/keyword ile aynı öncelik, "ok" kodu {@link #PAGE_OK}. */
    public static Map<String, Object> verdict(List<Map<String, Object>> findings, String monitorOutcome, String monitorFailedStep) {
        return verdict(findings, monitorOutcome, monitorFailedStep, PAGE_OK);
    }

    /**
     * Ortak hüküm kuralı (Sayfa Hızı da kullanır). {@code monitorOutcome} {@code ok|slow|fail}; bulgu üretilmemişse (savunma)
     * sonuca göre en yakın kod: ok → {@code okCode}, slow → warn + {@code okCode}, fail → {@code RESPONSE_TIMEOUT}.
     */
    static Map<String, Object> verdict(List<Map<String, Object>> findings, String monitorOutcome, String monitorFailedStep,
                                       String okCode) {
        Map<String, Object> pick = null;
        String status = null;
        for (Map<String, Object> f : findings) {
            Object c = f.get("code");
            if (HttpDiagFindings.PATH_DIFFERS.equals(c) || HttpDiagFindings.BOTH_PATHS_FAIL.equals(c)) { pick = f; status = HttpDiagFindings.FAIL; break; }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if ("monitor".equals(f.get("path")) && HttpDiagFindings.FAIL.equals(f.get("severity"))) { pick = f; status = HttpDiagFindings.FAIL; break; }
            }
        }
        if (pick == null) {
            List<Map<String, Object>> ordered = new ArrayList<>();
            for (Map<String, Object> f : findings) if ("monitor".equals(f.get("path"))) ordered.add(f);
            for (Map<String, Object> f : findings) if (!"monitor".equals(f.get("path"))) ordered.add(f);
            for (Map<String, Object> f : ordered) {
                if (HttpDiagFindings.WARN.equals(f.get("severity"))) { pick = f; status = HttpDiagFindings.WARN; break; }
            }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if (okCode.equals(f.get("code")) && "monitor".equals(f.get("path"))) { pick = f; status = "ok"; break; }
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        if (pick == null) {
            boolean ok = "ok".equals(monitorOutcome);
            boolean slow = "slow".equals(monitorOutcome);
            v.put("status", ok ? "ok" : slow ? HttpDiagFindings.WARN : HttpDiagFindings.FAIL);
            v.put("code", ok || slow ? okCode : HttpDiagFindings.RESPONSE_TIMEOUT);
            v.put("params", new LinkedHashMap<>());
            v.put("failed_step", ok || slow ? null : monitorFailedStep);
            v.put("path", "monitor");
            return v;
        }
        v.put("status", status);
        v.put("code", pick.get("code"));
        v.put("params", pick.get("params"));
        v.put("failed_step", HttpDiagFindings.FAIL.equals(status) ? monitorFailedStep : null);
        v.put("path", pick.get("path") != null ? pick.get("path") : "monitor");
        return v;
    }
}
