package com.sitemonitor.service.noc;

import com.sitemonitor.model.NocTarget;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * İzleme varlığında 7/24 alanlarını okuma/yazma — on türün TEK kod yolu (form bağlama, tek tık aç/kapa, toplu
 * işlem). Varlık yükleme/kaydetme türden bağımsızdır ({@link NocTarget}); yetki ve denetim çağıranda kalır.
 */
@Service
public class NocMonitorService {

    private final Map<NocType, JpaRepository<?, Long>> repos = new EnumMap<>(NocType.class);
    private final NocNotificationGroupRepository groupRepo;
    private final NocMonitorDirectory directory;

    public NocMonitorService(CertificateInventoryRepository inventory, PingMonitorRepository ping,
                             HttpMonitorRepository http, KeywordMonitorRepository keyword, PageMonitorRepository page,
                             PageSpeedMonitorRepository pageSpeed, ScriptedMonitorRepository scripted,
                             DnsMonitorRepository dns, PortMonitorRepository port, DomainMonitorRepository domain,
                             NocNotificationGroupRepository groupRepo, NocMonitorDirectory directory) {
        repos.put(NocType.SSL, inventory);
        repos.put(NocType.PING, ping);
        repos.put(NocType.HTTP, http);
        repos.put(NocType.KEYWORD, keyword);
        repos.put(NocType.PAGE, page);
        repos.put(NocType.PAGESPEED, pageSpeed);
        repos.put(NocType.SCRIPTED, scripted);
        repos.put(NocType.DNS, dns);
        repos.put(NocType.PORT, port);
        repos.put(NocType.DOMAIN, domain);
        this.groupRepo = groupRepo;
        this.directory = directory;
    }

    /** Canlı izleme varlığı ya da null (yok / silinmiş — silinmiş DNS/Port ve çöp kutusundaki envanter dâhil). */
    public NocTarget load(NocType type, long id) {
        Object e = repos.get(type).findById(id).orElse(null);
        if (!(e instanceof NocTarget t)) return null;
        if (deletedAt(e) != null) return null;
        return t;
    }

    private static Object deletedAt(Object entity) {
        try {
            return entity.getClass().getMethod("getDeletedAt").invoke(entity);
        } catch (Exception ignored) {
            return null;   // silme damgası olmayan tür
        }
    }

    /** Okuma modelindeki satır (etkin takım, ad, hedef) — yoksa null. */
    public NocMonitorDirectory.Row row(NocType type, long id) {
        return directory.find(type, id);
    }

    @SuppressWarnings("unchecked")
    public NocTarget save(NocType type, NocTarget entity) {
        return (NocTarget) ((JpaRepository<Object, Long>) repos.get(type)).save(entity);
    }

    /**
     * Gövdedeki grup kimliklerini temizler: VAR OLMAYAN grup (form açıkken silinmiş — normal yarış) sessizce
     * düşer; kalan boşsa null (varsayılan gruplar). Bildirim grubunun silinmiş-grup davranışıyla aynı gerekçe:
     * 400 vermek o izlemenin bütün düzenlemelerini kilitlerdi.
     */
    public String sanitizeGroupIds(Object raw) {
        List<Long> ids = NocGroupIds.fromBody(raw);
        if (ids.isEmpty()) return null;
        Set<Long> known = new HashSet<>();
        groupRepo.findAllById(ids).forEach(g -> known.add(g.getId()));
        List<Long> kept = new ArrayList<>();
        for (Long id : ids) if (known.contains(id)) kept.add(id);
        return NocGroupIds.format(kept);
    }

    /**
     * Map gövdesinden (camelCase: {@code nocNotify}, {@code nocGroupIds}) uygular — YALNIZ gelen anahtar yazılır;
     * alanı bilmeyen istemcinin düzenlemesi 7/24 ayarını sessizce sıfırlamaz. {@code nocNotify: null} = kapalı.
     */
    public void applyFromBody(NocTarget m, Map<String, Object> body) {
        applyFromBody(m, body, this);
    }

    /** {@code svc == null} (dilimli test bağlamı) → grup kimlikleri yalnız biçimce doğrulanır. */
    public static void applyFromBody(NocTarget m, Map<String, Object> body, NocMonitorService svc) {
        if (m == null || body == null) return;
        if (body.containsKey("nocNotify")) {
            Object v = body.get("nocNotify");
            if (v != null && !(v instanceof Boolean)) throw new IllegalArgumentException("nocNotify true/false olmalı");
            m.setNocNotify(Boolean.TRUE.equals(v) ? Boolean.TRUE : Boolean.FALSE);
        }
        if (body.containsKey("nocGroupIds")) {
            Object raw = body.get("nocGroupIds");
            m.setNocGroupIds(svc != null ? svc.sanitizeGroupIds(raw) : NocGroupIds.format(NocGroupIds.fromBody(raw)));
        }
    }
}
