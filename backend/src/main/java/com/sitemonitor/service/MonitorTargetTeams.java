package com.sitemonitor.service;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * "Rehber &amp; Notlar" hedefi (tip + hedef anahtarı) → o hedefi izleyen izlemelerin ETKİN takımları (2026-09-28
 * regresyon taraması). Rehber ({@code monitor_guides}) takım kolonu TAŞIMAZ — (tip, hedef) başına tek satırdır; yetki
 * bu yüzden hedefi izleyen izlemelerden türetilir: yazma = bu izlemelerden birini ÇALIŞTIRABİLMEK
 * ({@link SessionScope#canOperateTeam}, izleme güncelleme kapısıyla aynı), okuma = birini GÖREBİLMEK.
 *
 * <p>Hedef anahtarı ekranın gönderdiğiyle birebir: HTTP/KEYWORD/PAGE/PAGESPEED = url, PING = host, PORT = host:port,
 * DNS/DOMAIN = alan adı, SCRIPTED = izleme adı.
 *
 * <p><b>DNS/Port ÇİFT KAYNAKLI:</b> envanter-türevi satırın ({@code standalone != true}) takımı ENVANTERİN takımıdır —
 * satırdaki {@code team_id} null ya da bayat olabilir ({@code MonitoringController.effectiveTeam} ile aynı kural);
 * görüş için envanterin UG takımı da sayılır ({@code MonitoringController.inventoryViewable}). Silinmiş standalone
 * satır yetki vermez.
 */
@Service
@RequiredArgsConstructor
public class MonitorTargetTeams {

    /** Bir izlemenin etkin sorumlu takımı + (yalnız envanter-türevi DNS/Port) envanterin UG takımı. */
    public record Owner(Long teamId, Long ugTeamId) {}

    private final HttpMonitorRepository httpRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final PageMonitorRepository pageRepo;
    private final PageSpeedMonitorRepository pageSpeedRepo;
    private final PingMonitorRepository pingRepo;
    private final PortMonitorRepository portRepo;
    private final DnsMonitorRepository dnsRepo;
    private final DomainMonitorRepository domainRepo;
    private final ScriptedMonitorRepository scriptedRepo;
    private final CertificateInventoryRepository inventoryRepo;

    /** Hedefi izleyen izlemelerin sahipleri; eşleşme yoksa (ya da bilinmeyen tip) boş liste. */
    public List<Owner> owners(String type, String target) {
        if (type == null || target == null || target.isBlank()) return List.of();
        return switch (type) {
            case "HTTP"      -> plain(httpRepo.findByUrl(target), HttpMonitor::getTeamId);
            case "KEYWORD"   -> plain(keywordRepo.findByUrl(target), KeywordMonitor::getTeamId);
            case "PAGE"      -> plain(pageRepo.findByUrl(target), PageMonitor::getTeamId);
            case "PAGESPEED" -> plain(pageSpeedRepo.findByUrl(target), PageSpeedMonitor::getTeamId);
            case "PING"      -> plain(pingRepo.findByHost(target), PingMonitor::getTeamId);
            case "DOMAIN"    -> plain(domainRepo.findByDomain(target), DomainMonitor::getTeamId);
            case "SCRIPTED"  -> plain(scriptedRepo.findByName(target), ScriptedMonitor::getTeamId);
            case "DNS"       -> dnsOwners(target);
            case "PORT"      -> portOwners(target);
            default          -> List.of();
        };
    }

    /** Hedefin rehberini YAZABİLİR mi — global yönetici her zaman; aksi hâlde hedefi izleyen bir izlemeyi çalıştırabilmeli. */
    public boolean canOperate(HttpSession session, String type, String target) {
        if (SessionScope.isGlobalAdmin(session)) return true;
        for (Owner o : owners(type, target)) {
            if (SessionScope.canOperateTeam(session, o.teamId())) return true;
        }
        return false;
    }

    /** Hedefin rehberini OKUYABİLİR mi — global görüntüleyici (admin/AUDIT) her zaman; aksi hâlde bir izlemeyi görebilmeli. */
    public boolean canView(HttpSession session, String type, String target) {
        if (SessionScope.isGlobalViewer(session)) return true;
        for (Owner o : owners(type, target)) {
            if (SessionScope.canView(session, o.teamId())) return true;
            if (o.ugTeamId() != null && SessionScope.canView(session, o.ugTeamId())) return true;
        }
        return false;
    }

    private static <T> List<Owner> plain(List<T> rows, Function<T, Long> team) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<Owner> out = new ArrayList<>(rows.size());
        for (T r : rows) out.add(new Owner(team.apply(r), null));
        return out;
    }

    private List<Owner> dnsOwners(String domain) {
        List<Owner> out = new ArrayList<>();
        for (DnsMonitor m : dnsRepo.findByDomain(domain)) {
            if (m.getDeletedAt() != null) continue;
            out.add(dualSourced(m.getDomain(), m.getStandalone(), m.getTeamId()));
        }
        return out;
    }

    /** PORT hedefi "host:port" — son ':' ayırır (IPv6 host'u da iki nokta taşır). Aktif + duraklatılmış standalone. */
    private List<Owner> portOwners(String target) {
        int i = target.lastIndexOf(':');
        if (i <= 0 || i == target.length() - 1) return List.of();
        String host = target.substring(0, i);
        int port;
        try { port = Integer.parseInt(target.substring(i + 1)); } catch (NumberFormatException e) { return List.of(); }
        List<Owner> out = new ArrayList<>();
        Stream.concat(portRepo.findByHostAndPortAndActiveTrue(host, port).stream(),
                        portRepo.findByHostAndPortAndStandaloneTrueAndActiveFalseAndDeletedAtIsNull(host, port).stream())
                .filter(m -> m.getDeletedAt() == null)
                .forEach(m -> out.add(dualSourced(m.getHost(), m.getStandalone(), m.getTeamId())));
        return out;
    }

    /** Standalone → kendi takımı; envanter-türevi → envanterin SY (+UG) takımı, envanter yoksa saklanan değer. */
    private Owner dualSourced(String domain, Boolean standalone, Long storedTeamId) {
        if (Boolean.TRUE.equals(standalone) || domain == null) return new Owner(storedTeamId, null);
        CertificateInventory inv = inventoryRepo.findByDomain(domain).orElse(null);
        if (inv == null) return new Owner(storedTeamId, null);
        return new Owner(inv.getTeamId() != null ? inv.getTeamId() : storedTeamId, inv.getUgTeamId());
    }
}
