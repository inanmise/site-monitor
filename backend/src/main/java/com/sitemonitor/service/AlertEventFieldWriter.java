package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Alarm satırının HEDEFLİ yazımı (2026-10-23, çok pod R2b · F): okunan {@link AlertEvent} bütünüyle kaydedilmez, yalnız yolun
 * DEĞİŞTİRDİĞİ kolonlar tek bir {@code UPDATE … WHERE id = ?} ile yazılır.
 *
 * <p><b>Neden.</b> {@code AlertEvent}'te {@code @Version} / {@code @DynamicUpdate} yok: {@code save(event)} okunan kopyanın
 * TÜM kolonlarını yazar. Okuma ile kayıt arasında (çoğu zaman araya bir SMTP gönderimi girer) başka bir yol / pod alarmı
 * kapattıysa bayat kopya {@code resolved = false}'u geri yazıp alarmı YENİDEN AÇIYORDU ("hayalet alarm"); arada yazılmış
 * onay, yeniden uyarı damgası, fırtına bağı da geri alınıyordu.
 *
 * <p><b>Kural.</b> Bu sınıf kapanış kolonlarını ({@code resolved}, {@code resolved_at}, {@code resolved_by},
 * {@code resolved_note}, {@code resolved_silently}) ASLA yazmaz — kapanış CAS ({@code markResolvedIfOpenNotify}) ya da
 * {@link SilentAlarmCloser} ile olur; bu yüzden bir alarmı ne kapatabilir ne yeniden açabilir. Salt okunur kolonlar
 * ({@code oncall_emails}, {@code resolution_notify*}) ve kimlik de yazılmaz; bilinmeyen / yasak özellik bir programlama
 * hatasıdır ({@link IllegalArgumentException}). {@code onlyOpen} = yalnız hâlâ AÇIK satıra yaz (niyet "açık alarm":
 * onay, anahtar taşıma, takım damgası taşıma); 0 satır = alarm bu arada kapandı.
 *
 * <p>Okunan varlık DEĞİŞTİRİLMEZ (değerleri yalnız okunur): çağıran işlemde yönetilen varlığı değiştirirse kirli kontrol
 * bütün satırı yine yazar — işlem içi çağıranlar ({@code TeamAdminService.moveAll}, onay) varlığa dokunmadan değeri verir.
 */
@Component
public class AlertEventFieldWriter {

    /** Yazılabilir özellikler → okuyucu. Kapanış ve salt okunur kolonlar BİLEREK yok. */
    static final Map<String, Function<AlertEvent, Object>> WRITABLE;

    static {
        Map<String, Function<AlertEvent, Object>> m = new LinkedHashMap<>();
        m.put("domain", AlertEvent::getDomain);
        m.put("alertLevel", AlertEvent::getAlertLevel);
        m.put("alertType", AlertEvent::getAlertType);
        m.put("message", AlertEvent::getMessage);
        m.put("daysRemaining", AlertEvent::getDaysRemaining);
        m.put("notAfter", AlertEvent::getNotAfter);
        m.put("teamId", AlertEvent::getTeamId);
        m.put("notifiedContacts", AlertEvent::getNotifiedContacts);
        m.put("contextJson", AlertEvent::getContextJson);
        m.put("acknowledged", AlertEvent::getAcknowledged);
        m.put("acknowledgedBy", AlertEvent::getAcknowledgedBy);
        m.put("acknowledgedAt", AlertEvent::getAcknowledgedAt);
        m.put("acknowledgedNote", AlertEvent::getAcknowledgedNote);
        m.put("lastReAlertAt", AlertEvent::getLastReAlertAt);
        m.put("realertCount", AlertEvent::getRealertCount);
        m.put("stormId", AlertEvent::getStormId);
        m.put("groupName", AlertEvent::getGroupName);
        m.put("notificationGroupId", AlertEvent::getNotificationGroupId);
        WRITABLE = java.util.Collections.unmodifiableMap(m);
    }

    // Yolların kolon kümeleri — her biri yolun kendisinin (ya da araya giren fırtına değerlendirmesinin) bellekte değiştirdiği
    // kolonlar; başka hiçbiri.
    /** Gönderim sonrası kayıt: bildirilen kişiler + yeniden uyarı damgası (ilk bildirim, eskalasyon sonrası, elle yeniden bildirim). */
    public static final String[] NOTIFIED = {"notifiedContacts", "lastReAlertAt"};
    /** Sertifika terfisi ({@code EscalationService.EscalationUndo} ile aynı kolonlar — geri alma da bunları yazar). */
    public static final String[] CERT_ESCALATION = {"alertLevel", "message", "acknowledged", "acknowledgedAt",
            "acknowledgedBy", "lastReAlertAt", "daysRemaining", "notAfter", "notifiedContacts"};
    /** Onay kolonları (izleme terfisi onayı düşürdüğünde). */
    public static final String[] ACK = {"acknowledged", "acknowledgedBy", "acknowledgedAt"};

    @PersistenceContext
    private EntityManager em;

    /**
     * {@code props} kolonlarını {@code event}'in BELLEKTEKİ değerleriyle yazar (varlık değiştirilmez).
     *
     * @return güncellenen satır sayısı (0 = satır yok ya da {@code onlyOpen} iken kapanmış)
     */
    @Transactional
    public int write(AlertEvent event, boolean onlyOpen, String... props) {
        if (event == null || event.getId() == null || props == null || props.length == 0) return 0;
        Map<String, Object> values = new LinkedHashMap<>();
        for (String p : props) values.put(p, getter(p).apply(event));
        return doWrite(event.getId(), values, onlyOpen);
    }

    /** Açık değerlerle yazım — işlem içindeki yönetilen varlığa DOKUNMADAN (onay, anahtar taşıma). */
    @Transactional
    public int writeValues(Long id, Map<String, Object> values, boolean onlyOpen) {
        if (id == null || values == null || values.isEmpty()) return 0;
        for (String p : values.keySet()) getter(p);   // doğrulama
        return doWrite(id, values, onlyOpen);
    }

    /**
     * Takım taşıma: {@code from} takımının AÇIK alarmlarının damgası {@code to}'ya — tek ifade; kapanmış olayın damgası tarih
     * kaydıdır, dokunulmaz ({@code TeamAdminService.moveAll}). Varlık okunmaz, yönetilen kopya kirlenmez.
     */
    @Transactional
    public int moveOpenAlertsToTeam(Long from, Long to) {
        if (from == null) return 0;
        return em.createQuery("UPDATE AlertEvent e SET e.teamId = :to WHERE e.teamId = :from AND e.resolved = false")
                .setParameter("to", to).setParameter("from", from).executeUpdate();
    }

    private int doWrite(Long id, Map<String, Object> values, boolean onlyOpen) {
        StringBuilder jpql = new StringBuilder("UPDATE AlertEvent e SET ");
        int i = 0;
        for (String p : values.keySet()) {
            if (i > 0) jpql.append(", ");
            jpql.append("e.").append(p).append(" = :p").append(i++);
        }
        jpql.append(" WHERE e.id = :id");
        if (onlyOpen) jpql.append(" AND e.resolved = false");
        Query q = em.createQuery(jpql.toString());
        i = 0;
        for (Object v : values.values()) q.setParameter("p" + i++, v);
        q.setParameter("id", id);
        return q.executeUpdate();
    }

    private static Function<AlertEvent, Object> getter(String prop) {
        Function<AlertEvent, Object> g = WRITABLE.get(prop);
        if (g == null) throw new IllegalArgumentException("AlertEvent özelliği hedefli yazımda yazılamaz: " + prop);
        return g;
    }
}
