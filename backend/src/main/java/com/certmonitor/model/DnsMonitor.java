package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dns_monitors")
@Data
@NoArgsConstructor
public class DnsMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String domain;

    @Column(name = "record_type", nullable = false)
    private String recordType;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 300;

    /** true = kullanıcının DNS sayfasından eklediği, sertifika envanterine bağlı OLMAYAN monitör.
     *  Envanter-skip'i baypas eder (her zaman kontrol edilir). null/false = envanter-türevi (otomatik senkron). */
    @Column(name = "standalone")
    private Boolean standalone = false;

    /** Standalone monitörün sorumlu takımı (alarm yönlendirme + liste kapsamı için).
     *  Envanter-türevi monitörlerde null — takım domain→envanter eşlemesinden gelir. */
    @Column(name = "team_id")
    private Long teamId;

    /** Beklenen-değer kilidi (baseline): kullanıcının sabitlediği bilinen-iyi değer(ler), satır (\n) ayrılmış.
     *  Boş = kilit kapalı. Doluyken canlı sonuçta BEKLENMEYEN (bu sette olmayan) değer çıkarsa DNS_UNEXPECTED
     *  alarmı (esnek/hijack-odaklı: beklenenin alt kümesi = rotasyon, alarm üretmez). */
    @Column(name = "expected_value", columnDefinition = "TEXT")
    private String expectedValue;

    /** Çoklu-resolver tutarlılık (propagation) kontrolü açık mı? Opt-in: yalnız true olan monitörlerde
     *  domain birden çok public resolver'a sorulup cevaplar karşılaştırılır (CDN gürültüsünü önlemek için). */
    @Column(name = "propagation_check")
    private Boolean propagationCheck = false;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
