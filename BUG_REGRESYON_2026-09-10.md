# BUG REGRESYON — 2026-09-10 (sürüm öncesi, diff-odaklı)

Kapsam: `3f2fd87a..HEAD` (v20.51.1 sonrası iki commit: Ayarlar kapısı `global_admin`, görev havuzu
canlı ayar). Tam kod-tabanı süpürmesi değil; sürüm makrosunun "önce /bug-regresyon" adımı bu
diff'in imza sınıflarına karşı koşuldu.

## (A) Baseline re-check

Değişen 25 dosyanın hiçbiri önceki dört turun bulgu alanlarına girmiyor: `controller/` (S1 IDOR,
S13 izin kataloğu, S16 SQL), `repository/` (S9 tx), mail şablonları (S17), checker/HTTP istemcileri
(S2/S3/S4 SSRF-gövde), outbox (S15), eskalasyon (S14). Bilinen düzeltmelerin imzaları bu sürümde
dokunulmadan kaldı → **REGRESYON YOK** (dosya kümesi kesişimi boş).

## (B) Benzer-tarama (diff üzerinde)

| Sınıf | Arama | Sonuç |
|---|---|---|
| S2/S4 redirect & SsrfGuard | `Redirect.NORMAL`, `openConnection`, `new Socket` | eşleşme yok (yeni kod dış istek yapmıyor) |
| S3 sınırsız gövde | `readAllBytes`, `BodyHandlers.of*` | eşleşme yok |
| S9 tx'siz yazım | `deleteBy`, `@Modifying` | eşleşme yok; `AppSettingsService.save` mevcut `@Transactional` altında |
| S10 Boolean unbox | `!x.getFlag()` | eşleşme yok |
| S11 fetch yarışı | `GeneralSettings` yalnız grup açıklaması eklendi; yükleme yolu değişmedi | temiz |
| S12 türetilmiş state | `useState(prop)` | eşleşme yok |
| S8 entity/şema | yeni `@Column` yok (ayarlar `app_settings` satırı) | temiz |

Yeni kodun kendi riskleri okunarak doğrulandı:
- `TunableThreadPoolTaskExecutor.applyPoolSizes` sıra-güvenli (JDK `max<core` kuralı iki yolda da
  ihlal edilmez; büyütme/küçültme testli).
- `AppSettingsService.save` olayı commit'ten ÖNCE yayınlar; commit düşerse cache/executor DB'den
  sapar ama 10 sn'lik `refreshFromDb` farkı görüp yeniden olay yayınlar → kendi kendini onarır.
- `ResizableCapacityQueue.offer` boyut denetimi kilitsiz; tavan thread sayısı kadar aşılabilir —
  geri-basınç amaçlı, sınıf yorumunda belgeli.

## Temiz sınıflar
S1, S2, S3, S4, S5, S6, S7, S8, S9, S10, S11, S12, S13, S14, S15, S16, S17 — bu diff'te örnek yok.

## Sonuç
Tüm düzeltmeler tutmuş; imzaların hiçbiri bu sürümün diff'inde tekrar etmiyor. Sürüme engel yok.

## Ek — ikinci tur (aynı gün, `d413a94a..HEAD`)

Kapsam: sunucu mesajlarının arayüz dilini izlemesi (`X-Lang` + `Msg.t`, 12 dosyada 48 dize) ve
Sistem Sağlığı kart CSS'i. Eklenen satırlarda S2/S3/S4/S9/S10/S11/S12 imzalarından hiçbiri yok.
Dokuz ayar denetleyicisinde `Msg.t` dışında kalan Türkçe kullanıcı mesajı kalmadı (grep boş).
Baseline dosyaları bu diff'te değişmedi → **REGRESYON YOK**. Sürüme engel yok.

## Ek — üçüncü tur (aynı gün, `dc80c7c0..HEAD`)

Kapsam: kapsamlı müdürün Ayarlar erişimi (seçenek B). Bu tur bilinçli bir kapı GEVŞETMESİ olduğundan
S1 (yetki atlaması) imzası özel olarak tarandı: kapısı kaldırılan dört denetleyicide `permissionService.
require` duruyor; UserPush `isScopedAdmin` ile daraltıldı; dört sır yüzeyi (SMTP/LDAP/Secret/DB)
`requireNotScopedAdmin` taşıyor; `GLOBAL_ONLY` 16 anahtar `AppSettingsService.save`'de tek kapı.
Eklenen satırlarda S2/S3/S4/S9/S10/S11/S12 imzası yok. Kapı: `SettingsScopedAdminGateTest`.
v20.50.29 bulgusu KAPALI kalır (yüzeyi "tüm ayarlar"dan "sır yüzeyleri + riskli anahtarlar"a
daraltıldı; bkz. CLAUDE.md). Sürüme engel yok.
