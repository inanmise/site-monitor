package com.sitemonitor.service;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.repository.DiagnosticRunRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Tanılama geçmişi — her CONNECTION/OPENSSL çalıştırmasını kim/ne zaman/nereden
 * /sonuç ile kalıcı kaydeder ve domain bazında geri okur. Yetki kontrolü
 * controller'da (ADMIN); kayıt hatası tanılama akışını bozmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticHistoryService {

    private final DiagnosticRunRepository repo;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    public void record(String domain, Integer port, String runType,
                       String actor, Long actorId, Long actorTeamId, String sourceIp,
                       boolean success, String summary, Object resultObj) {
        try {
            DiagnosticRun d = new DiagnosticRun();
            d.setDomain(domain);
            d.setPort(port);
            d.setRunType(runType);
            d.setExecutedBy(actor);
            d.setExecutedById(actorId);
            d.setExecutedByTeamId(actorTeamId);
            d.setSourceIp(sourceIp);
            d.setSuccess(success);
            d.setSummary(summary);
            d.setResultJson(objectMapper.writeValueAsString(resultObj));
            d.setExecutedAt(ISO.format(Instant.now()));
            repo.save(d);
        } catch (Exception e) {
            log.warn("Tanılama geçmişi kaydı yazılamadı: domain={} type={} err={}",
                    domain, runType, e.getMessage());
        }
    }

    /** Domain geçmişi — resultJson hariç özet alanlar (liste). */
    public List<DiagnosticRun> history(String domain) {
        return repo.findTop100ByDomainOrderByIdDesc(domain);
    }

    /** Tek kayıt — resultJson dahil (yeniden gösterim). */
    public DiagnosticRun get(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Diagnostic run not found: " + id));
    }
}
