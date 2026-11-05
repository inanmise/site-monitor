package com.sitemonitor.service.forward;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bir alarmın İLETİLECEĞİ tek alıcı takım (2026-11-05, alarm iletimi). {@link AlertForwardRouting#targetsFor} alıcı takım
 * başına TEK öğe döndürür: aynı alıcı takıma giden birden çok iletim (ör. SY'nin "tüm izlemeler" + UG'nin "seçili üyeler")
 * burada birleşir — bir alarm için alıcı takım başına bir e-posta, bir push kararı.
 *
 * @param recipientTeamId   alıcı takım
 * @param recipientTeamName alıcı takımın adı (e-posta bandı, günlük satırı {@code recipient_name})
 * @param ownerTeamIds      alarmı bu takıma ileten sahip takım(lar)ın kimlikleri (push kapısı: sahip takımın tür / takım
 *                          kapsamı bu takımlara sorulur — en az biri izin veriyorsa geçer; boş = bilinmiyor, çağıranın kuralı)
 * @param ownerTeamNames    alarmı bu takıma ileten sahip takım(lar)ın adları (bant: "X takımının izlemesi")
 * @param forwardIds        eşleşen iletimlerin kimlikleri (artan)
 * @param emails            teslim edilebilir, tekil, pasif kişileri düşmüş adresler: TEAM türü iletimde takımın alarm adresi
 *                          (varsayılan bildirim grubu, yoksa takım e-postası) + MEMBERS türünde seçilen üyelerin e-postası
 * @param memberUserIds     MEMBERS türünde seçilen, hâlâ takımda AKTİF üyelerin iç kimlikleri (push doğrudan bunlara)
 * @param wholeTeam         en az bir TEAM türü iletim var → push alıcı takımın kendi push kurallarıyla takım üyelerine gider
 */
public record ForwardTarget(long recipientTeamId, String recipientTeamName, List<Long> ownerTeamIds,
                            List<String> ownerTeamNames, List<Long> forwardIds, List<String> emails,
                            List<Long> memberUserIds, boolean wholeTeam) {

    public ForwardTarget {
        ownerTeamIds = ownerTeamIds == null ? List.of() : List.copyOf(ownerTeamIds);
        ownerTeamNames = ownerTeamNames == null ? List.of() : List.copyOf(ownerTeamNames);
        forwardIds = forwardIds == null ? List.of() : List.copyOf(forwardIds);
        emails = emails == null ? List.of() : List.copyOf(emails);
        memberUserIds = memberUserIds == null ? List.of() : List.copyOf(memberUserIds);
    }

    /**
     * Aynı alıcı takıma giden hedefleri BİRLEŞTİRİR (2026-11-06, inceleme): ör. fırtınada bir üyenin SY ve UG dağıtımları aynı
     * alıcıya iletiyorsa iki hedef gelir — ikincisini düşürmek UG'nin seçili üyelerinin push'unu kaybettiriyordu. Sahip
     * kimlikleri / adları, iletimler, adresler (büyük / küçük harf duyarsız tekil) ve üyeler birleşir; {@code wholeTeam} herhangi
     * birinde varsa. Sonuç alıcı takım kimliğine göre artan; null öğe atlanır. Tek öğeli girdi aynen döner.
     */
    public static List<ForwardTarget> mergeByRecipient(Collection<ForwardTarget> targets) {
        if (targets == null || targets.isEmpty()) return List.of();
        Map<Long, List<ForwardTarget>> by = new java.util.TreeMap<>();
        for (ForwardTarget t : targets) if (t != null) by.computeIfAbsent(t.recipientTeamId(), k -> new ArrayList<>()).add(t);
        List<ForwardTarget> out = new ArrayList<>(by.size());
        for (List<ForwardTarget> group : by.values()) out.add(group.size() == 1 ? group.get(0) : merge(group));
        return out;
    }

    private static ForwardTarget merge(List<ForwardTarget> group) {
        ForwardTarget first = group.get(0);
        Set<Long> ownerIds = new LinkedHashSet<>();
        Set<String> ownerNames = new LinkedHashSet<>();
        Set<Long> forwardIds = new TreeSet<>();
        Map<String, String> emails = new LinkedHashMap<>();
        Set<Long> members = new LinkedHashSet<>();
        boolean whole = false;
        String name = null;
        for (ForwardTarget t : group) {
            if (name == null && t.recipientTeamName() != null && !t.recipientTeamName().isBlank()) name = t.recipientTeamName();
            ownerIds.addAll(t.ownerTeamIds());
            ownerNames.addAll(t.ownerTeamNames());
            forwardIds.addAll(t.forwardIds());
            for (String e : t.emails()) if (e != null) emails.putIfAbsent(e.trim().toLowerCase(Locale.ROOT), e);
            members.addAll(t.memberUserIds());
            whole |= t.wholeTeam();
        }
        Set<String> seen = new HashSet<>();
        List<String> mails = new ArrayList<>();
        for (Map.Entry<String, String> e : emails.entrySet()) if (seen.add(e.getKey())) mails.add(e.getValue());
        return new ForwardTarget(first.recipientTeamId(), name != null ? name : first.recipientTeamName(),
                new ArrayList<>(ownerIds), new ArrayList<>(ownerNames), new ArrayList<>(forwardIds), mails,
                new ArrayList<>(members), whole);
    }
}
