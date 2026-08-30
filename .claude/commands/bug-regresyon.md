---
description: SiteMonitor için ODAKLI regresyon + benzer-bug taraması — önceki 4 turda bulunan her bug SINIFININ imzasını (aranacak anti-desen + güvenli kardeş deseni) kullanarak (a) o bulguların hâlâ kapalı mı yoksa GERİ Mİ geldiğini re-check eder, (b) AYNI anti-deseni kod tabanının BAŞKA yerlerinde arar. imza-güdümlü, grep-önce/oku-sonra; geniş yeniden-denetim (/bug-denetle) değil, hızlı hedefli süpürme. Kullanıcı "önceki buglar çözülmüş mü", "benzer bug var mı", "bug regresyon taraması" dediğinde.
argument-hint: [class:<sınıf>|regres|benzer] (opsiyonel — boş = TAM: baseline re-check + tüm sınıflarda benzer-tarama)
---

# /bug-regresyon — Regresyon + Benzer-Bug İmza Taraması

Görevin: önceki dört denetim turunda (bkz. `project_memory_read bug_denetimi_2026_08.md`) bulunan
bug'ların her birini bir **sınıf imzası**na indirge, sonra bu imzalarla kod tabanını tara: (A)
bilinen bulgular hâlâ **kapalı mı / GERİ Mİ geldi** (regresyon), (B) aynı anti-desen **başka
yerde** var mı (benzer bug). Çıktı bir rapordur; kod DEĞİŞTİRMEZSİN (kullanıcı istemedikçe).

Bu komut `/bug-denetle`'den FARKLIDIR: o geniş 6-eksen keşif, bu **imza-güdümlü hedefli süpürme**
— hızlı, tekrarlanabilir, düşük yanlış-pozitif. `/bug-denetle` yeni sınıflar bulur; bu, bilinen
sınıfların NEREDE tekrarladığını ve düzeltmelerin tutup tutmadığını bulur.

Kapsam: `$ARGUMENTS`
- Boş → TAM: baseline re-check + aşağıdaki tüm imza sınıflarında benzer-tarama.
- `regres` → yalnız (A) baseline re-check (bilinen bulgular kapalı mı).
- `benzer` → yalnız (B) tüm sınıflarda anti-desen süpürmesi.
- `class:<ad>` → yalnız bir sınıf (ör. `class:idor`, `class:ssrf`, `class:pagination`).

## Değişmez kurallar

1. **Kaynağı konteynere al, orada tara.** `/bug-denetle`'deki gibi: tar → device_stage_files →
   `/tmp/sm`'e aç. `git log --oneline -10` ile son commit'leri gör (özellikle "fix"/"feat" —
   düzeltme regresyon getirmiş olabilir, yeni özellik yeni imza yüzeyi açar). Bitince temizle.
2. **İmza = ARA → ELE → DOĞRULA.** Her sınıf için: (i) anti-deseni Grep ile ARA (aşağıdaki
   imzalar); (ii) §Güvenli-kardeş listesindeki bilinen-doğru eşleşmeleri ELE; (iii) kalan her
   adayı kaynakta OKUYARAK doğrula. Grep eşleşmesi tek başına bulgu DEĞİLDİR — kod okunmadan
   rapora girmez.
3. **Baseline re-check zorunlu.** `bug_denetimi_2026_08.md`'deki her madde (Y1–Y9, O-serisi,
   N1–N12) için o maddenin "düzeltilmiş imzası" hâlâ yerinde mi bak: KAPALI ✓ / **REGRESYON ⚠**
   (düzeltme geri alınmış/bozulmuş) / hâlâ-açık. Regresyon en yüksek önceliktir.
4. **Önem eşiği** `/bug-denetle` ile aynı (KRİTİK/YÜKSEK/ORTA/DÜŞÜK). Benzer-bug bulgusu, kaynak
   sınıfının önemini otomatik almaz — kendi bağlamında (yetki kapısı, kullanıcı-girdisi, etki)
   yeniden derecelendirilir. Doğrulanamayan → DÜŞÜK + "doğrulanmalı".
