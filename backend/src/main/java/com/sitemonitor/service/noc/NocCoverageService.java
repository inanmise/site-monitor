package com.sitemonitor.service.noc;

import com.sitemonitor.model.NocNotificationGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 7/24 KAPSAMI — hangi izleme NOC'a gidiyor, hangisi neden gitmiyor.
 *
 * <p><b>Etkin kapsam</b> (sözleşme): izleme aktif VE {@code noc_notify} VE tür etkin VE en az bir aktif hedef grup.
 * Neden sırası kullanıcının eyleme dönüştürebileceği ilk engeli gösterir:
 * {@code PAUSED} (izleme duraklatılmış) → {@code MONITOR_OFF} (7/24 anahtarı kapalı) → {@code TYPE_DISABLED}
 * (tür Ayarlar'dan kapatılmış) → {@code NO_ACTIVE_GROUP} (hiç aktif 7/24 grubu yok). Gönderim
 * ({@link NocNotificationService}) aynı yüklemleri ve aynı grup çözümünü kullanır.
 */
@Service
@RequiredArgsConstructor
public class NocCoverageService {

    public static final String PAUSED = "PAUSED";
    public static final String MONITOR_OFF = "MONITOR_OFF";
    public static final String TYPE_DISABLED = "TYPE_DISABLED";
    public static final String NO_ACTIVE_GROUP = "NO_ACTIVE_GROUP";

    private final NocMonitorDirectory directory;
    private final NocConfigService config;
    private final NocGroupService groups;

    /** Kapsam dışı kalma nedeni; null = kapsanıyor. */
    public static String reason(NocMonitorDirectory.Row r, NocConfigService.Config cfg, boolean anyGroup) {
        if (!r.active()) return PAUSED;
        if (!r.nocNotify()) return MONITOR_OFF;
        if (!cfg.typeEnabled(r.type())) return TYPE_DISABLED;
        if (!anyGroup) return NO_ACTIVE_GROUP;
        return null;
    }

    /**
     * @param visible   görüş kapsamı yüklemi (global görücü: hepsi)
     * @param canEdit   satırda 7/24 anahtarını değiştirebilir mi (izlemenin kendi güncelleme kapısı)
     * @param teamNames takım adları
     */
    public Map<String, Object> compute(Long teamFilter, NocType typeFilter, Predicate<NocMonitorDirectory.Row> visible,
                                       Predicate<NocMonitorDirectory.Row> canEdit, Map<Long, String> teamNames) {
        NocConfigService.Config cfg = config.get();
        List<NocNotificationGroup> all = groups.list();
        boolean anyGroup = NocGroupService.anyUsable(all);
        int activeGroups = 0;
        for (NocNotificationGroup g : all) if (Boolean.TRUE.equals(g.getActive())) activeGroups++;

        List<NocMonitorDirectory.Row> rows = typeFilter == null ? directory.all() : directory.ofType(typeFilter);
        Map<String, Map<String, Integer>> byType = new LinkedHashMap<>();
        for (NocType t : NocType.values()) {
            if (typeFilter != null && t != typeFilter) continue;
            Map<String, Integer> c = new LinkedHashMap<>();
            c.put("total", 0);
            c.put("covered", 0);
            byType.put(t.name(), c);
        }
        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (String r : List.of(PAUSED, MONITOR_OFF, TYPE_DISABLED, NO_ACTIVE_GROUP)) byReason.put(r, 0);

        int total = 0, covered = 0, paused = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (NocMonitorDirectory.Row r : rows) {
            if (!visible.test(r)) continue;
            if (teamFilter != null && !teamFilter.equals(r.teamId())) continue;
            String reason = reason(r, cfg, anyGroup);
            boolean isCovered = reason == null;
            total++;
            if (isCovered) covered++;
            else byReason.merge(reason, 1, Integer::sum);
            if (!r.active()) paused++;
            Map<String, Integer> c = byType.get(r.type().name());
            c.merge("total", 1, Integer::sum);
            if (isCovered) c.merge("covered", 1, Integer::sum);

            items.add(item(r, reason, all, anyGroup, canEdit.test(r), teamNames));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("covered", covered);
        // Duraklatılmış izleme "kapsanmıyor" sayılmaz — zaten hiçbir kanaldan alarm üretmiyor; ayrı sayılır.
        summary.put("not_covered", total - covered - paused);
        summary.put("paused", paused);
        summary.put("by_type", byType);
        summary.put("by_reason", byReason);
        summary.put("active_groups", activeGroups);
        summary.put("disabled_types", cfg.disabledTypeKeys());
        summary.put("min_level", cfg.minLevel());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("items", items);
        return out;
    }

    /** Tek satır — aç/kapa ucunun yanıtı kapsam listesindeki satırla BİREBİR aynı biçimde. */
    public Map<String, Object> item(NocMonitorDirectory.Row r, boolean canEdit, Map<Long, String> teamNames) {
        List<NocNotificationGroup> all = groups.list();
        boolean anyGroup = NocGroupService.anyUsable(all);
        return item(r, reason(r, config.get(), anyGroup), all, anyGroup, canEdit, teamNames);
    }

    private static Map<String, Object> item(NocMonitorDirectory.Row r, String reason, List<NocNotificationGroup> all,
                                            boolean anyGroup, boolean canEdit, Map<Long, String> teamNames) {
        List<String> groupNames = r.nocNotify() && anyGroup
                ? NocGroupService.resolveTargets(r.nocGroupIds(), all).groupNames() : List.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", r.type().name());
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("target", r.target());
        m.put("team_id", r.teamId());
        m.put("team_name", r.teamId() == null ? null : teamNames.get(r.teamId()));
        m.put("active", r.active());
        m.put("noc_notify", r.nocNotify());
        m.put("noc_group_ids", NocGroupIds.parse(r.nocGroupIds()));
        m.put("covered", reason == null);
        m.put("reason", reason);
        m.put("group_names", groupNames);
        m.put("derived", r.derived());
        m.put("can_edit", canEdit);
        return m;
    }
}
