package com.sitemonitor.service.forward;

import java.util.List;

/**
 * Bir alarmın İLETİLECEĞİ tek alıcı takım (2026-11-05, alarm iletimi). {@link AlertForwardRouting#targetsFor} alıcı takım
 * başına TEK öğe döndürür: aynı alıcı takıma giden birden çok iletim (ör. SY'nin "tüm izlemeler" + UG'nin "seçili üyeler")
 * burada birleşir — bir alarm için alıcı takım başına bir e-posta, bir push kararı.
 *
 * @param recipientTeamId   alıcı takım
 * @param recipientTeamName alıcı takımın adı (e-posta bandı, günlük satırı {@code recipient_name})
 * @param ownerTeamNames    alarmı bu takıma ileten sahip takım(lar)ın adları (bant: "X takımının izlemesi")
 * @param forwardIds        eşleşen iletimlerin kimlikleri (artan)
 * @param emails            teslim edilebilir, tekil, pasif kişileri düşmüş adresler: TEAM türü iletimde takımın alarm adresi
 *                          (varsayılan bildirim grubu, yoksa takım e-postası) + MEMBERS türünde seçilen üyelerin e-postası
 * @param memberUserIds     MEMBERS türünde seçilen, hâlâ takımda AKTİF üyelerin iç kimlikleri (push doğrudan bunlara)
 * @param wholeTeam         en az bir TEAM türü iletim var → push alıcı takımın kendi push kurallarıyla takım üyelerine gider
 */
public record ForwardTarget(long recipientTeamId, String recipientTeamName, List<String> ownerTeamNames,
                            List<Long> forwardIds, List<String> emails, List<Long> memberUserIds, boolean wholeTeam) {

    public ForwardTarget {
        ownerTeamNames = ownerTeamNames == null ? List.of() : List.copyOf(ownerTeamNames);
        forwardIds = forwardIds == null ? List.of() : List.copyOf(forwardIds);
        emails = emails == null ? List.of() : List.copyOf(emails);
        memberUserIds = memberUserIds == null ? List.of() : List.copyOf(memberUserIds);
    }
}
