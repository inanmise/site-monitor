---
description: SiteMonitor'ü tek turda uçtan uca kod-seviyesi bug denetimi — üç ayrı denetim turunun (eşzamanlılık/sızıntı · güvenlik IDOR/SSRF/yetki · veri katmanı · sayısal-zaman hesabı · alarm/eskalasyon tutarlılığı · React state/render) TAMAMINI paralel ajanlarla aynı anda koşar, her YÜKSEK/ORTA bulguyu kaynakta dosya:satır ile doğrular, bilinen-sağlam desenleri yanlış-pozitif olarak eler, önem sıralı ve çözümlü tek rapor üretir.
argument-hint: [hizli|derin|frontend|backend|güvenlik] (opsiyonel — boş = TAM 6-eksen denetim)
---

# /bug-denetle — Tek Turda Uçtan Uca Bug Denetimi

Görevin: SiteMonitor kod tabanını (`backend/src` ~550 Java + `frontend/src` ~360 JS/JSX) tek
oturumda uçtan uca denetleyip **kod-seviyesi gerçek bug'ları** önem sırasına göre, dosya:satır
kanıtı ve somut çözümüyle raporlamak. Bu komut, daha önce ÜÇ ayrı turda koşulan denetimin
(eşzamanlılık, güvenlik, veri, hesap, alarm, React) tamamını **aynı turda paralel** yürütür.

Çıktı bir **rapor**tur — kod değiştirmezsin (kullanıcı ayrıca istemedikçe). Amacın kapsam ve
doğruluk: spekülasyon değil, kaynağı OKUYARAK doğrulanmış bulgular. Yüzeysel/aceleci tarama
kabul edilmez.

Kapsam argümanı: `$ARGUMENTS`
- Boş → TAM: 6 eksenin tamamı paralel.
- `hizli` → yalnız YÜKSEK-olasılıklı eksenler (güvenlik + alarm + veri), tek tur asgari doğrulama.
- `backend` → E1–E5 (React hariç). `frontend` → E6 (React) + E4'ün frontend kısmı.
- `güvenlik` → yalnız E2 (IDOR/SSRF/yetki) derinlemesine.

## Değişmez kurallar (her denetimde geçerli)

1. **Kaynağı konteynere al, orada tara.** device_bash mount üzerinde 550+360 dosyayı ajanlarla
   taramak yavaş ve kırılgan. Önce `tar czf _review_src.tar.gz backend/src frontend/src
   frontend/package.json backend/pom.xml CLAUDE.md`, `device_stage_files` ile stage et, konteynerde
   `/tmp/sm`'e aç (`tar xzf … -C /tmp/sm`). Ajanlar `/tmp/sm` üzerinde `Grep`/`Read` ile çalışır.
   Bittiğinde konteyner arşivini ve device'taki `_review_src.tar.gz`'yi temizle.
2. **Paralel ajanlar, tek mesajda.** Eksenleri BAĞIMSIZ ajanlara böl ve HEPSİNİ tek `Agent`
   çağrı bloğunda başlat (eşzamanlı koşsunlar). Her ajan: kendi ekseninde en fazla ~12 bulgu,
   dosya:satır kanıtı, "kodu okuyarak doğrula, yanlış pozitif verme" talimatı, ve §Yanlış-pozitif
   listesi + §Bilinen bulgular baseline'ı prompt'a gömülü.
3. **Doğrulama zorunlu — ajana körlemesine güvenme.** Ana ajan (sen) her **YÜKSEK ve ORTA**
   bulguyu kaynakta AÇIP okur; dosya:satır, çağrı zinciri ve somut senaryo teyit edilmeden rapora
   YÜKSEK/ORTA olarak girmez. Teyit edilemeyen → DÜŞÜK + "doğrulanmalı". Kardeş-karşılaştırma en
   güçlü kanıttır ("7 uç `denyIfNotViewable` kullanıyor, bu ikisi kullanmıyor").
4. **Bilinen bulguları RE-CHECK et, körü körüne tekrarlama.** `project_memory_read
   bug_denetimi_2026_08.md` ile önceki turların bulgularını (Y1–Y9, O-serisi) OKU. Her biri için:
   koddaki satır hâlâ kusurlu mu? (a) Düzeltilmişse rapora "ÇÖZÜLMÜŞ ✓" olarak yaz (kullanıcı
   ilerlemeyi görsün). (b) Hâlâ açıksa "AÇIK" olarak taşı. (c) YENİ bulgular ayrı işaretlenir.
   Bu, komutu her koşuşta değer üretir kılar (aynı listeyi basmaz).
5. **Önem eşiği tutarlı:** **KRİTİK** (RCE / kimlik-doğrulama bypass / veri kaybı / retention'ın
   yanlış veriyi silmesi) · **YÜKSEK** (takım-izolasyon ihlali/IDOR, SSRF→metadata, yetki
   yükseltme, yanlış/eksik alarm, OOM/DoS, kullanıcı-görünür işlevsel kırılma) · **ORTA** (yanlış
   gösterim/hesap, dar yarış, kaynak sızıntısı) · **DÜŞÜK** (latent, kozmetik, perf, "doğrulanmalı").
