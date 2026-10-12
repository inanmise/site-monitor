package com.sitemonitor.service.ldap;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.LdapDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Kim ne olacak?" — LDAP grup → rol eşlemesini AÇMADAN (ya da kaydetmeden) önce etkisini gösterir (2026-10-11, kullanıcı
 * kararı). Kurallar formdaki (henüz kaydedilmemiş) hâliyle değerlendirilir; hiçbir şey yazılmaz.
 *
 * <p>Her kuralın DOĞRUDAN üyeleri AD'den okunur ({@link LdapDirectoryService#membersOfGroups}); uygulamadaki ETKİN LDAP
 * hesapları için {@link LdapRoleMapping#decide} ile AYNI karar verilir ve giriş yolunun korumaları uygulanır: kilitli rol
 * değişmez, son etkin ADMIN düşürülmez, sabit varsayılan rol ADMIN/AUDIT'i düşürmez. "Bugünkü kural" seçiliyse eşleşmeyen
 * hesaplar "değişmez (girişte AD'den yeniden hesaplanır)" sayılır — o kural ürün sahibi / müdür bilgisini girişteki AD
 * kaydından okur, önizleme bunu tahmin etmez.
 */
@Service
@RequiredArgsConstructor
public class LdapRoleMappingPreviewService {

    /** Yanıttaki en çok satır (değişenler önce). */
    public static final int MAX_ROWS = 500;

    private final AppUserRepository userRepo;
    private final LdapDirectoryService directory;

    /** Satırdaki sonuç. */
    public enum Outcome { PROMOTE, DEMOTE, CHANGE, SAME, LOCKED, LAST_ADMIN, PROTECTED, DERIVED }

    public Map<String, Object> preview(List<LdapRoleMapping.Rule> rules, String fallback) {
        String fb = LdapRoleMapping.normalizeFallback(fallback);
        List<LdapDirectoryService.GroupMembers> members = rules.isEmpty()
                ? List.of() : directory.membersOfGroups(rules.stream().map(LdapRoleMapping.Rule::group).toList());

        List<AppUser> ldapUsers = userRepo.findByActiveTrueOrderByUsernameAsc().stream()
                .filter(u -> "LDAP".equalsIgnoreCase(u.getAuthSource()))
                .toList();
        long activeAdmins = userRepo.countBySystemRoleAndActiveTrue("ADMIN");

        Map<String, Integer> outcomeCounts = new LinkedHashMap<>();
        for (Outcome o : Outcome.values()) outcomeCounts.put(o.name(), 0);
        int[] matchedPerRule = new int[rules.size()];
        List<Map<String, Object>> rows = new ArrayList<>();
        long demotedAdmins = 0;

        for (AppUser u : ldapUsers) {
            String uname = u.getUsername() == null ? "" : u.getUsername().toUpperCase(Locale.ROOT);
            int ruleIdx = -1;
            for (int i = 0; i < rules.size() && i < members.size(); i++) {
                if (members.get(i).usernames().contains(uname)) { ruleIdx = i; break; }
            }
            String cur = u.getSystemRole();
            String target;
            Outcome outcome;
            String source;
            if (ruleIdx >= 0) {
                matchedPerRule[ruleIdx]++;
                source = LdapRoleMapping.Source.GROUP.name();
                target = rules.get(ruleIdx).role();
            } else if (LdapRoleMapping.FALLBACK_DERIVED.equals(fb)) {
                source = LdapRoleMapping.Source.DERIVED.name();
                target = cur;
            } else {
                source = LdapRoleMapping.Source.FALLBACK.name();
                target = fb;
            }

            boolean locked = Boolean.TRUE.equals(u.getRoleLocked());
            if (ruleIdx < 0 && LdapRoleMapping.FALLBACK_DERIVED.equals(fb)) {
                outcome = Outcome.DERIVED;
            } else if (java.util.Objects.equals(cur, target)) {
                outcome = Outcome.SAME;
            } else if (locked) {
                outcome = Outcome.LOCKED; target = cur;
            } else if (ruleIdx < 0 && LdapRoleMapping.PROTECTED_FROM_FALLBACK.contains(cur)) {
                outcome = Outcome.PROTECTED; target = cur;
            } else if ("ADMIN".equals(cur) && !"ADMIN".equals(target) && activeAdmins - demotedAdmins <= 1) {
                outcome = Outcome.LAST_ADMIN; target = cur;
            } else {
                int a = LdapRoleMapping.rank(cur), b = LdapRoleMapping.rank(target);
                outcome = b > a ? Outcome.PROMOTE : b < a ? Outcome.DEMOTE : Outcome.CHANGE;
                if ("ADMIN".equals(cur)) demotedAdmins++;
            }
            outcomeCounts.merge(outcome.name(), 1, Integer::sum);

            if (outcome != Outcome.SAME && outcome != Outcome.DERIVED) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("username", u.getUsername());
                row.put("display_name", u.getDisplayName());
                row.put("role_before", cur);
                row.put("role_after", target);
                row.put("outcome", outcome.name());
                row.put("source", source);
                row.put("rule_index", ruleIdx >= 0 ? ruleIdx : null);
                row.put("rule_group", ruleIdx >= 0 ? rules.get(ruleIdx).group() : null);
                rows.add(row);
            }
        }

        // Değişenler önce (düşürme en üstte — en riskli), sonra korunanlar; ad sırası
        List<String> order = List.of("DEMOTE", "PROMOTE", "CHANGE", "LAST_ADMIN", "LOCKED", "PROTECTED");
        rows.sort(Comparator.<Map<String, Object>>comparingInt(r -> order.indexOf(String.valueOf(r.get("outcome"))))
                .thenComparing(r -> String.valueOf(r.get("display_name") != null ? r.get("display_name") : r.get("username")),
                        String.CASE_INSENSITIVE_ORDER));
        boolean rowsTruncated = rows.size() > MAX_ROWS;
        if (rowsTruncated) rows = new ArrayList<>(rows.subList(0, MAX_ROWS));

        List<Map<String, Object>> ruleRows = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            LdapDirectoryService.GroupMembers gm = i < members.size() ? members.get(i) : null;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            r.put("group", rules.get(i).group());
            r.put("role", rules.get(i).role());
            r.put("groups_found", gm != null ? gm.groupDns().size() : 0);
            r.put("group_dns", gm != null ? gm.groupDns() : List.of());
            r.put("ad_members", gm != null ? gm.usernames().size() : 0);
            r.put("ad_members_truncated", gm != null && gm.truncated());
            r.put("matched_users", matchedPerRule[i]);
            ruleRows.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ldap_users", ldapUsers.size());
        out.put("fallback", fb);
        out.put("counts", outcomeCounts);
        out.put("rules", ruleRows);
        out.put("rows", rows);
        out.put("rows_truncated", rowsTruncated);
        return out;
    }

    /** Önizleme sonucunun değişiklik getirip getirmediği (UI "uygula" uyarısı için). */
    public static boolean hasChanges(Map<String, Object> preview) {
        Object c = preview.get("counts");
        if (!(c instanceof Map<?, ?> m)) return false;
        for (String k : Set.of("PROMOTE", "DEMOTE", "CHANGE")) {
            Object v = m.get(k);
            if (v instanceof Number n && n.intValue() > 0) return true;
        }
        return false;
    }
}
