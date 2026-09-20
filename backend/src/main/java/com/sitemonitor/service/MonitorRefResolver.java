package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Alarm olayı → izleme referansı (ad, aile, sekme, monitor_id) — N+1'siz (2026-09-20).
 *
 * <p>{@code IncidentsController.resolveMonitors} ile aynı anahtarlama (AlertEvent.domain = izlemenin url/host/domain/ad
 * alanı); bildirim kutusu "İzlemeye git" eylemi için servis olarak açıldı. Olay ekranı kendi kopyasını taşır — o
 * kopya kapı testleriyle pinli, iki yerde değişiklik gerekirse önce burası, sonra oradaki `family`/`tabFor`.
 */
@Service
@RequiredArgsConstructor
public class MonitorRefResolver {

    private final HttpMonitorRepository httpMonitorRepo;
    private final PortMonitorRepository portMonitorRepo;
    private final KeywordMonitorRepository keywordMonitorRepo;
    private final PingMonitorRepository pingMonitorRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final DomainMonitorRepository domainMonitorRepo;
    private final PageMonitorRepository pageMonitorRepo;
    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final PageSpeedMonitorRepository pageSpeedMonitorRepo;

    /** @param family cert | http | port | … ; {@code tab} arayüz sekmesi; {@code monitorId} çözülemezse null */
    public record Ref(String name, String family, String tab, Long monitorId) {}

    public Map<Long, Ref> resolve(List<AlertEvent> events) {
        Map<Long, Ref> byEvent = new HashMap<>();
        if (events.isEmpty()) return byEvent;
        Set<String> fams = events.stream().map(e -> family(e.getAlertType())).collect(Collectors.toSet());
        Map<String, Map<String, Object[]>> idx = new HashMap<>();
        if (fams.contains("http"))      idx.put("http",      index(httpMonitorRepo.findAll(),      m -> m.getUrl(),    m -> m.getName(), m -> m.getId()));
        if (fams.contains("port"))      idx.put("port",      index(portMonitorRepo.findAll(),      m -> m.getHost(),   m -> m.getName(), m -> m.getId()));
        if (fams.contains("keyword"))   idx.put("keyword",   index(keywordMonitorRepo.findAll(),   m -> m.getUrl(),    m -> m.getName(), m -> m.getId()));
        if (fams.contains("ping"))      idx.put("ping",      index(pingMonitorRepo.findAll(),      m -> m.getHost(),   m -> m.getName(), m -> m.getId()));
        if (fams.contains("dns"))       idx.put("dns",       index(dnsMonitorRepo.findAll(),       m -> m.getDomain(), m -> m.getName(), m -> m.getId()));
        if (fams.contains("domain"))    idx.put("domain",    index(domainMonitorRepo.findAll(),    m -> m.getDomain(), m -> m.getName(), m -> m.getId()));
        if (fams.contains("page"))      idx.put("page",      index(pageMonitorRepo.findAll(),      m -> m.getUrl(),    m -> m.getName(), m -> m.getId()));
        if (fams.contains("scripted"))  idx.put("scripted",  index(scriptedMonitorRepo.findAll(),  m -> m.getName(),   m -> m.getName(), m -> m.getId()));
        if (fams.contains("pagespeed")) idx.put("pagespeed", index(pageSpeedMonitorRepo.findAll(), m -> m.getUrl(),    m -> m.getName(), m -> m.getId()));
        for (AlertEvent e : events) {
            String fam = family(e.getAlertType());
            Object[] ref = idx.containsKey(fam) ? idx.get(fam).get(e.getDomain()) : null;
            byEvent.put(e.getId(), new Ref(ref != null ? (String) ref[0] : e.getDomain(), fam, tabFor(fam), ref != null ? (Long) ref[1] : null));
        }
        return byEvent;
    }

    /** Arayüz derin bağlantı paramları: izleme sekmelerinde {@code monitor}, sertifikada pano araması {@code q}. */
    public static Map<String, Object> paramsFor(Ref ref, String domain) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (ref == null) return p;
        // Genel Bakış canlı sekme geçişinde `domain` paramını uygular (App onNav); `q` yalnız ilk yüklemede okunur (QA ISSUE-001).
        if ("cert".equals(ref.family())) { if (domain != null && !domain.isBlank()) p.put("domain", domain); return p; }
        if (ref.monitorId() != null && !"scripted".equals(ref.family())) p.put("monitor", ref.monitorId());
        return p;
    }

    private static <T> Map<String, Object[]> index(List<T> rows, Function<T, String> key, Function<T, String> name, Function<T, Long> id) {
        Map<String, Object[]> m = new HashMap<>();
        for (T r : rows) {
            String k = key.apply(r);
            if (k != null) m.putIfAbsent(k, new Object[]{ name.apply(r), id.apply(r) });
        }
        return m;
    }

    public static String family(String type) {
        if (type == null) return "cert";
        if (type.startsWith("KEYWORD"))    return "keyword";
        if (type.startsWith("PORT_"))      return "port";
        if (type.startsWith("PING"))       return "ping";
        if (type.startsWith("DNS_"))       return "dns";
        if (type.startsWith("DOMAINMON_")) return "domain";
        if (type.startsWith("PAGESPEED_")) return "pagespeed";   // PAGE_ önekinden ÖNCE: "PAGESPEED_DOWN".startsWith("PAGE_") false ama okunurluk
        if (type.startsWith("PAGE_"))      return "page";
        if (type.startsWith("SCRIPTED_"))  return "scripted";
        if ("HTTP_DOWN".equals(type) || "HTTP_SSL".equals(type) || "DOMAIN_EXPIRY".equals(type)) return "http";
        return "cert";
    }

    public static String tabFor(String fam) {
        return switch (fam) {
            case "http" -> "http"; case "port" -> "port"; case "keyword" -> "keyword";
            case "ping" -> "ping"; case "dns" -> "dns";  case "domain" -> "domain";
            case "page" -> "page"; case "scripted" -> "scripted"; case "pagespeed" -> "pagespeed";
            default -> "dashboard";
        };
    }
}
