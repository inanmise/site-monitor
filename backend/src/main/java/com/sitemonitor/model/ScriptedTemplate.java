package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Paylaşılan k6 script şablonu — Sentetik İzleme'nin şablon kütüphanesi.
 *
 * <p><b>Üç katman, TEK ayrım kolonu.</b> {@link #teamId} {@code null} ise şablon GENEL'dir
 * (herkes görür, yalnız global admin düzenler); doluysa o TAKIMINDIR (üyeleri görür ve düzenler).
 * Ayrı bir "scope" enum'u BİLİNÇLİ olarak yok: enum ile takım işaretçisi birbirine düşebilir ve
 * hangisinin doğru olduğu belirsizleşirdi.
 *
 * <p><b>Yerleşikler de burada yaşar.</b> Kod içindeki 11 küratörlü şablon açılışta bu tabloya
 * seed edilir ve {@link #builtinKey} ile işaretlenir. O anahtar üç iş görür: seeder'ın
 * idempotensi, eski {@code tpl:<id>} değerlerinin çözümü (kayıtlı monitörlerin {@code template}
 * kolonunda o dizeler duruyor) ve küratörlü seti admin'in yazdığı Genel şablonlardan ayırmak.
 *
 * <p><b>Şablon ASLA secret DEĞERİ taşımaz.</b> {@link #envJson} yalnız TANIM tutar
 * ({@code [{name, desc, example, secret}]}); kimlik bilgileri monitör kurulurken {@code __ENV}
 * üzerinden verilir. Değer taşıyan bir kayıt API sınırında reddedilir.
 */
@Entity
@Table(
    name = "scripted_templates",
    indexes = {
        @Index(name = "idx_stpl_team_active", columnList = "teamId,active"),
        @Index(name = "uq_stpl_builtin_key",  columnList = "builtinKey", unique = true),
        @Index(name = "idx_stpl_name",        columnList = "name")
    }
)
@Data
@NoArgsConstructor
public class ScriptedTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Birincil ad (kullanıcı tek alan doldurur). */
    @Column(nullable = false)
    private String name;

    /**
     * Kategori ANAHTARI — kütüphaneyi ağaç olarak gruplar ({@code availability}, {@code identity}…).
     *
     * <p>Serbest metin DEĞİL, sabit bir anahtar kümesidir ({@link com.sitemonitor.service.ScriptedTemplateCategories}).
     * Gerekçe: gruplama başlıkları iki dilde gösteriliyor; serbest metin olsaydı her kullanıcı
     * kendi yazımıyla yeni bir dal açar ("Ödeme", "odeme", "Payment") ve ağaç kısa sürede
     * kullanılamaz hâle gelirdi. Anahtar sabit, etiket i18n'den geliyor.
     *
     * <p>{@code null} olabilir: eski kayıtlar ve kategori seçmeyen kullanıcı şablonları
     * arayüzde "Diğer" dalında toplanır — kategori zorunlu tutulup kayıt reddedilmez.
     */
    @Column(length = 40)
    private String category;

    /**
     * İngilizce karşılıklar — YALNIZ seed'lenen 11 yerleşikte dolu.
     * Kullanıcıya çeviri yükü bindirilmez; boşsa tüketici birincil metne düşer.
     */
    private String nameEn;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String descriptionEn;

    /**
     * "Ne zaman kullanılır" — seçici altındaki panelde gösterilir, kullanıcı şablonu
     * yüklemeden önce kendi işine uyup uymadığını anlar.
     *
     * <p>Kolon adı {@code when_to_use}: {@code when} çoğu veritabanında AYRILMIŞ sözcüktür.
     */
    @Column(name = "when_to_use", columnDefinition = "TEXT")
    private String whenToUse;

    @Column(name = "when_to_use_en", columnDefinition = "TEXT")
    private String whenToUseEn;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String script;

    /** Env TANIMLARI: {@code [{name, desc, example, secret}]}. DEĞER alanı YOK ve olmayacak. */
    @Column(columnDefinition = "TEXT")
    private String envJson;

    /** Virgüllü etiket listesi — ScriptedMonitor.tags ile aynı sözleşme (ui/TagInput). */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** {@code null} = GENEL şablon; dolu = o takımın şablonu. Katman ayrımının tek kaynağı. */
    private Long teamId;

    /**
     * Genele açılmış bir şablonun köken takımı — ADI, işaretçisi değil.
     * Takım yeniden adlandırılsa veya silinse bile rozet doğru kalsın diye anlık kopya.
     */
    private String sourceTeamName;

    private String promotedAt;
    private String promotedBy;

    /** Seed edilen küratörlü şablonun anahtarı (ör. {@code smoke-health}); kullanıcı şablonlarında null. */
    @Column(length = 64)
    private String builtinKey;

    /** Bu satırı üreten katalog sürümü — ileride "yukarı akışta yeni sürüm var" raporu için. */
    private Integer builtinSeedVersion;

    /** Soft delete (silinen şablon listeden çıkar, çöp kutusunda admin'e görünür). */
    @Column(nullable = false)
    private Boolean active = true;

    /**
     * "Kim ne zaman sildi" sorusunu {@link #active} tek başına cevaplayamaz — çöp kutusunun
     * asıl vaadi bu iki alandır.
     */
    private String deletedAt;
    private String deletedBy;

    /** Güncel sürüm etiketi ({@code 1.0.0}) — liste/rozet gösterimi sürüm tablosunu sorgulamasın. */
    @Column(length = 20)
    private String currentVersion;

    @Column(nullable = false)
    private String createdAt;

    /** Sicil (ui/UserBadge bunu ada ve AD fotoğrafına çevirir). */
    @Column(nullable = false)
    private String createdBy;

    private String createdByName;

    private String updatedAt;
    private String updatedBy;
    private String updatedByName;
}
