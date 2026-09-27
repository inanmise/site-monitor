package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 7/24 İzleme Ekibi (NOC) yapılandırması (2026-09-27) — TEK satır ({@code id = 1}).
 *
 * <p>Neden AppSettings anahtarı değil: Genel Ayarlar ekranı katalogdan (AppSettingsCatalog) üretiliyor; buraya
 * konan anahtarlar orada ikinci kez, bağlamsız görünür ve kendi "7/24 İzleme Ekibi" bölümüyle çakışırdı. Tek
 * satır + özel uç ({@code /api/admin/noc/config}) yalnız global yöneticiye yazdırılır.
 *
 * <p>Tür anahtarları KAPALI olanlar olarak saklanır: yeni bir izleme türü eklendiğinde varsayılanı AÇIK doğar.
 */
@Entity
@Table(name = "noc_settings")
@Getter @Setter @NoArgsConstructor
public class NocSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** NOC bildirimi KAPATILMIŞ tür anahtarları (virgüllü, ör. "PING,DNS"); boş = hepsi açık. */
    @Column(name = "disabled_types", length = 200)
    private String disabledTypes;

    /** WARNING | HIGH | CRITICAL (null = CRITICAL). */
    @Column(name = "min_level", length = 16)
    private String minLevel;

    /** Çözülünce "ÇÖZÜLDÜ" e-postası (null = açık). */
    @Column(name = "send_resolve")
    private Boolean sendResolve;

    /** NOC'a serbest metin arama talimatı — e-postaya KAÇIRILARAK girer. */
    @Column(name = "call_instructions", columnDefinition = "TEXT")
    private String callInstructions;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;
}
