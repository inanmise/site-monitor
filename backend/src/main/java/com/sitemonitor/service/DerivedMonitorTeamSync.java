package com.sitemonitor.service;

import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Envanter-türevi izlemelerin {@code team_id} sütununu envanterle eşitler.
 *
 * <p><b>Neden gerekti.</b> Türev DNS/Port satırları takımlarını lazy-provision anında envanterden
 * bir kez kopyalar ve o kopya bir daha ASLA tazelenmezdi: ne envanter transferi, ne envanter
 * güncellemesi, ne {@code MonitoringGroupBackfill} (yalnız NULL doldurur), ne de bir zamanlanmış
 * iş dokunuyordu. Envanter başka takıma taşındığında satırdaki takım eskide kalıyordu.
 *
 * <p><b>Neden yetki kapısı tek başına yetmiyor.</b> {@code MonitoringController.effectiveTeam}
 * yetkiyi ve ekranı anında doğru yapar — ama o, oturumlu bir istek yolunda çalışır. Zamanlayıcı
 * oturumsuzdur ve alarm yönlendirmesini doğrudan sütundan okur
 * ({@code SchedulerService} DNS alarm bağlamı). Sütun tazelenmezse kesinti bildirimi ESKİ takıma
 * gitmeye devam eder; kimse de bunu ekranda göremez.
 *
 * <p><b>Standalone satırlara DOKUNULMAZ.</b> Onlar envanterden bağımsızdır ve takımları kendi
 * alanlarıdır; buradan güncellemek kullanıcının kendi seçimini ezerdi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DerivedMonitorTeamSync {

    private final DnsMonitorRepository dnsMonitorRepo;
    private final PortMonitorRepository portMonitorRepo;

    /**
     * {@code domain} envanter kaydının takımı değiştiğinde çağrılır: o domain'den türeyen
     * DNS ve Port izlemelerinin takımını {@code newTeamId} yapar.
     *
     * <p>Türev satırın anahtarı DNS'te {@code domain}, Port'ta {@code host}'tur ve ikisi de
     * envanterin domain'inden doğar ({@code m.setHost(inv.getDomain())}).
     *
     * @return güncellenen satır sayısı (denetim kaydına yazılabilir)
     */
    @Transactional
    public int syncTeam(String domain, Long newTeamId) {
        if (domain == null || domain.isBlank()) return 0;
        int n = 0;

        for (var m : dnsMonitorRepo.findAll()) {
            if (Boolean.TRUE.equals(m.getStandalone())) continue;      // envanterden bağımsız
            if (!domain.equalsIgnoreCase(m.getDomain())) continue;
            if (java.util.Objects.equals(m.getTeamId(), newTeamId)) continue;
            m.setTeamId(newTeamId);
            dnsMonitorRepo.save(m);
            n++;
        }
        for (var m : portMonitorRepo.findAll()) {
            if (Boolean.TRUE.equals(m.getStandalone())) continue;
            if (!domain.equalsIgnoreCase(m.getHost())) continue;
            if (java.util.Objects.equals(m.getTeamId(), newTeamId)) continue;
            m.setTeamId(newTeamId);
            portMonitorRepo.save(m);
            n++;
        }

        if (n > 0) log.info("Envanter takım değişimi: {} türev izlemenin takımı {} olarak güncellendi ({})",
                n, newTeamId, domain);
        return n;
    }
}
