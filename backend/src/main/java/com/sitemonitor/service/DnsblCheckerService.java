package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kara liste (DNSBL) denetimi — alan adı ve çözümlenen IP'leri e-posta kara listelerinde arar.
 *
 * <p><b>Kendi DNS istemcisini KURMAZ:</b> {@link DnsCheckerService} zaten paylaşılan bir
 * {@code ExtendedResolver} ve yapılandırılabilir zaman aşımıyla çalışıyor. İkinci bir istemci,
 * ikinci bir zaman aşımı politikası ve ikinci bir hata sınıflandırması demek olurdu.
 *
 * <p><b>UNKNOWN ≠ TEMİZ — bu sınıfın varlık sebebi:</b> DNSBL sorgusu cevapsız kalabilir
 * (kurumsal resolver dışarı çıkmıyor, liste açık resolver'ları reddediyor, SERVFAIL). Bu
 * durumları "temiz" saymak korumanın çalıştığı yanılsaması, "listede" saymak ise sahte alarm
 * üretirdi. Üçüncü bir cevap zorunlu: <b>doğrulanamadı</b>.
 *
 * <p>Spamhaus açık/public resolver'lardan gelen sorguları REDDEDER ve bunu bir "listede"
 * cevabıyla ({@code 127.255.255.252/254/255}) bildirir. Bu kodları listelenme saymak, kurumsal
 * DNS'i public resolver'a düşmüş her kurulumda TÜM domainleri kara listede göstermek olurdu —
 * kod tablosunda açıkça UNKNOWN'a eşlenir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DnsblCheckerService {

    public static final String CLEAN   = "CLEAN";
    public static final String LISTED  = "LISTED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String SKIPPED = "SKIPPED";

    /** Varsayılan listeler; ops {@code site.monitor.domain.dnsbl-lists} ile değiştirir. */
    static final String DEFAULT_LISTS = "zen.spamhaus.org,bl.spamcop.net,dbl.spamhaus.org";

    /** Domain-tabanlı (DBL) zone'lar: IP değil ALAN ADI sorgulanır. */
    static final List<String> DOMAIN_ZONES = List.of("dbl.spamhaus.org");

    private final DnsCheckerService dns;
    private final AppSettingsService appSettings;

    /**
     * @param status CLEAN | LISTED | UNKNOWN | SKIPPED
     * @param detail kanıt satırları ("zen.spamhaus.org=1.2.3.4 → 127.0.0.2"); yoksa null
     * @param hits   kaç ayrı listede bulundu
     */
    public record Result(String status, String detail, int hits) {
        public static Result skipped() { return new Result(SKIPPED, null, 0); }
    }

    /**
     * Alan adını ve IP'lerini yapılandırılmış listelerde arar.
     *
     * <p>Asla istisna fırlatmaz: kara liste denetimi bir <b>zenginleştirme</b>dir, alan adı
     * kontrolünün ön şartı değil. Patlarsa UNKNOWN döner ve kontrolün geri kalanı sürer.
     */
    public Result check(String domain, List<String> resolvedIps) {
        try {
            List<String> zones = lists();
            if (zones.isEmpty()) return Result.skipped();

            long deadline = System.currentTimeMillis() + budgetMs();
            List<String> ips = capped(resolvedIps);
            List<String> evidence = new ArrayList<>();
            boolean anyUnknown = false;
            boolean anyAnswered = false;

            for (String zone : zones) {
                boolean domainZone = DOMAIN_ZONES.stream().anyMatch(zone::equalsIgnoreCase);
                List<String> targets = domainZone ? List.of(domain) : ips;
                for (String target : targets) {
                    if (System.currentTimeMillis() > deadline) { anyUnknown = true; break; }
                    String query = domainZone ? domain + "." + zone : reverse(target) + "." + zone;
                    if (query.startsWith(".")) { anyUnknown = true; continue; }   // ters çevrilemedi

                    String verdict = interpret(dns.check(query, "A"));
                    if (LISTED.equals(verdict)) {
                        evidence.add(zone + "=" + target);
                        anyAnswered = true;
                    } else if (CLEAN.equals(verdict)) {
                        anyAnswered = true;
                    } else {
                        anyUnknown = true;
                    }
                }
            }

            if (!evidence.isEmpty()) {
                return new Result(LISTED, String.join("; ", evidence), evidence.size());
            }
            // Hiçbir sorgu cevaplanmadıysa "temiz" DEMEK YOK — koruma çalışmadı, öyle söylenir.
            if (!anyAnswered) return new Result(UNKNOWN, null, 0);
            // Kısmi: bazı listeler cevapsız kaldı. Cevap verenler temiz olsa da tam temiz denemez.
            return anyUnknown ? new Result(UNKNOWN, null, 0) : new Result(CLEAN, null, 0);

        } catch (Exception e) {
            log.debug("Kara liste denetimi yapılamadı ({}): {}", domain, e.toString());
            return new Result(UNKNOWN, null, 0);
        }
    }

    /**
     * DNS cevabını karar tablosuna göre yorumlar.
     *
     * <p>Tablo saf: ağ yok, {@link DnsCheckerService} çıktısı üzerinden çalışır ve her dalı
     * birim testiyle sabitlenir. Yanlış yorumlanan tek bir kod, ya korumayı sessizce kapatır
     * ya da tüm envanteri sahte alarma boğar.
     */
    static String interpret(Map<String, Object> dnsResult) {
        if (dnsResult == null) return UNKNOWN;
        Object err = dnsResult.get("error");
        List<String> values = new ArrayList<>();
        if (dnsResult.get("values") instanceof List<?> l) {
            for (Object v : l) if (v != null) values.add(String.valueOf(v));
        }

        if (!values.isEmpty()) {
            boolean blockedOnly = true;
            boolean listed = false;
            for (String v : values) {
                if (v.startsWith("127.255.255.")) continue;          // engelli sorgu bildirimi
                blockedOnly = false;
                if (v.startsWith("127.")) listed = true;             // 127.0.0.x ailesi = listede
            }
            // Yalnız engelli-sorgu kodları geldiyse bu bir listelenme DEĞİL, sorgunun reddidir.
            if (blockedOnly) return UNKNOWN;
            return listed ? LISTED : UNKNOWN;   // 127. dışı bir A kaydı beklenmedik → iddia etme
        }

        String e = err == null ? "" : String.valueOf(err).toUpperCase(Locale.ROOT);
        if (e.contains("NXDOMAIN")) return CLEAN;   // listede DEĞİL — tek "temiz" kanıtı budur
        return UNKNOWN;                             // SERVFAIL / REFUSED / timeout / no answer
    }

    /** {@code 1.2.3.4} → {@code 4.3.2.1}. IPv6 ve ayrıştırılamayan girdi desteklenmez → boş. */
    static String reverse(String ip) {
        if (ip == null || ip.isBlank() || ip.contains(":")) return "";
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) return "";
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return "";
            for (int i = 0; i < p.length(); i++) if (!Character.isDigit(p.charAt(i))) return "";
        }
        return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0];
    }

    /** IPv4 olmayanlar elenir, tavan uygulanır — tek domain yüzünden onlarca sorgu üretilmesin. */
    List<String> capped(List<String> ips) {
        List<String> out = new ArrayList<>();
        if (ips == null) return out;
        int max = Math.max(1, appSettings.getInt("site.monitor.domain.dnsbl-max-ips", 5));
        for (String ip : ips) {
            if (out.size() >= max) break;
            if (!reverse(ip).isEmpty()) out.add(ip.trim());
        }
        return out;
    }

    List<String> lists() {
        String csv = appSettings.getString("site.monitor.domain.dnsbl-lists", DEFAULT_LISTS);
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String z : csv.split(",")) {
            String t = z.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private long budgetMs() {
        return Math.max(1000L, appSettings.getInt("site.monitor.domain.dnsbl-budget-ms", 8000));
    }

    /** Liste adına göre bilinen delist sayfası — iddiasız, yalnız bilinenler. */
    public static String delistUrl(String zone) {
        if (zone == null) return null;
        String z = zone.toLowerCase(Locale.ROOT);
        if (z.contains("spamhaus")) return "https://check.spamhaus.org/";
        if (z.contains("spamcop"))  return "https://www.spamcop.net/bl.shtml";
        return null;
    }

    /** Kanıt satırlarını liste→hedef haritasına çevirir (mail ve arayüz aynı ayrıştırıcıyı kullansın). */
    public static Map<String, String> parseDetail(String detail) {
        Map<String, String> out = new LinkedHashMap<>();
        if (detail == null || detail.isBlank()) return out;
        for (String part : detail.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && !kv[0].isBlank()) out.put(kv[0].trim(), kv[1].trim());
        }
        return out;
    }
}
