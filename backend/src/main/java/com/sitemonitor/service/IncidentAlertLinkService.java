package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.service.noc.NocCallLogService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Alarm ↔ olay kaydı bağı (2026-10-01, "alarmdan olay kaydı aç").
 *
 * <p><b>Yazma doğrulaması</b> ({@link #validate}): olay kaydına yazılan {@code alert_event_id} gerçekten var olan
 * bir alarmı göstermeli (yoksa 400) ve kaydı yazan kullanıcı o alarmı GÖREBİLMELİ (yoksa 403). Görünürlük kuralı
 * alarm detayının okuma kapısıyla aynıdır — {@link NocCallLogService#canRead}: 7/24 operatörü ya da {@code alerts.read}
 * + (global görücü ya da alarmın takımı — damgalı takım veya envanter SY/UG — görüş kapsamında). Aksi hâlde başka
 * takımın alarm kimlikleri olay kaydı üzerinden taranabilir / yabancı bir alarma bağlanabilirdi.
 *
 * <p><b>Okuma</b> ({@link #linked}): alarm detayının "Olay kaydı #N" bağlantıları — tek sorgu, olay kaydı takım
 * kapsamıyla süzülür (kapsam dışı kayıt hiç dönmez).
 */
@Service
@RequiredArgsConstructor
public class IncidentAlertLinkService {

    /** İstek / yanıt alanının adı (snake_case tel biçimi). */
    public static final String FIELD = "alert_event_id";

    private final AlertEventRepository alertRepo;
    private final IncidentRecordRepository incidentRepo;
    private final NocCallLogService nocCallLog;

    /**
     * Gövdedeki {@value #FIELD} değerini doğrular. Alan yoksa ya da boşsa (bağ yok / kaldırılıyor) hiçbir şey yapmaz.
     * {@code current} kayıtlı değerdir (yeni kayıtta null): DEĞİŞMEYEN bağ yeniden doğrulanmaz — düzenleme formu her
     * kayıtta tüm alanları gönderir ve alarm sonradan saklama süresiyle silinmiş olabilir.
     *
     * @throws IllegalArgumentException kimlik sayı değil ya da alarm yok (400)
     * @throws SecurityException        kullanıcı alarmı göremiyor (403)
     */
    public void validate(HttpSession session, Map<String, Object> body, Long current) {
        if (body == null || !body.containsKey(FIELD)) return;
        Object raw = body.get(FIELD);
        if (raw == null || (raw instanceof String s && s.isBlank())) return;
        Long id = parseId(raw);
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(Msg.t("Geçersiz alarm kimliği: ", "Invalid alarm id: ") + raw);
        }
        if (Objects.equals(id, current)) return;
        AlertEvent ev = alertRepo.findById(id).orElseThrow(() -> new IllegalArgumentException(
                Msg.t("Alarm bulunamadı: #", "Alarm not found: #") + id));
        if (!nocCallLog.canRead(session, ev)) {
            throw new SecurityException(Msg.t(
                    "Bu alarmı görme yetkiniz yok — olay kaydı bu alarma bağlanamaz",
                    "You don't have access to this alarm — the incident record can't be linked to it"));
        }
    }

    /**
     * Alarma bağlı, çağıranın görebildiği olay kayıtlarının özeti (en yenisi önce). {@code scope} = null → global
     * görücü (tümü); boş liste → kapsamsız kullanıcı, hiçbir şey.
     */
    public List<Map<String, Object>> linked(Long alertEventId, List<Long> scope) {
        if (alertEventId == null) return List.of();
        boolean scoped = scope != null;
        if (scoped && scope.isEmpty()) return List.of();
        List<IncidentRecord> rows = incidentRepo.findLinkedToAlert(alertEventId, scoped, scoped ? scope : List.of(-1L));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (IncidentRecord r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("title", r.getTitle());
            m.put("status", r.getStatus());
            m.put("severity", r.getSeverity());
            m.put("occurred_at", r.getOccurredAt());
            m.put("team_id", r.getTeamId());
            m.put("team_name", r.getTeamName());
            out.add(m);
        }
        return out;
    }

    private static Long parseId(Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? n.longValue() : null;
        }
        try { return Long.valueOf(raw.toString().trim()); } catch (NumberFormatException e) { return null; }
    }
}
