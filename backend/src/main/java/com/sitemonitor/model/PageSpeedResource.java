package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bir ölçümdeki TEK kaynağın ağırlığı — "sayfa neden yavaşladı" sorusunun cevabı.
 *
 * <p><b>Neden her kontrolde saklanmıyor:</b> 100 sayfa x 500 kaynak x yarım saatte bir kontrol günde
 * milyonlarca satır eder; tek pod'da bu tablo hem diski hem sorguları boğar. Bunun yerine iki sebeple
 * saklanır ({@link #keepReason}):
 * <ul>
 *   <li>{@code LATEST} — SON ölçümün kırılımı. Her kontrolde silinip yeniden yazılır, yani satır sayısı
 *       izleme sayısıyla orantılı kalır, kontrol sayısıyla DEĞİL.</li>
 *   <li>{@code BREACH} — bir eşik aşıldığı ANIN kırılımı, delil olarak dondurulur. Böylece "geçen salı
 *       neden yavaşladı" sorusu sonradan da cevaplanabilir.</li>
 * </ul>
 * Retention yalnız {@code BREACH} satırlarına uygulanır; {@code LATEST} zaten üzerine yazılıyor.
 */
@Entity
@Table(name = "pagespeed_resources",
       indexes = {
           @Index(name = "idx_psr_monitor_reason", columnList = "monitor_id,keep_reason"),
           @Index(name = "idx_psr_check", columnList = "check_id")
       })
@Data
@NoArgsConstructor
public class PageSpeedResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    /** Hangi ölçüme ait ({@link PageSpeedCheck#getId()}). */
    @Column(name = "check_id")
    private Long checkId;

    @Column(name = "checked_at", nullable = false, length = 30)
    private String checkedAt;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String url;

    /** IMG / CSS / JS / IFRAME / FONT / FAVICON / OTHER — envanterden gelen tür. */
    @Column(length = 20)
    private String type;

    /** Transfer edilen bayt. {@link #truncated} true ise bu değer ALT SINIRDIR. */
    private Long bytes;

    /** Okuma bayt tavanında kesildi mi — satır "≥ 10 MB" olarak gösterilir.
     *  nullable: tablo oluştuktan SONRA eklendi (bkz. PageSpeedCheck.bytesTruncated). */
    @Column
    private Boolean truncated = false;

    /** Bu kaynağın alınması ne kadar sürdü (ms). */
    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "status_code")
    private Integer statusCode;

    /** Farklı kayıtlı domain'den mi geliyor (PSL) — "ağırlığın ne kadarı bizim" sorusu. */
    @Column(name = "third_party")
    private Boolean thirdParty = false;

    /** LATEST (son ölçüm, üzerine yazılır) | BREACH (eşik ihlali anı, kalıcı delil). */
    @Column(name = "keep_reason", length = 10, nullable = false)
    private String keepReason = KEEP_LATEST;

    public static final String KEEP_LATEST = "LATEST";
    public static final String KEEP_BREACH = "BREACH";
}
