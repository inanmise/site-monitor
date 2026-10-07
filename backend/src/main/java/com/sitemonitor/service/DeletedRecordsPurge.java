package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * TEK SEFERLİK veri yaması (2026-10-07, kullanıcı kararı: "silinen izlemeleri veya sertifikaları hiçbir şekilde göremiyorum
 * … silme işlemi her şekilde kalıcı olsun"). Eski sürümün çöp kutusu — yumuşak silinmiş envanter kayıtları (silinmiş
 * takımlarınkiler dâhil) ve yumuşak silinmiş bağımsız Port/DNS izlemeleri — {@link PermanentDeletionService} üzerinden
 * KALICI silinir; açık alarmları önce sessizce kapanır. Sonuç tek bir sistem denetim olayına yazılır ({@link #AUDIT_EVENT},
 * çağıran {@code SchedulerService} işlem commit olduktan SONRA yazar).
 *
 * <p><b>Bir kez ve çoklu pod güvenli.</b> {@code schema_patch_markers} nişanı ({@link StandaloneMonitorDeletionBackfill}
 * deseni): nişan varsa hiçbir şey yapılmaz. Yoksa nişan İŞİN BAŞINDA, aynı işlemde INSERT edilir — eşzamanlı açılan ikinci
 * pod aynı birincil anahtarda birincinin commit'ini bekler, sonra çakışmayla düşer ve kendi işlemini geri alır (iş iki kez
 * yapılmaz, çift alarm kapanışı / çift denetim yok). İş düşerse nişan da geri alınır; sonraki açılış yeniden dener.
 *
 * <p>Nişan anahtarı DEĞİŞTİRİLMEZ (değişirse temizlik yeniden koşar — zararsız ama gereksiz). Yeni sürüm hiçbir satıra
 * {@code deleted_at} yazmaz; bu yüzden ikinci bir koşuya gerek yoktur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeletedRecordsPurge {

    /** Bu yamanın nişan anahtarı — DEĞİŞTİRİLMEZ. */
    public static final String KEY = "2026-10-07-permanent-delete-bin-purge";

    /** Tek sistem denetim olayı (AuditEventCatalog). */
    public static final String AUDIT_EVENT = "SYSTEM_DELETED_RECORDS_PURGE";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final CertificateInventoryRepository inventoryRepo;
    private final PortMonitorRepository portRepo;
    private final DnsMonitorRepository dnsRepo;
    private final PermanentDeletionService deletion;

    /** Silinen bir kaydın künyesi (denetim için; kişisel veri yok). */
    public record Purged(Long id, String name, Long teamId) {}

    /** Koşu sonucu. */
    public record Result(List<Purged> inventory, List<Purged> ports, List<Purged> dns, int alertsClosed) {
        public int total() { return inventory.size() + ports.size() + dns.size(); }

        /** Denetim ayrıntısı: sayılar + silinen her kaydın kimliği / adı / takımı (forensics — geri dönüş yok). */
        public String auditDetail() {
            StringBuilder sb = new StringBuilder("{\"marker\":\"").append(KEY).append("\",\"permanent\":true")
                    .append(",\"inventoryPurged\":").append(inventory.size())
                    .append(",\"portMonitorsPurged\":").append(ports.size())
                    .append(",\"dnsMonitorsPurged\":").append(dns.size())
                    .append(",\"alertsClosed\":").append(alertsClosed);
            appendList(sb, "inventory", inventory);
            appendList(sb, "portMonitors", ports);
            appendList(sb, "dnsMonitors", dns);
            return sb.append('}').toString();
        }

        private static void appendList(StringBuilder sb, String key, List<Purged> items) {
            sb.append(",\"").append(key).append("\":[");
            for (int i = 0; i < items.size(); i++) {
                Purged p = items.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"id\":").append(p.id()).append(",\"name\":\"").append(esc(p.name()))
                        .append("\",\"teamId\":").append(p.teamId()).append('}');
            }
            sb.append(']');
        }

        private static String esc(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    /**
     * Temizliği en çok BİR KEZ uygular. Dönüş: bu çağrıda yapılan iş; nişan zaten varsa {@code null}. Eşzamanlı başka bir
     * pod nişanı aldıysa nişan INSERT'i {@code DuplicateKeyException} ile düşer ve bu işlem geri alınır.
     */
    @Transactional
    public Result applyOnce() {
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
        if (done != null && done > 0) return null;
        // Nişan ÖNCE: ikinci pod burada bekler ve çakışmayla düşer (iş iki kez yapılmaz).
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, 0)",
                KEY, ISO.format(Instant.now()));

        List<Purged> inv = new ArrayList<>();
        List<Purged> ports = new ArrayList<>();
        List<Purged> dns = new ArrayList<>();
        int alerts = 0;
        for (CertificateInventory i : inventoryRepo.findByDeletedAtIsNotNullOrderByDomainAsc()) {
            var r = deletion.deleteInventory(i);
            alerts += r.alertsClosed();
            inv.add(new Purged(i.getId(), i.getDomain(), i.getTeamId()));
        }
        for (PortMonitor m : portRepo.findByDeletedAtIsNotNullOrderByIdAsc()) {
            deletion.deleteStandalonePort(m);
            ports.add(new Purged(m.getId(), m.getHost() + ":" + m.getPort(), m.getTeamId()));
        }
        for (DnsMonitor m : dnsRepo.findByDeletedAtIsNotNullOrderByIdAsc()) {
            deletion.deleteStandaloneDns(m);
            dns.add(new Purged(m.getId(), m.getDomain() + " " + m.getRecordType(), m.getTeamId()));
        }
        Result result = new Result(inv, ports, dns, alerts);
        jdbc.update("UPDATE schema_patch_markers SET rows_affected = ? WHERE patch_key = ?", result.total(), KEY);
        log.info("Tek seferlik yama {}: {} envanter + {} Port + {} DNS yumuşak silinmiş kayıt KALICI silindi ({} alarm kapandı)",
                KEY, inv.size(), ports.size(), dns.size(), alerts);
        return result;
    }
}
