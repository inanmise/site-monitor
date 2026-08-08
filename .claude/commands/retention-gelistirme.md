---
description: Site Monitor retention altyapısını bildirimsel bir politika kaydına (RetentionPolicy registry) taşır — dry-run raporu, politika-senkron bekçi testi, unutulmuş tabloların kapatılması, saatlik rollup katmanı ve ham seriyi kademeli kısaltma. Sıra tersinirliğe göredir: önce ölç, sonra koru, en son kısalt.
argument-hint: [olc|registry|bosluk|rollup|kisalt] (opsiyonel — boş bırakılırsa Faz 0'dan 6'ya sırayla)
---

# /retention-gelistirme — Retention'ı Bildirimsel Politikaya Taşıma

**Mevcut durum (bu komut yazılırken koddan doğrulandı — sıfırdan kurma, üstüne kur):**
`SchedulerService.cleanupOldLogs` (cron `0 30 3`, `nightly-cleanup` dağıtık kilidi, TTL 60dk) bugün
~25 tabloyu temizliyor; `rollupDailyStats` → `monitor_check_daily` (7 tür, idempotent upsert) purge'den
ÖNCE koşuyor; `safeDeleteBatched` 10k dilimli siliyor; `DbGrowthMetrics` `db_table_rows/bytes` yayınlıyor;
`AppSettingsCatalog`'da "retention" kategorisi var. Zayıf nokta mekanizma değil, **biçim**: temizlik
~150 satır elle yazılmış imperatif DELETE bloğu. Yeni bir tür/tablo eklendiğinde buraya satır eklemeyi
unutmak sessiz ve maliyetlidir — `http_checks`, `page_*`, `scripted_checks`, `login_issue_*` hep sonradan
"hiç temizlenmiyormuş" diye eklenmiş; `incident_images` hâlâ eklenmemiş.

**Yöntem ilkesi (bağlayıcı): önce ölç → sonra koru → en son kısalt.** Ham veri silmek geri alınamaz;
bu yüzden fazlar özellik değerine göre değil **tersinirliğe** göre sıralanmıştır. Kısaltma fazı (5),
rollup doğrulama kanıtı (4) olmadan ASLA çalıştırılmaz.

Kapsam argümanı: `$ARGUMENTS` — boş: Faz 0–6; `olc`: 0; `registry`: 1–2; `bosluk`: 3;
`rollup`: 4; `kisalt`: 5. Faz 6 her koşuda kısa tutulur.

## Değişmez kurallar

1. **Hiçbir fazda mevcut politikadan daha agresif silme devreye alınmaz** — Faz 5 hariç, o da dry-run
   kanıtı + kullanıcı onayıyla ve kademeli (180→90→45).
2. **Açık kayıt asla silinmez:** açık/ack'li `alert_events`, RESOLVED olmayan `incident_records` ve
   `login_issue_reports`, baseline satırları (`dns_records`, `domain_checks` — mevcut guard'lar korunur).
3. Yeni kolon/tablo → `applySchemaPatches()`'e `patch()` satırı (ddl-auto'ya güvenme); yeni ayar →
   `AppSettingsCatalog` "retention" kategorisi; yeni i18n anahtarı TR+EN birlikte.
4. Yeni rollup/series kodu **mevcut huniyi kopyalar** (`rollupUpsert` deseni) — ham `Object[]`
   dönüşü yasak (bkz. proje hafızası `chart_endpoint_dersleri.md`, Sentetik İzleme regresyonu).
5. Coverage floor'ları düşürülmez; `TESTING.md`/`docs/db-scaling.md` geçersizleşirse aynı değişiklikte
   güncellenir; commit atılmaz. Ortam: `JAVA_HOME=C:\Program Files\Zulu\zulu-25`,
   Maven `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`.

## Faz 0 — Ölç (hiçbir şey silmeden)

- Baseline: `mvn -B clean verify` + `npm run test:coverage` yeşil; `git status --short` kaydet.
- **Dry-run raporu:** her retention kuralı için "bugün kaç satır silinirdi, tablo kaç satır/kaç MB,
  en eski kayıt hangi tarihte" — silmeden hesaplayan bir servis metodu + admin ucu. Çıktı hem log
  hem `Sistem Sağlığı → Veritabanı` ekranında. Bu rapor, Faz 5'in karar dayanağıdır.
- **Kullanım telemetrisi (retention süresini hisle değil veriyle seçmek için):** grafik preset
  kullanımını (24h/7d/30d/90d/custom) ve audit/aktivite ekranlarında sorgulanan tarih aralığının
  yaşını say (mevcut `AuditService`/`HttpMetricsService` desenine bağla, kişisel veri ekleme).
  En az 2 hafta veri biriksin — Faz 5 bunu okur.
- Faz 0 çıktısı rapora: en hızlı büyüyen 10 tablo + projeksiyon (satır/gün × 90 gün).

## Faz 1 — Politika kaydı (registry) — mimari çekirdek

