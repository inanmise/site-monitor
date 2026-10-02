package com.sitemonitor.service;

import com.sitemonitor.model.MonitorGuide;
import com.sitemonitor.repository.MonitorGuideRepository;
import com.sitemonitor.service.mail.RunbookNote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Bildirim gönderiminde izlemenin REHBER bloğunu ("Rehber &amp; Notlar" sekmesi, {@code monitor_guide}) bulur
 * (2026-10-01). Gönderim başına TEK sorgu ({@code (monitor_type, target)} tekil indeksi); alıcı başına sorgu yoktur —
 * çağıran ({@code EscalationService}) sonucu o gönderimin bütün e-posta ve webhook alıcılarında yeniden kullanır.
 *
 * <p><b>Alarm → rehber anahtarı</b> ({@link #keyFor}) — {@code MonitorTargetTeams} / ekranın gönderdiği hedefle
 * birebir: HTTP/KEYWORD/PAGE/PAGESPEED = url, PING = host, DNS/DOMAIN = alan adı, SCRIPTED = izleme adı (alarmın
 * {@code domain} alanı bu değerleri taşır, {@code MonitorRefResolver} ile aynı anahtarlama); PORT = "host:port"
 * (alarmın domain'i host'tur, port bağlamdan). Sertifika ailesi (ACCESSIBILITY dahil) rehber taşımaz — sorgu atılmaz.
 *
 * <p>Hiçbir hata bildirimi etkilemez: sorgu düşerse not eklenmez (bildirim bugünkü hâliyle gider).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunbookNoteService {

    /** Rehber anahtarı: {@code monitor_guide.monitor_type} (büyük harf) + {@code target}. */
    public record GuideKey(String type, String target) { }

    private final MonitorGuideRepository guideRepo;

    /** Alarm türü + hedef (+ port için bağlam) → rehber anahtarı; rehber desteklemeyen türde ya da eksik veride null. */
    public static GuideKey keyFor(String alertType, String domain, Map<String, Object> ctx) {
        if (alertType == null || domain == null || domain.isBlank()) return null;
        String type = switch (MonitorRefResolver.family(alertType)) {
            case "http"      -> "HTTP";
            case "keyword"   -> "KEYWORD";
            case "ping"      -> "PING";
            case "dns"       -> "DNS";
            case "domain"    -> "DOMAIN";
            case "page"      -> "PAGE";
            case "pagespeed" -> "PAGESPEED";
            case "scripted"  -> "SCRIPTED";
            case "port"      -> "PORT";
            default          -> null;   // cert ailesi: rehber yüzeyi yok
        };
        if (type == null) return null;
        String target = domain.trim();
        if ("PORT".equals(type)) {
            Object p = ctx == null ? null : ctx.get("port");
            String port = p == null ? "" : String.valueOf(p).trim();
            if (port.isEmpty() || !port.chars().allMatch(Character::isDigit)) return null;
            target = target + ":" + port;
        }
        if (target.isEmpty() || target.length() > 500) return null;
        return new GuideKey(type, target);
    }

    /**
     * Alarmın hedefinin rehberi — DÜZ METİN (markdown'dan arındırılmış, henüz kırpılmamış). Rehber yoksa, boşsa,
     * tür desteklemiyorsa ya da okuma başarısızsa null. Tek sorgu; istisna yaymaz.
     */
    public String plainGuide(String alertType, String domain, Map<String, Object> ctx) {
        GuideKey key = keyFor(alertType, domain, ctx);
        if (key == null) return null;
        try {
            String text = guideRepo.findByMonitorTypeAndTarget(key.type(), key.target())
                    .map(MonitorGuide::getGuide)
                    .map(RunbookNote::toPlainText)
                    .orElse(null);
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("Rehber notu okunamadı ({} {}) — bildirim notsuz gider: {}", key.type(), key.target(), e.toString());
            return null;
        }
    }
}