5. Rapor Türkçe, `dosya:satır — bug | Neden bug: senaryo | Çözüm: düzeltme`. Gerçek sicil/kurum
   adı geçmez. `D:\site-monitor\`'a yazılır; bellek güncellenir. Kod değiştirilmez.

## Bug sınıfı imzaları (ARA deseni · GÜVENLİ kardeş · nasıl doğrulanır)

**S1 — IDOR / takım-izolasyonu atlaması** (kaynak: Y1,Y2,Y3,O9)
ARA: `controller/*.java` içinde `{id}` yol değişkenli `@GetMapping/@PutMapping/@DeleteMapping/
@PostMapping` uçları; gövdede `findById` VAR ama `denyIfNotViewable`/`SessionScope.canView`/
`canManage`/`canOperateTeam`/`requireIncidentWrite` YOK. Ayrıca `existsById` ile takım-kontrolü
baypası. GÜVENLİ: kardeş uçların hepsi monitörün KENDİ `teamId`'siyle `denyIfNotViewable`. DOĞRULA:
uç gerçekten takım-scoped bir kaynağa mı dokunuyor (envanter/monitör/olay) ve `require` sonrası
nesne-yetki var mı; yazma/transfer/bulk uçlarında kayıt-başına kontrol.

**S2 — Redirect SSRF** (kaynak: Y8,O10,O11)
ARA: `Redirect.NORMAL`, `.followRedirects(HttpClient.Redirect.NORMAL)`,
`setInstanceFollowRedirects(true)`, `setFollowRedirects(true)`. GÜVENLİ: `Redirect.NEVER` +
`SafeRedirect` hop döngüsü (her hop `ssrfGuard.validate`). DOĞRULA: dış-hedefe giden bir istemci
mi (kullanıcı URL'si/monitör hedefi) ve redirect'ler yeniden doğrulanıyor mu.

**S3 — Sınırsız yanıt/gövde okuma** (kaynak: Y7,O12,D18)
ARA: `readAllBytes()`, `BodyHandlers.ofString()`, `BodyHandlers.ofByteArray()`,
`InputStream.readAllBytes`, `GZIPInputStream`/`InflaterInputStream` + `readAllBytes`. GÜVENLİ:
`readNBytes(MAX_…)` / `ofInputStream` + tavanlı okuma. DOĞRULA: kaynak dış/güvenilmez mi
(webhook yanıtı, sayfa gövdesi, push API yanıtı) — iç sabit değilse OOM yüzeyi.

**S4 — SsrfGuard boşluğu** (kaynak: O13,Y8)
ARA: `new Socket`, `connectFirstReachable`, `HttpClient…send`, `URL(...).openConnection`,
`SSLSocketFactory…createSocket` — çağrıdan önce `ssrfGuard.validate` YOK. GÜVENLİ: PortChecker/
KeywordChecker deseni (bağlanmadan önce `vetted = ssrfGuard.validate(host)` + IP'ye bağlan).
DOĞRULA: hedef host kullanıcı/envanter kaynaklı mı.

**S5 — Sayısal: tamsayı bölmesi / sıfıra bölme / sınır asimetrisi** (kaynak: O15,O1)
ARA: `/ 10L`, `/ 100L`, `Math.round(` sonrası `/ \d+L`, `int … / …count`, `(a) / (b)` yüzde/
ortalama bağlamında; sayaç-liste asimetrisi (`<= x` bir yerde, `>= 0 && <= x` başka yerde).
GÜVENLİ: `/10.0`, `count>0 ?` sıfır-koruması, aynı sınır her iki tarafta. DOĞRULA: değer yüzde/
oran/ortalama mı ve tek-ondalık niyeti (`*10 … /10`) var mı; sıfır-bölen mümkün mü.

**S6 — Sayfalama taban uyumsuzluğu** (kaynak: Y9)
ARA: `PaginationBar` çağrıları — `page={page}` (0-tabanlı `useState(0)` ile) + `onPageChange=
{setPage}` (dönüşümsüz). GÜVENLİ: `page={page+1}` + `onPageChange={p=>...(p-1)}` (MyAuditLog);
veya 1-tabanlı hook (`h.page`). DOĞRULA: state 0-tabanlı mı (API `page` 0-tabanlı, `rangeStart=
page*size+1`) ve widget 1-tabanlı mı (`disabled={page<=1}`).

**S7 — Chart/dış-veri ts-guard eksikliği** (kaynak: LoginActivityChart)
ARA: chart bileşenlerinde `.map(... ts ...)` / `.endsWith(` / `new Date(x.ts)` — `typeof
x?.ts === 'string'` filtresi YOK. GÜVENLİ: `ResponseTimeChart` (`.filter(s => typeof s?.ts ===
'string')`). DOĞRULA: veri dış/sunucu kaynaklı mı ve null/number `ts` çökertebilir mi.

**S8 — Envanter/entity alan ekleme tuzağı: setter/patch/şema eksik** (kaynak: CLAUDE.md kuralı)
ARA: `model/*.java` yeni `@Column` alanları ↔ `SchedulerService.applySchemaPatches()` `ADD
COLUMN` satırları (kolon adı eşleşmesi — yazım hatası dahil, bkz. `pagespeed_checks`); `update*`/
`updateInventory` metodunda ilgili `setX` çağrısı VAR mı. GÜVENLİ: her yeni alan patch + create +
update setter + diff + gösterim (6 nokta). DOĞRULA: alanı kaydedip reload'da kaybolan var mı;
patch tablo adı entity `@Table` ile birebir mi.

**S9 — @Transactional eksik yazan/deleteBy metod** (kaynak: kural)
ARA: `repository/*.java` türetilmiş `deleteBy…`/`@Modifying @Query` — `@Transactional` YOK;
`RepositoryWriteTransactionGuardTest` allow-list dışı. GÜVENLİ: yazan metod `@Transactional` +
`int` döner. DOĞRULA: metod gerçekten yazıyor/siliyor mu.

**S10 — Boolean unbox NPE** (kaynak: acknowledged)
ARA: `!x.getBooleanField()` / `if (x.getFlag())` — nullable Boolean üzerinde (kolon backfill'siz
eklenmiş). GÜVENLİ: `Boolean.TRUE.equals(x.getFlag())`. DOĞRULA: alan nullable mı ve eski satırlar
NULL taşıyabilir mi; NPE bir sweep'i iptal eder mi.

**S11 — Frontend fetch yarışı** (kaynak: PageMonitorPage,CertificateModal,UserPushSettings)
ARA: `async` load + `setState(...)` — `seq`/`alive`/`AbortController` guard YOK; birden çok
tetikleyici (filtre/poll/checkNow). GÜVENLİ: `resSeq`/`alive` guard (PageSpeedMonitorPage);
metin filtrelerinde debounce. DOĞRULA: eş-zamanlı çağıran var mı, yavaş yanıt hızlıyı ezebilir mi.

**S12 — Türetilmiş-state senkronsuzluğu** (kaynak: InventoryManager)
ARA: `useState(propAdı)` — prop değişince güncelleyen `useEffect(()=>setX(prop),[prop])` YOK.
GÜVENLİ: sync effect. DOĞRULA: prop oturum içinde değişebilir mi.

**S13 — PermissionCatalog rol-default tek-satır çok-eylem** (kaynak: Y6)
ARA: `PermissionCatalog` `new Resource(..., List.of(VIEW, EDIT), ...)` tek satırda + o key
`auditDefaults` allow-list'inde. GÜVENLİ: VIEW ve EDIT ayrı `r(...)` satırları (yorum 73-76).
DOĞRULA: `auditDefaults` putAll ile salt-okunur role EDIT sızıyor mu (`defaultsFor("AUDIT")`).

**S14 — Eskalasyon/alarm tutarlılığı** (kaynak: Y4,Y5,N9,N10)
ARA: alıcı çözen iki yol (storm vs bireysel) ayrı mantık; `alertLevel` event'e `setAlertLevel`+
`save` ile kalıcılaşmayan dal; üç yolun `event.getTeamId()` yerine canlı ctx kullanması; push
tetiğinin mail erken-dönüşünden sonra gelmesi. GÜVENLİ: `teamOnlyRecipients` tek kaynak; seviye
terfisinde her dalda save; kanal tetikleri suppression gate'inden sonra ama mail-boşluğundan önce.
DOĞRULA: storm↔bireysel aynı sonucu mu veriyor; seviye bump kaydediliyor mu.

**S15 — Dayanıklı kuyruk/outbox** (kaynak: N2,N3,N4,O14)
ARA: outbox/retry servisleri — devre-açık durumda enqueue edilen satırın hiç geri-alınmayan
statüsü; `@PostConstruct`/`@Scheduled` açılış drain'i YOK; retry sonrası gecikmesiz yeniden-tarama
(backoff atlama); her çağrıda yeni kapatılmayan `HttpClient`. GÜVENLİ: cooldown sonrası re-queue;
açılış drain; backoff'a saygılı tarama; paylaşılan `@PreDestroy`'lu istemci. DOĞRULA: bir sonraki
enqueue'a kadar askıda kalan/kaybolan teslimat var mı.

**S16 — SQL salt-okunur zorlaması** (kaynak: N1,N5)
ARA: kullanıcı SQL çalıştıran servisler — kara-listede eksik kelime (`into`, `merge`, `\bwith\b …
as (… insert)`), `setReadOnly(true)`/`@Transactional(readOnly=true)` YOK, satır tavanının iç
LIMIT ile atlanması. GÜVENLİ: salt-okunur bağlantı + dış-sarma LIMIT + `setMaxRows`. DOĞRULA:
SELECT-başlangıçlı bir yan-etki (SELECT INTO) veya sınırsız satır mümkün mü.

**S17 — Mail HTML/düz-metin paritesi + esc** (kaynak: sorumlu-ekipler kuralı)
ARA: `EmailTemplateBuilder` — yalnız-HTML eklenen satır (düz-metin karşılığı yok), `esc()`
uygulanmadan gömülen kullanıcı değeri, null → "null" yazımı. GÜVENLİ: `Row(label, html, text)`
çifti + `esc(...)`. DOĞRULA: yeni eklenen mail bölümü iki biçimde de var mı, değerler kaçırılıyor mu.

## Akış

**Faz 0.** Kaynağı stage+aç; baseline'ı oku; `git log -10`.
**Faz 1 — (A) Baseline re-check.** Her bilinen madde için "düzeltilmiş imzası"nı kaynakta doğrula
→ KAPALI ✓ / REGRESYON ⚠ / açık. (Bu, imzaların da doğru olduğunu teyit eder.)
**Faz 2 — (B) Benzer-tarama.** Kapsamdaki her S-sınıfı için ARA→ELE→DOĞRULA; kalan gerçek
bulguları topla. (Grep'i paralel ajanlara dağıtabilirsin: backend imzaları bir ajana, frontend
imzaları diğerine — ama her aday ana ajanda okunarak doğrulanır.)
**Faz 3 — Rapor.** `BUG_REGRESYON_<tarih>.md`: (1) **Baseline durum tablosu** (madde → KAPALI/
REGRESYON); (2) **Yeni benzer bulgular** önem sırasıyla, kaynak sınıfına atıfla ("S6 imzası,
Y9 ile aynı sınıf, farklı dosya"); (3) **Temiz sınıflar** (imza tarandı, başka örnek yok — kapsam
görünürlüğü); (4) önerilen sıra. SendUserFile + device_commit_files. Arşivleri temizle.
**Faz 4 — Bellek.** `bug_denetimi_2026_08.md`'yi güncelle: regresyonları işaretle, yeni benzer
bulguları ekle, satırları tazele.

## Ton
Regresyon YOKSA ve benzer bulgu YOKSA bunu net söyle ("tüm düzeltmeler tutmuş, imzaların hiçbiri
başka yerde tekrar etmiyor") — üretmek için bulgu icat etme. REGRESYON varsa en üste al (bir
düzeltmenin geri alınması, hiç düzeltilmemiş bir buga göre daha ciddi sinyaldir). Benzer-bug
önemini bağlamına göre ver, kaynak sınıfından miras alma. "Temiz sınıflar" bölümü zorunlu.

## Zenginleştirme
- Bulunan REGRESYON/benzer bug'ları fiilen düzeltip testini yazma (onayla, ayrı iş).
- İmzaları kalıcı bir statik-kontrol testine çevirme (ör. ArchUnit/özel test: "her {id} uç
  denyIfNotViewable çağırır", "PaginationBar çağıranları 1-tabanlı"), böylece regresyon CI'da yakalanır.
- Yeni bir `/bug-denetle` turu bulursa bulgularını buraya S-sınıfı olarak ekleme.
