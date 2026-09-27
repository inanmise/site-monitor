package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 7/24 İzleme Ekibi (NOC) ARAMA KAYDI (2026-09-27): NOC bir uyarı için sahibi takımdan nöbetçiyi aradığında
 * uyarının ÜZERİNDEN girdiği satır — KİM arandı, NE ZAMAN, hangi KANALDAN, SONUÇ ve NOT.
 *
 * <p><b>Telefon YAZILMAZ.</b> Aranan kişi bir kullanıcıysa {@code contactedUserId} + o anki görünen adı
 * ({@code contactedName}, anlık görüntü) saklanır; serbest metin yedeğinde yalnız ad. Numara AD'de durur ve yalnız
 * NOC e-postasına girer.
 *
 * <p><b>Kolonların HEPSİ NULLABLE</b> (proje kuralı: dolu tabloya NOT NULL / tekil kısıt ddl-auto ile SESSİZCE
 * oluşmayabilir). Zorunluluklar ({@code outcome}, kişi) {@code NocCallLogService}'te doğrulanır; indeksler
 * {@code NocCallLogSchemaPatch}'te açıkça da kurulur. Zamanlar proje biçiminde UTC ISO ({@code yyyy-MM-dd'T'HH:mm:ss}).
 * İndeks adları {@code noc_team_call_list}'inkilerden AYRI ({@code idx_noc_call_team} orada kullanılıyor —
 * PostgreSQL'de indeks adı şema genelinde tekildir).
 */
@Entity
@Table(name = "noc_call_log",
       indexes = {
           @Index(name = "idx_noc_call_log_alert", columnList = "alert_id,contacted_at"),
           @Index(name = "idx_noc_call_log_team",  columnList = "team_id,contacted_at")
       })
@Getter @Setter @NoArgsConstructor
public class NocCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Uyarı geçmişi satırı ({@code alert_events.id}). */
    @Column(name = "alert_id")
    private Long alertId;

    /** Uyarının takımı (damgalı teamId; yoksa envanter SY, o da yoksa UG) — yazma anında çözülür. */
    @Column(name = "team_id")
    private Long teamId;

    /** Aranan kullanıcı (seçiciden); serbest metinle girilen kişide null. */
    @Column(name = "contacted_user_id")
    private Long contactedUserId;

    /** Aranan kişinin adı — kullanıcıda o anki görünen ad, serbest metinde girilen ad. TELEFON DEĞİL. */
    @Column(name = "contacted_name", length = 200)
    private String contactedName;

    /** Aramanın yapıldığı an (UTC ISO). Varsayılan: kayıt anı; gelecek olamaz. */
    @Column(name = "contacted_at", length = 30)
    private String contactedAt;

    /** PHONE | SMS | TEAMS | EMAIL (varsayılan PHONE). */
    @Column(name = "channel", length = 16)
    private String channel;

    /** REACHED | NO_ANSWER | VOICEMAIL | BUSY | WRONG_NUMBER | ESCALATED. */
    @Column(name = "outcome", length = 20)
    private String outcome;

    /** 0..1000 karakter; kontrol karakterleri ayıklanmış. */
    @Column(name = "note", length = 1000)
    private String note;

    /** Kaydı giren kullanıcı adı — silme penceresinin sahiplik anahtarı. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at", length = 30)
    private String createdAt;
}