İmperatif DELETE bloğunu **bildirimsel bir listeye** çevir: `RetentionPolicy` kaydı
(`record RetentionPolicy(String table, String timeColumn, String settingKey, int defaultDays,
int minDays, String guardSql, boolean batched, DataClass dataClass, String rationale)`) ve
`RetentionCatalog.ALL` — `PermissionCatalog` ile aynı "tek doğruluk kaynağı" deseni.

`cleanupOldLogs` bu listeyi dolaşır; özel durumlar (FK sırası, baseline guard'ı, öksüz temizliği,
`incident` opt-in kapalılığı) kaydın alanlarına taşınır — davranış birebir korunur, yalnız biçim değişir.
Bu dönüşüm **davranış-nötr** olmalı: dönüşüm öncesi/sonrası aynı sentetik veri üzerinde silinen satır
sayıları eşit çıkmalı (karşılaştırmalı test yaz).

Kazançlar tek kaynaktan gelir: dry-run raporu, `Sistem Sağlığı` ekranı, Prometheus metrikleri, bekçi
testi ve `docs/RETENTION_POLITIKASI.md` üretimi hep aynı listeden beslenir.

## Faz 2 — Bekçi testi + politika dokümanı

- **Bekçi testi:** `@Entity` sınıflarını (veya `information_schema` üzerinden tabloları) tara; her
  büyüyen fact tablosunun `RetentionCatalog`'da bir satırı olmalı. Politika satırı olmayan tablo →
  test FAIL. İstisna listesi kod içinde **gerekçeli** tutulur (küçük/sınırlı tablolar: `teams`,
  `app_settings`, `latest_check`, `permission_grants` vb.). Bu test, "yeni tür eklendi, temizlik
  unutuldu" hata sınıfını kalıcı olarak kapatır.
- **`docs/RETENTION_POLITIKASI.md`** registry'den üretilir (elle yazılmaz — sapma imkânsız olsun):
  tablo × veri sınıfı × süre × ayar anahtarı × silme kuralı/guard × gerekçe. Dokümanın başına
  **uyum onayı** bölümü: kişisel veri içeren tablolar (`audit_log`, `activity_log`,
  `login_issue_reports`, `login_anomaly_incident`) için süreler uyum/hukuk biriminden teyitli mi,
  kim onayladı, ne zaman. KVKK tarafında saklama-imha politikası ve **6 aylık periyodik imha**
  ritmi; bankacılık tarafında BDDK/iç denetim süreleri — varsayılanları değil onaylı süreyi esas al.
  Ayrıca **legal hold** kaçış valfi: `site.monitor.retention.hold-enabled` açıkken temizlik
  komple durur ve her gece WARN log + audit event yazar (soruşturma/denetim senaryosu).
- `docs/db-scaling.md` düzeltmesi: §2 ayar tablosundaki anahtarlar hâlâ `cert.monitor.*` yazıyor,
  kod `site.monitor.*` okuyor → dokümanı izleyen ops yanlış anahtarı set eder, sessizce varsayılana
  düşer. Tabloyu registry'den üret, rename kalıntısını kapat.

## Faz 3 — Unutulmuş tabloları kapat (koruyucu, veri kaybettirmez)

Faz 0 ölçümüyle önceliklendir; adaylar (kodda doğrulandı):
- **`incident_images`** — en riskli: `incident.retention-days` varsayılanı 0 (hiç silme) olduğundan
  base64 görseller (satır başına MB'lar) sonsuza kadar duruyor. `weekly_report_images` deseni:
  **olay metnini koru, yalnız çok eski görseli sil** (ayrı ayar, muhafazakâr varsayılan 730g).
- **`alert_comments`** — 365g'den eski çözülmüş `alert_events` silinince yorumlar öksüz kalıyor mu?
  (`login_issue_mail_logs` için öksüz temizliği var, burada yok.) FK/öksüz durumunu doğrula, gerekiyorsa
  aynı deseni uygula.
- **`password_history`**, **`certificate_note_revisions`** — sınırsız büyüyen, hiç temizlenmeyen
  tablolar. İlki için "kullanıcı başına son N kayıt" (parola tekrar kontrolü kaç geriye bakıyorsa
  o kadar), ikincisi için gün-bazlı retention.
- **`spring_session` / `spring_session_attributes`** — prod'da JDBC store; Spring'in kendi cleanup
  job'ının çalıştığını DOĞRULA (süresi dolmuş satır sayısı 0'a yakın mı). Çalışmıyorsa registry'ye al.
- **Hardcoded kesimleri ayara çevir:** `notification_logs` 90g, `sql_query_history` 30g, ts serisi
  180g, `system_heartbeat` 30g, `diagnostic_runs` 90g, `alert_events` 365g, `login_issue_*` 365g
  bugün koda gömülü. Politika matrisi bunları da kapsıyorsa hepsi `settingKey` almalı (registry
  dönüşümü bunu zaten doğal kılar).

Her yeni kural için: `patch()` gerekiyorsa ekle, dry-run'da görünsün, testi yaz.

## Faz 4 — Saatlik rollup katmanı (kısaltmanın ön koşulu)