6. **Gizlilik:** rapor ve loglarda gerçek sicil/kurum adı geçmez (CLAUDE.md kuralı). Rapor
   Türkçe; her bulgu `dosya:satır — <bug> | Neden bug: <somut senaryo> | Çözüm: <düzeltme>`.
7. Ortam Windows; kod DEĞİŞTİRİLMEZ (yalnız okuma + rapor). Rapor `D:\site-monitor\`'a yazılır
   (SendUserFile + device_commit_files); bellek güncellenir.

## Eksenler (paralel ajanlar) — TAM kapsamda 6'sı birden

**E1 — Eşzamanlılık, zamanlayıcı, kaynak sızıntısı.** executor kapatma; dağıtık kilit
fail-open (geçici DB hatasında `true` dönme → çift sweep); her çağrıda yeni/kapatılmayan
`HttpClient` (SelectorManager thread + FD sızıntısı); soket/stream `close()` asimetrisi (hata
yolunda kapatmama); `ConcurrentHashMap.clear()` kilit yarışı; sınırsız in-memory retry kuyruğu
(OOM); cache coherency. Dosyalar: SchedulerService, *CheckerService, WebhookService,
UserPushService, CaAutoPinService.

**E2 — Güvenlik: IDOR + SSRF + yetki mantığı.** (a) IDOR: `@GetMapping`/`@PostMapping` uçlarında
`permissionService.require` VAR ama `SessionScope.canView/canManage`/`denyIfNotViewable`/
`canOperateTeam` ATLANMIŞ olanlar; `existsById` ile takım kontrolü baypası; kardeş uçlarla
karşılaştır. Yazma-tarafı nesne-yetki boşlukları (transfer/bulk uçları). (b) SSRF: `Redirect.NORMAL`
+ yalnız ilk-host `ssrfGuard.validate` (redirect ile metadata/loopback); SsrfGuard'ı hiç
çağırmayan fetch/uptime/webhook yolları; sınırsız yanıt gövdesi okuma. (c) Yetki mantığı:
`PermissionCatalog` rol default'larında tek-satır `List.of(VIEW, EDIT)` → `auditDefaults` putAll ile
salt-okunur role EDIT sızması; `mustChangePassword` kısıtlı-yol genişlemesi; `systemRole` string
eşitlik hataları; grandfathered `activeSessionId=null`.

**E3 — Veri katmanı ve bütünlük.** `@Transactional` eksik yazan/`deleteBy…` metodlar
(RepositoryWriteTransactionGuardTest ihlali); N+1 (liste `enrich*` döngüsünde repo çağrısı);
entity `@Column` ↔ `applySchemaPatches()` ADD COLUMN tutarsızlığı (eski DB'de kolon yok);
`update*` metodunda **setter unutma** (alan kaydeder görünüp reload'da kaybolur — envanter alan
ekleme tuzağı); DB unique kısıt yokluğu → eşzamanlı çift POST mükerreri; dedupe case/trim
tutarsızlığı; retention yanlış tablo/kolon/tarih.

**E4 — Sayısal/istatistik + zaman-penceresi hesabı.** sıfıra bölme (`/count` kontrolsüz); tamsayı
bölmesi kaybı (`/10L` vs `/10.0`, `int/int`); percentile/histogram off-by-one + boş liste; yüzde
>100/negatif; çift sayım; sayaç/liste sınır asimetrisi (bir yer `d<=x`, diğeri `d>=0 && d<=x`);
confirm/recovery sayacı `<` vs `<=`; `reAlertDue` interval; bakım penceresi çakışma/gece-yarısı;
cron next-run; ISO string karşılaştırmasının leksikografik doğruluğu; süre farkında UTC/yerel
karışımı; eşik gün sınırı hangi tarafta.

**E5 — Alarm/eskalasyon tutarlılığı.** storm alıcı çözümü ↔ bireysel alıcı çözümü ayrışması
(bir yol team-only sayarken diğeri müdür ekliyor); alarm seviyesinin event'e
kalıcılaştırılmaması → çözüm/re-notify bayat seviyeyle alıcı çözer; üç yolun (ilk/çözüm/yeniden-
gönder) `event.getTeamId()` ile aynı takımı çözmesi; mail HTML+düz-metin paritesi + `esc()`
eksik (XSS/null "null" yazımı); dedupe (UNIQUE) ve fırtına/bakım bastırma paritesi.

**E6 — React state/efekt/render.** fetch-yarışı (`seq`/`alive` guard eksikliği → bayat yanıt
ezme); bayat closure (setInterval/handler eski state); useEffect eksik/fazla bağımlılık;
interval/subscription/AbortController temizlenmemesi; liste `key={i}` veya çakışan key (odak/state
karışması); türetilmiş-state `useState(prop)` prop değişince güncellenmez; sunucu-sayfalamada
0-tabanlı state ↔ 1-tabanlı `PaginationBar` dönüşüm hatası; dış veri `typeof s?.ts==='string'`
guard eksikliği (chart çökmesi); tarih biçimlemede UTC/yerel (zone-eksiz string → yerel).

## Yanlış-pozitif listesi (RAPOR ETME — kod bunları BİLİNÇLE doğru yapıyor)

Ajan prompt'larına gömülür; bu desenler görülünce "temiz" sayılır, bulgu üretilmez:
`response-series` daima `buildResponseSeries` hunisinden geçer + chart'lar `typeof ts==='string'`
savunmalı; ErrorBoundary çökmeleri `/api/client-error-report`'a bildirilir; executor'lar
`@PreDestroy`'da kapatılır; `RepositoryWriteTransactionGuardTest` allow-list'i geçerli;
`enrich*` metodları `teamNameMap()` toplu-harita kullanır (N+1 yok); retention UTC ISO
leksikografik doğru + `minDays` tabanı zorlu; `HttpMetricsQueryService.percentile` kova-içi
interpolasyon + `total==0` korumalı; `MonitoringOutageService` teyit=3→3 re-check, recovery=3→
tetikleyici+2; `MaintenanceService` occurrence yarı-açık + gece-yarısı geçişi doğru;
`useVisibleInterval`/`useRunningChecks`/`usePagination`/`Toast`/`CopyButton` timer temizlikleri
doğru; `CertificateHealthRules`/`PageSpeedRules` eşikleri kesin. Bu listedeki bir davranışı
"bug" diye raporlayan ajan çıktısı ELENİR.

## Akış

**Faz 0 — Hazırlık.** Kaynağı stage+aç (`/tmp/sm`). `project_memory_read bug_denetimi_2026_08.md`
ile baseline'ı yükle. `git log --oneline -5` ile son değişiklikleri gör (yeni kod = yeni bug
yüzeyi; ör. son eklenen özelliğin dosyalarını ajanlara özel işaretle).

**Faz 1 — Paralel tarama.** 6 ekseni (kapsam argümanına göre alt küme) TEK mesajda `Agent` ile
başlat. Her prompt: eksen tanımı + §Yanlış-pozitif listesi + baseline "şunları TEKRARLAMA ama
düzeltilmiş mi diye BAK" notu + çıktı formatı. (E2 ve E4 ağır → gerekirse ikiye böl: E2a IDOR,
E2b SSRF+yetki.)

**Faz 2 — Doğrulama + dedupe.** Her YÜKSEK/ORTA'yı kaynakta aç-oku-teyit et (kural 3). Baseline
maddelerini re-check et (kural 4): ÇÖZÜLMÜŞ / AÇIK / YENİ. Yanlış-pozitifleri ele. Kesişen
bulguları birleştir (aynı kök neden → tek madde + "aynı kök: X, Y, Z uçları").

**Faz 3 — Rapor.** `BUG_RAPORU_<tarih>.md` (veya kullanıcı adı verdiyse o): başlıkta kapsam +
yöntem + genel değerlendirme; **AÇIK bulgular** önem sırasıyla (KRİTİK→DÜŞÜK), her biri dosya:satır
+ senaryo + çözüm + (varsa) kök-neden kümesi; **ÇÖZÜLMÜŞ** bölümü (baseline'dan kapananlar);
**Doğru bulunan** kısa listesi (yanlış-pozitif üretmeme kanıtı); sonda **önerilen düzeltme sırası**
(güvenlik önce, ucuz/yüksek-etki önce) + birleşik önem tablosu. SendUserFile + device_commit_files
`D:\site-monitor\`'a. Konteyner + device arşivini temizle.

**Faz 4 — Bellek.** `bug_denetimi_2026_08.md`'yi güncelle: yeni bulguları ekle, çözülenleri
işaretle, satır numaralarını tazele. Böylece bir sonraki `/bug-denetle` daha da isabetli koşar.

## Raporda ton ve dürüstlük

- Kod tabanı gerçekten sağlamsa bunu SÖYLE ("sistemik açık yok, tekil sapmalar") — şişirme yapma,
  DÜŞÜK'leri YÜKSEK gibi sunma.
- Her önem etiketi savunulabilir olmalı: YÜKSEK ancak somut istismar/etki senaryosuyla.
- "Doğru bulunan" bölümü zorunlu: neyi tarayıp temiz bulduğunu yaz ki kapsam görünsün ve kullanıcı
  aynı yeri tekrar sormasın.
- Çözümler somut olmalı (kod parçası/desen adı), "gözden geçirilmeli" gibi muğlak değil.

## Zenginleştirme (kullanıcıya opsiyonel sun)

- Bulunan YÜKSEK'lerden birini/birkaçını FİİLEN düzeltip testini yazma (kullanıcı onayıyla; ayrı iş).
- Bulguları GitHub issue taslaklarına çevirme.
- Bir sonraki denetim için `Workflow` ile daha çok eksende (ör. i18n paritesi, erişilebilirlik,
  bağımlılık CVE'leri) genişletme — yalnız kullanıcı çok-ajan orkestrasyona açıkça izin verirse.
