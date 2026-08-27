package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kişi-webhook katman matrisi satırı: TAKIM ya da İZLEME TİPİ başına aç/kapa.
 *
 * <p>AppSettings JSON'u yerine tablo: takım sayısı büyüyebilir, satırlar sorgulanabilir olmalı
 * ve toplu aç/kapa ekranı sayfalama ister. Kayıt YOKSA o kapsam AÇIK sayılır (varsayılan açık;
 * global anahtar zaten varsayılan KAPALI olduğundan çifte emniyet bozulmaz).
 */
@Entity
@Table(name = "user_push_scopes",
        uniqueConstraints = @UniqueConstraint(name = "ux_push_scope",
                columnNames = {"scope_type", "scope_key"}))
@Data
@NoArgsConstructor
public class UserPushScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TEAM / TYPE. */
    @Column(name = "scope_type", nullable = false, length = 10)
    private String scopeType;

    /** TEAM'de takım id'si, TYPE'ta tür anahtarı (cert/http/port/...). */
    @Column(name = "scope_key", nullable = false, length = 40)
    private String scopeKey;

    @Column(nullable = false)
    private Boolean enabled = true;
}
