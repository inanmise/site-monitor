package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

/**
 * Bir takım üyeliğinin KAYNAĞI — "bu kişi bu takıma neden üye?" sorusunun cevabı (2026-09-26).
 *
 * <p><b>Neden var (prod hatası 2026-09-26).</b> Yönetim Panelinde bir kullanıcı üyesi olmadığı bir takımda
 * görünüyordu ve ekrandan "bu üyelik nereden geldi" sorusu cevaplanamıyordu: {@code app_user_teams} yalnız
 * (kullanıcı, takım) çifti tutar; AD grubundan mı, {@code company} özniteliğinden mi, yöneticinin elle
 * eklemesinden mi, takım varlık taşımasından mı geldiği hiçbir yerde yoktu. LDAP kaynaklı değişiklikler
 * denetim kaydına da düşmüyordu.
 *
 * <p>Ayrıca güvenli budamanın ön şartı: AD artık desteklemediğinde YALNIZ AD'den türetilmiş üyelik
 * ({@link #LDAP_SOURCES}) kaldırılabilir; elle eklenen ya da kaynağı bilinmeyen (bu tablodan önceki)
 * üyelik girişte otomatik silinmez — yönetici "AD ile karşılaştır" önizlemesinde görüp karar verir.
 *
 * <p>Üyelik gerçeğinin kaynağı YİNE {@code app_user_teams}'tir; bu tablo yalnız açıklama/iz taşır.
 * Satırı olmayan üyelik = "kaynak kaydı yok (eski)".
 */
@Entity
@Table(name = "app_user_team_sources",
    indexes = { @Index(name = "idx_auts_team", columnList = "team_id") })
@IdClass(UserTeamSource.Key.class)
@Data
@NoArgsConstructor
public class UserTeamSource {

    /** AD {@code memberOf} içindeki {@code OU=ScrumGroups} grubu (detay: grup CN'i). */
    public static final String LDAP_GROUP = "LDAP_GROUP";
    /** AD {@code company} özniteliği yedeği — grup yokken (detay: company değeri). */
    public static final String LDAP_COMPANY = "LDAP_COMPANY";
    /** Yönetici elle ekledi (kullanıcı formu, takım üye yönetimi, toplu atama, oluşturma). */
    public static final String MANUAL = "MANUAL";
    /** Takım silme/taşıma akışında varlıklarla birlikte taşındı (detay: kaynak takım id'si). */
    public static final String TEAM_MOVE = "TEAM_MOVE";

    /** AD'den TÜRETİLEN kaynaklar — AD artık desteklemiyorsa budanabilir olanlar yalnız bunlar. */
    public static final Set<String> LDAP_SOURCES = Set.of(LDAP_GROUP, LDAP_COMPANY);

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "source", length = 20, nullable = false)
    private String source;

    /** İnsan okuyabilir kanıt: grup CN'i, company değeri, "#<takım>" … (en çok 300 karakter). */
    @Column(name = "detail", length = 300)
    private String detail;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    /** Yazan: oturumdaki kullanıcı adı ya da {@code LDAP} / {@code SYSTEM}. */
    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    public UserTeamSource(Long userId, Long teamId, String source, String detail, String updatedAt, String updatedBy) {
        this.userId = userId;
        this.teamId = teamId;
        this.source = source;
        this.detail = detail;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /** Bileşik birincil anahtar — (kullanıcı, takım) başına tek kaynak satırı. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long userId;
        private Long teamId;
    }
}
