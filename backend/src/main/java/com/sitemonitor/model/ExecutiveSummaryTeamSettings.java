package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TAKIM yönetici özeti ayarı (2026-10-10, kullanıcı isteği: "takım bazlı yönetici ayarlaması") — takım başına TEK satır.
 * Satır yoksa takım özeti KAPALI ve varsayılan alıcı seçenekleri geçerlidir (opt-in; kurum özetiyle aynı ilke).
 *
 * <p>Alıcı kaynakları (birleşim, küçük harf tekil, pasif kullanıcı adresi düşer):
 * <ul>
 *   <li>{@code includeManager} — Takım Yönetimi'nde atanmış takım müdürü ({@code teams.manager_id}); varsayılan AÇIK.</li>
 *   <li>{@code includeTeamAdmins} — bu takımı YÖNETEN müdürler (AD kaynaklı kapsamlı yöneticiler, yönetim kapsamında
 *       bu takım olanlar); varsayılan AÇIK.</li>
 *   <li>{@code recipientUserIdsCsv} — seçilen takım üyeleri (kullanıcı kimlikleri, virgüllü). Üye takımdan ayrılınca
 *       gönderimde düşer (satır yeniden yazılmadan).</li>
 *   <li>{@code extraEmails} — ek adresler (virgüllü).</li>
 * </ul>
 * Varlık doğrudan yanıta yazılmaz (servis görünüm haritası kurar; kullanıcı kimlikleri {@code user_id} anahtarıyla
 * {@code UserRefWire} kapısından geçer).
 */
@Entity
@Table(name = "executive_summary_team_settings")
@Data
@NoArgsConstructor
public class ExecutiveSummaryTeamSettings {

    @Id
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "include_manager")
    private Boolean includeManager;

    @Column(name = "include_team_admins")
    private Boolean includeTeamAdmins;

    /** Seçilen takım üyelerinin kullanıcı kimlikleri (virgüllü, sayısal). Yanıta asla yazılmaz. */
    @JsonIgnore
    @Column(name = "recipient_user_ids", columnDefinition = "TEXT")
    private String recipientUserIdsCsv;

    @Column(name = "extra_emails", columnDefinition = "TEXT")
    private String extraEmails;

    @Column(name = "updated_at", length = 40)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
