package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorReportIncident;
import com.sitemonitor.repository.TechErrorReportIncidentRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.sitemonitor.service.techerror.TechErrorIncidentLinks.*;

/**
 * Rapor yazım yollarının olay bağı kancaları (2026-11-20). {@link TechErrorReportService} bunları KENDİ işleminde, raporun
 * satır kilidini aldıktan SONRA çağırır (kilit sırası: rapor satırı → bağ satırları); burada işlem açılmaz. Yalnız bağ
 * deposuna bağlıdır — servisle döngü yok.
 *
 * <ul>
 *   <li>{@link #afterValuesSaved}: değer kaydından sonra sabitleme tespiti + konusu kalmamış bağın silinmesi;</li>
 *   <li>{@link #afterTemplateRefresh}: şablonun son hâli uygulanınca konusu kalan bağlar korunur, uyarlamanın (conform)
 *       değiştirdiği izler yeniden tabanlanır (kullanıcı değişikliği sayılmaz);</li>
 *   <li>{@link #deleteForReport} / {@link #deleteForReports}: rapor / kanal silinince aynı işlemde.</li>
 * </ul>
 */
@Component
public class TechErrorIncidentLinkHooks {

    private final TechErrorReportIncidentRepository links;

    public TechErrorIncidentLinkHooks(TechErrorReportIncidentRepository links) {
        this.links = links;
    }

    public TechErrorReportIncidentRepository repository() {
        return links;
    }

    /** Değer kaydından sonra (satır kilidi altında): sabitleme tespiti, konusu kalmamış bağın silinmesi. */
    public void afterValuesSaved(Long reportId, Template t, Values after) {
        List<TechErrorReportIncident> all = links.findByReportIdOrderByIdAsc(reportId);
        if (all.isEmpty()) return;
        reconcile(all, t, after);
    }

    /**
     * Bağları güncel değerlere göre uzlaştırır (satır kilidi ALTINDA çağrılmalı): konusu yoksa siler, kullanıcının
     * değiştirdiği grupları sabitler. Değişen satırlar kaydedilir; silinenler toplu silinir. Kalan bağları döner.
     */
    public List<TechErrorReportIncident> reconcile(List<TechErrorReportIncident> all, Template t, Values v) {
        List<Long> gone = new ArrayList<>();
        List<TechErrorReportIncident> kept = new ArrayList<>();
        List<TechErrorReportIncident> changed = new ArrayList<>();
        for (TechErrorReportIncident l : all) {
            Issue issue = issueOf(t, v, l.getSectionId(), l.getIssueId());
            if (issue == null) {
                gone.add(l.getId());
                continue;
            }
            LinkedHashSet<String> pinned = parseCsv(l.getPinned(), GROUPS);
            LinkedHashSet<String> next = detectPins(issue, parseFp(l.getFieldsFp()), pinned);
            if (!next.equals(pinned)) {
                l.setPinned(csv(next, GROUPS));
                changed.add(l);
            }
            kept.add(l);
        }
        if (!gone.isEmpty()) links.deleteByIds(gone);
        if (!changed.isEmpty()) links.saveAll(changed);
        return kept;
    }

    /**
     * Şablon yenilemeden sonra (satır kilidi altında): konusu kalmayan bağ silinir. Kalan bağda, sabitlenmemiş ve izi olan her
     * grup için: yenilemeden ÖNCE iz tutuyorsa (kullanıcı dokunmamış) ve uyarlama değeri değiştirdiyse iz yeni değere
     * taşınır; önce de tutmuyorsa (kullanıcı değiştirmiş) grup sabitlenir.
     */
    public void afterTemplateRefresh(Long reportId, Template prev, Values before, Template next, Values after) {
        List<TechErrorReportIncident> all = links.findByReportIdOrderByIdAsc(reportId);
        if (all.isEmpty()) return;
        List<Long> gone = new ArrayList<>();
        List<TechErrorReportIncident> changed = new ArrayList<>();
        for (TechErrorReportIncident l : all) {
            Issue now = issueOf(next, after, l.getSectionId(), l.getIssueId());
            if (now == null) {
                gone.add(l.getId());
                continue;
            }
            Issue was = issueOf(prev, before, l.getSectionId(), l.getIssueId());
            LinkedHashSet<String> pinned = parseCsv(l.getPinned(), GROUPS);
            Map<String, String> fp = parseFp(l.getFieldsFp());
            boolean dirty = false;
            for (String g : GROUPS) {
                if (pinned.contains(g) || fp.get(g) == null) continue;
                String saved = fp.get(g);
                String b = fingerprint(g, was);
                String a = fingerprint(g, now);
                if (!saved.equals(b)) {
                    pinned.add(g);
                    dirty = true;
                } else if (!a.equals(b)) {
                    fp.put(g, a);
                    dirty = true;
                }
            }
            if (dirty) {
                l.setPinned(csv(pinned, GROUPS));
                l.setFieldsFp(fpJson(fp));
                changed.add(l);
            }
        }
        if (!gone.isEmpty()) links.deleteByIds(gone);
        if (!changed.isEmpty()) links.saveAll(changed);
    }

    public void deleteForReport(Long reportId) {
        links.deleteForReport(reportId);
    }

    public void deleteForReports(List<Long> reportIds) {
        for (int i = 0; i < reportIds.size(); i += 500) links.deleteForReports(reportIds.subList(i, Math.min(reportIds.size(), i + 500)));
    }

    /** Bölümün (anomali bölümü olmalı) konusunu bulur; yoksa null. */
    static Issue issueOf(Template t, Values v, String sectionId, String issueId) {
        if (t == null || v == null) return null;
        Section s = t.section(sectionId);
        if (s == null || !"issues".equals(s.kind())) return null;
        for (Issue i : v.issues().getOrDefault(sectionId, List.of())) if (i.id().equals(issueId)) return i;
        return null;
    }
}