`monitor_check_daily`'nin yanına `monitor_check_hourly` (aynı şema + `hour` kolonu, aynı
`idx_..._type_key_hour` indexi, aynı idempotent upsert). `rollupUpsert` birebir aynı huniden geçer;
7 tür (PORT/PING/KEYWORD/HTTP/PAGE/SCRIPTED/UPTIME) aynı anda beslenir. Retention: 90–180 gün
(`site.monitor.rollup.hourly-retention-days`), günlük rollup 730g olarak kalır.

Neden zorunlu: ham seri 180g→45g'ye indiğinde 45–180 gün arası olayların dakika/saat çözünürlüğü
tamamen kaybolur; saatlik kova bu boşluğu kapatır ve maliyeti ihmal edilebilirdir (100 monitör ×
24 satır/gün ≈ 876k satır/yıl, ham serinin ~%1,7'si).

**Rollup doğrulama testi (Faz 5'in kanıtı):** aynı gün için ham satırlardan hesaplanan
`avg/max/total/up` değerleri ile rollup satırı eşleşiyor mu — tolerans içinde tutmuyorsa kısaltma
YAPILMAZ. Bu testi hem birim (sentetik veri) hem prod-benzeri veriyle koş.

## Faz 5 — Kademeli kısaltma (geri alınamaz — onay + kanıt zorunlu)

Ön koşullar: Faz 4 doğrulama testi yeşil, saatlik rollup en az 1 hafta prod verisi biriktirmiş,
Faz 0 kullanım telemetrisi "X günden eskiye pratikte bakılmıyor" diyor, dry-run raporu sunuldu,
**kullanıcı açıkça onayladı**, güncel yedek var.

Kademe: `180g → 90g` (2–4 hafta gözlem: kimse eski veriyi aramıyor mu, ekranlar rollup'a düşüyor mu)
→ `90g → 45g`. Her kademede `db_table_rows` düşüşü, gece purge süresi ve ekran davranışı raporlanır.
Ham seriyi kısaltmak `tsCutoff`'u tek yerden (registry) besler — tür bazında farklılaştırma gerekirse
kayıt bazında ayarlanır (ör. `scripted_checks` daha uzun, `keyword_results` daha kısa).

## Faz 6 — Otomasyon sağlığı ve ritim

- **Kendi temizliğini izle** (ironi: izleme ürünü kendi cleanup'ını izlemiyor): "gece temizliği
  N saattir çalışmadı" sağlık sinyali (`ExtendedHealthService`'e ekle, `Sistem Sağlığı` ekranında
  görünsün) + silinen satır sayısı metrikleri (`retention_deleted_rows{table=...}`) + purge süresi.
  Sessizce çöken bir cleanup, diski dolduğunda değil ertesi sabah fark edilmeli.
- `site.monitor.db.growth-warn-rows` eşiği aşıldığında WARN zaten var — bunu sağlık ekranına ve
  (opsiyonel) admin mailine bağla.
- **6 aylık gözden geçirme** (KVKK periyodik imha ritmiyle aynı takvim): politika matrisi + gerçekleşen
  büyüme + kullanım telemetrisi + mevzuat değişikliği birlikte gözden geçirilir; `docs/RETENTION_POLITIKASI.md`
  yeniden üretilir ve onay satırı tazelenir.
- **Ölçek tetikleyicisi (şimdi yapma, eşiği yaz):** bir tablo >50M satıra veya gece purge >20dk'ya
  çıkarsa aylık **declarative partitioning**'e geç (`DROP PARTITION` anlık, bloat/autovacuum yükü yok).
  Eşik ve geçiş planı dokümana yazılır.

## Testler (fazlara dağıtılmış, kapanışta topluca)

Registry dönüşümünün davranış-nötrlüğü (öncesi/sonrası silinen satır eşitliği); politika-senkron bekçi
testi (istisna listesi gerekçeli); her yeni kural için guard testi (açık kayıt/baseline silinmiyor);
`incident_images` için "metin korunur, görsel silinir"; öksüz temizliği; legal-hold açıkken HİÇBİR
silme olmadığı; saatlik rollup idempotentliği + ham↔rollup tutarlılığı; dry-run'ın hiçbir satır
silmediği (en kritik test — yanlışlıkla gerçek silme yapan bir dry-run felakettir).
Kapanış: `mvn -B clean verify` + `npm run test:coverage` yeşil, floor'lar korunur/yükseltilir.

## Final rapor

Faz × sonuç × kanıt; registry'ye taşınan kural sayısı ve davranış-nötrlük kanıtı; kapatılan boşluklar
(tablo × önce/sonra büyüme); üretilen `docs/RETENTION_POLITIKASI.md` ve uyum onayı bekleyen satırlar;
saatlik rollup doğrulama sonuçları; kısaltma yapıldıysa kademe kademe ölçümler, yapılmadıysa ön koşul
durumu; kalan öneriler (partitioning eşiği, arşiv formatı). Commit `feat:`/`refactor:` önerisiyle
kullanıcı onayına.
