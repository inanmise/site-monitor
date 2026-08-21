---
description: Sentetik İzleme (k6) şablonlarını takım-kapsamlı, versiyonlu ve denetim izli hale getiren tam özellik geliştirmesi — Genel/Takım/Yerleşik şablon katmanları, admin yönetimi ve genele açma, estetik şablon yönetim ekranı, form seçicisinde gruplu listeleme, testler ve zenginleştirme önerileri.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /sablon-gelistirme — Takım-Kapsamlı Sentetik İzleme Şablonları

Görevin: bugün `frontend/src/components/scriptedTemplates.js` içinde **statik 13 yerleşik k6
şablonu** olarak yaşayan yapıyı, kullanıcıların katkı verebildiği, takım-kapsamlı, versiyonlu ve
tam denetim izli bir **şablon yönetim sistemine** dönüştürmek. Ürün kararı netleşmiş çekirdek:

- **Üç katman:** *Yerleşik* (koddan gelen 13 şablon — salt-okunur), *Genel* (herkes görür,
  yalnız admin düzenler/oluşturur), *Takım* (yalnız o takımın üyeleri görür; takımdaki herkes
  düzenleyebilir; kullanıcı YALNIZ kendi takımına şablon ekleyebilir).
- **Admin her şeyi görür ve yönetir**; bir takım şablonunu **genele açabilir**.
- **Versiyonlama:** her şablon değişikliği sürüm üretir; geçmiş görülebilir ve geri yüklenebilir.
- **Denetim izi:** kim, ne zaman, hangi şablonu ekledi/güncelledi/sildi — hem sürüm geçmişinden
  hem denetim kayıtlarından okunabilir olmalı.
- **Yönetim arayüzü:** Sentetik İzleme sayfası içinde, mevcut proje CSS'i ve UI primitifleriyle
  uyumlu, estetik açıdan özenli bir "Şablonlar" yönetim yüzeyi.
- **Form entegrasyonu:** monitör tanımlarken şablon seçicisinde Genel şablonlar ve kullanıcının
  takım şablonları AYRI gruplar halinde listelenir (yerleşikler ve "kayıtlı script"ler yanında).

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla (dosya yolu, test çıktısı, ekran davranışı) raporlanır;
ürün kararı gerektiren her boşluk tahmin edilmez, seçeneklerle kullanıcıya sorulur.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–7) sırayla.
- `tasarim` → yalnız Faz 0–1 (keşif + tasarım/karar dosyası) — kod yazılmaz, onay beklenir.
- `backend` → Faz 0, 1, 2, 3, 6, 7.
- `frontend` → Faz 0, 1, 4, 5, 6, 7 (API hazır varsayılır; değilse önce raporla).
- `hizli` → Faz 0–5 (testler asgari, Faz 6 smoke atlanır) — yalnız hızlı prototip istendiğinde.

## Değişmez kurallar (her fazda geçerli)

1. **Mevcut desenleri yeniden icat etme — aynala.** Versiyonlama `ScriptedScriptVersion` deseninin
   birebir eşidir (append-only, tam snapshot, `sequenceNo` + x.y.z etiket,
   `MonitoringController.nextVersion(prev, bumpType)` yardımcı fonksiyonu — `ScriptedVersioningTest`
   sözleşmesi: ilk kayıt 1.0.0, varsayılan artış yama, bozuk etiket kilitlemez). Yetki kontrolü
   `permissionService.require(session, key, action)` + takım görünürlüğü `SessionScope.canView(...)`
   + yazma takımı `resolveWriteTeam(...)` ile yapılır. Denetim `auditService.recordAction(...)`.
2. **PermissionCatalog kuralı (CLAUDE.md):** koda giren her yeni `resource_key` AYNI değişiklikte
   `PermissionCatalog.ALL`'a eklenir; yoksa Permission Matrix'te görünmez ve grant bootstrap olmaz.
3. **Şema kuralı (CLAUDE.md):** yeni tablolar `ddl-auto=update` ile doğar AMA eski DB'lerin
   yükseltmesi için `SchedulerService.applySchemaPatches()`'e idempotent `patch()` satırları da eklenir.
4. **i18n:** her yeni anahtar TR ve EN'e AYNI değişiklikte girer (`i18n-parity.test.jsx` kapısı).
   `.properties` değerlerinde ham Türkçe karakter YAZILMAZ (`\uXXXX`; `PropertiesEncodingTest`).
5. **Güvenlik pazarlık dışı:** şablon gövdesi k6 = keyfi kod. Şablon kaydında monitör kaydındaki
   statik denetimler AYNEN koşar (`ScriptedCheckerService.scanHardcodedSecrets` — sabit-kodlu
   secret reddi, `auditRequestTimeouts`/`auditTimeoutBudget` uyarıları, `auditEnvReferences`).
   Şablonlar **asla secret DEĞERİ taşımaz** — yalnız env değişken TANIMLARI (ad + açıklama);
   credential'lar `__ENV` üzerinden gelir (yerleşik şablonların mevcut sözleşmesi). IDOR: başka
   takımın şablonuna erişim → 404/403, testle pinlenir.
6. **Tasarım dili:** yeni ekran mevcut `App.css` değişkenleri/sınıflarıyla ve `ui/` primitifleriyle
   kurulur (`ModalShell`, `Dialog`, `SearchableSelect`, `SegmentedControl`, `KebabMenu`,
   `PaginationBar`, `Toast`, `TagInput`, `CodeEditor` — prism tabanlı, satır cetvelli). Yeni CSS
   yazmadan önce eşdeğer sınıf var mı bak. Desen adlandırması için namethatui.com sözlüğü referans
   (empty-state, dialog-drawer-sheet, badge-chip-pill, form-field, toast). İkon = yalnız
   `lucide-react`; dark theme'de (`[data-theme="dark"]`) her yeni yüzey elle doğrulanır.
7. Ortam Windows (`JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`, Node 24). `start-local.ps1` paketlenmiş
   jar çalıştırır → smoke öncesi `mvn package -DskipTests` + `npm run build`.
8. Hiçbir şey commit'lenmez; coverage floor'ları düşürülmez (yalnız yukarı ratchet);
   `TESTING.md` etkilenirse aynı anda güncellenir. Davranışı değiştiren her ürün kararı
   (aşağıdaki "Karar noktaları") uygulanmadan ÖNCE kullanıcıya sorulur.

## Faz 0 — Keşif ve karar noktaları

Önce mevcut durumu kanıtla (varsayma): `scriptedTemplates.js` yapısı (id, TR/EN `name/desc/when`,
`env[]`, `script`; yazım kuralları dosya başındaki yorumda ve `scriptedTemplates.test.js`'te
pinli — k6 0.49/Babel 6: `?.`, `??`, spread YOK; her isteğe açık `timeout`; `vus/iterations/stages`
tanımlanmaz), `ScriptedMonitorPage.jsx` seçici sözleşmesi (`form.template` değerleri: `''` |
`tpl:<id>` | `saved:<monitorId>`; `TemplateInfo` paneli `when` + env listesini gösterir; `savedScripts`
zaten ayrı grup), `/scripted*` endpoint envanteri (MonitoringController ~2400–2850), çok-takım
üyeliği (`AppUser.teamIds` + birincil `teamId`), `monitoring.scripted` izninin USER'a AÇIK OLMADIĞI.

Sonra şu **karar noktalarını** seçeneklerle ve önerinle kullanıcıya sun (`tasarim` modunda bu
fazın çıktısı `docs/` altına tasarım notu olarak da yazılır):

- **K1 — Yerleşik 13 şablonun kaderi:** (a) kodda kalsın, salt-okunur "Yerleşik" grubu olarak
  listelensin (ÖNERİLEN: sürümle birlikte güncelleniyor, TR+EN, testle pinli — DB'ye taşımak
  bakım sahibini belirsizleştirir); (b) ilk açılışta DB'ye Genel şablon olarak seed edilsin.
- **K2 — Şablon ekleme yetkisi:** kullanıcı "herkes kendi takımına ekleyebilsin" dedi; ancak
  `monitoring.scripted` (k6 = keyfi kod) bilinçli olarak USER'a kapalı. Şablon eklemek kod
  ÇALIŞTIRMAK değildir (monitör kurmak yine `monitoring.scripted` ister) — yine de ayrı bir
  `monitoring.scripted_templates` resource_key'i tanımla ve varsayılan grant'ini sor:
  (a) tüm USER'lara açık (isteğin düz okuması), (b) PO/TEAM_ADMIN'e açık (mevcut k6 politikasıyla
  hizalı). Matristen sonradan değiştirilebilir olduğunu not et.
- **K3 — "Herkes editleyebilsin"in sınırı:** takım şablonunu takımın her üyesi mi düzenler
  (isteğin düz okuması — ÖNERİLEN, versiyonlama + denetim izi riski karşılıyor), yoksa yalnız
  ekleyen + PO mu? Genel şablonu yalnız admin düzenler (net).
- **K4 — Silme:** soft-delete (ÖNERİLEN — "kim ne zaman sildi" görünür kalır, admin çöp
  kutusundan geri alabilir; envanterdeki `active`/restore deseniyle tutarlı) vs kalıcı silme.
- **K5 — Genele açma:** taşıma mı (şablon takımdan çıkar, ÖNERİLEN — tek gerçek kaynak) yoksa
  kopya mı? Ters yön (genelden takıma indirme) admin için gerekli mi?
- **K6 — Kullanıcı şablonlarında dil:** yerleşikler TR+EN; kullanıcı şablonunda tek serbest
  metin alanı mı (ÖNERİLEN — kullanıcıya çeviri yükü bindirme), çift dil alanı mı?

## Faz 1 — Veri modeli ve şema

- **`ScriptedTemplate`** entity (`scripted_templates`): `id`, `name`, `description`, `whenToUse`
  (seçici altındaki "ne zaman kullanılır" paneli — yerleşiklerdeki `when` karşılığı), `script`
  (TEXT), `envJson` (yalnız tanım: `[{name, desc, example}]` — DEĞER YOK), `tags` (CSV,
  `TagInput` ile), `teamId` (**null = Genel**), `active` (K4 soft-delete), `currentVersion`
  (x.y.z), `createdAt/createdBy/createdByName`, `updatedAt/updatedBy/updatedByName`,
  `promotedAt/promotedBy` (K5 izi), `sourceTeamName` (genele açılan şablonun köken rozeti).
  İndeksler: `teamId`, `active`, `name`.
- **`ScriptedTemplateVersion`** (`scripted_template_versions`): `ScriptedScriptVersion`'ın birebir
  eşi — `templateId`, `sequenceNo` (0=CREATE), `version`, `eventType`
  (CREATE/EDIT/RESTORE/**PROMOTE/DELETE/UNDELETE** — şablonda ek olaylar da sürüm satırı üretir ki
  "kim ne zaman ne yaptı" tek zaman çizelgesinde okunusun), `script`, `envJson`, `note`,
  `createdAt/createdBy/createdByName`. Append-only; asla update/delete edilmez.
- Repository'ler mevcut adlandırmayla (`ScriptedTemplateRepository.findByTeamIdInOrTeamIdIsNull...`
  yerine açık iki sorgu tercih et: genel + takım kümesi). `applySchemaPatches()`'e iki tablo için
  idempotent `CREATE TABLE` + indeks patch'leri (kural 3).

## Faz 2 — Backend API ve yetki

`MonitoringController`'daki `/scripted` ailesinin yanına (aynı sınıf, aynı stil — `ok()`/
`badRequest()` yardımcıları, `Map<String,Object>` gövdeler):

- `GET /monitoring/scripted/templates` — üç küme tek yanıtta: `builtin` DEĞİL (yerleşikler
  frontend'te kalır, K1a), `general` (herkes), `team` (kullanıcının `teamIds` kümesindekiler;
  admin → tümü, takım adı etiketiyle). `can_manage_general` (admin) ve takım başına yazma hakkı
  bayrakları yanıtta gelsin ki UI buton gizlemeyi tahminle yapmasın.
- `POST /monitoring/scripted/templates` — `resolveWriteTeam` ile takım ataması (admin `teamId:null`
  gönderip doğrudan Genel oluşturabilir; kullanıcı yalnız üyesi olduğu takıma — aksi 403).
  Kayıt öncesi Faz 0'daki statik denetimler: `scanHardcodedSecrets` bulgu → **redde** (mesajla),
  timeout/env denetimleri → yanıtta `warnings[]` (monitör kaydındaki davranışla aynı).
- `PUT /monitoring/scripted/templates/{id}` — içerik değiştiyse `nextVersion(cur, bumpType)` ile
  sürüm + version satırı (EDIT). Genel şablonda admin şartı; takım şablonunda K3 kararı.
- `DELETE /monitoring/scripted/templates/{id}` — K4: soft-delete + DELETE olaylı sürüm satırı;
  `?permanent=true` yalnız admin (sensitive).
- `GET .../templates/{id}/versions` + `GET .../versions/{versionId}` + `POST .../restore` —
  monitör sürüm uçlarının eşi (RESTORE olayı, "x.y.z sürümünden geri yüklendi" notu).
- `POST .../templates/{id}/promote` — yalnız admin: `teamId→null`, `sourceTeamName` doldur,
  PROMOTE sürüm satırı. (K5 ters yönü onaylanırsa `demote` da.)
- Her mutasyon `auditService.recordAction(...)` çağırır — `resourceType="scripted_template"`,
  diff'li; böylece AuditLog ekranı ve `UserActivityService` istatistikleri kendiliğinden kapsar.
- **Yetki:** yeni `monitoring.scripted_templates` resource_key (view/edit; `promote` + kalıcı
  silme admin-only uç kontrolü) — `PermissionCatalog.ALL`'a aynı değişiklikte (kural 2), K2
  kararına göre default grant seed'i. `SessionScope.canView` takım filtresi HER okuma ucunda.

## Faz 3 — Backend testleri

`src/test/java` aynasında, `@ExtendWith(MockitoExtension.class)` + AssertJ:
- **Görünürlük matrisi** (en kritik test — tablo halinde): USER(takım A) / USER(A+B çok-takım) /
  TEAM_ADMIN(A) / ADMIN × [genel şablon, A şablonu, B şablonu, soft-silinmiş A şablonu] →
  kim neyi listeler/okur/düzenler/siler. B'nin şablonuna A kullanıcısı: liste'de YOK + doğrudan
  ID ile 404/403 (IDOR).
- Sürümleme: CREATE→1.0.0; EDIT içerik değişmeden sürüm ÜRETMEZ; bumpType patch/minor/major;
  RESTORE yeni sürüm üretir (geçmişi ezmez); sequenceNo kesintisiz; version satırları append-only.
- Genele açma: promote sonrası eski takım üyesi hâlâ görür (genel oldu), `sourceTeamName` dolu,
  PROMOTE olayı kayıtlı; promote'u admin olmayan deneyince 403.
- Güvenlik: `scanHardcodedSecrets` yakalayan script kaydı reddedilir; envJson'a değer sızdırma
  denemesi reddedilir; timeout uyarıları `warnings[]`'te.
- Denetim: her mutasyonun `recordAction` çağrısı (Mockito verify) — eventType/resourceId doğru.
- `MonitoringControllerTest`'e yeni uçların sözleşme testleri (mevcut stille).

## Faz 4 — Şablon yönetim ekranı (estetik yüzey)

`ScriptedMonitorPage.jsx` içine (sekme durumu `?tab=` whitelist'ine yeni alt-görünüm eklemeden,
sayfanın kendi iç görünüm state'iyle) bir **"Şablonlar"** yönetim yüzeyi:

- Giriş: sayfa başlığı yanına `SegmentedControl` → *Monitörler | Şablonlar* (izinli kullanıcıya).
- Liste: kart ızgarası — her kart: ad, kapsam rozeti (*Yerleşik* gri / *Genel* mavi / takım adı),
  sürüm rozeti (`v1.2.0`), etiket chip'leri, "ne zaman kullanılır" özeti (2 satır clamp),
  güncelleyen + tarih. Üstte arama + kapsam filtresi (Tümü/Yerleşik/Genel/Takımım; admin'de takım
  seçici) + etiket filtresi. Boş durumda namethatui *empty-state* deseni: açıklama + "İlk takım
  şablonunu oluştur" CTA. Sayfalama `PaginationBar`.
- Kart eylemleri `KebabMenu`: Görüntüle / Düzenle / Sürüm geçmişi / Kopyala (yeni şablon taslağı) /
  Genele Aç (yalnız admin, `Dialog` onaylı) / Sil (`Dialog` onaylı, soft). Yerleşik kartlarda
  yalnız Görüntüle + Kopyala (salt-okunur rozetiyle).
- Editör modalı: `ModalShell` + mevcut monitör formundaki `CodeEditor` (satır cetvelli) + env
  TANIM satırları (ad/açıklama/örnek — değer alanı YOK) + `TagInput` + bump türü seçimi (mevcut
  `bumpType` UI deseni) + kaydetmeden önce backend `warnings[]` gösterimi (monitör formundaki
  uyarı bloğu deseni).
- Sürüm geçmişi modalı: monitör script sürüm modalinin eşi — zaman çizelgesi (CREATE/EDIT/
  RESTORE/PROMOTE/DELETE olay rozetleriyle "kim, ne zaman, ne yaptı"), sürüm önizleme
  (ikinci `CodeEditor`, `textareaId` çakışmasına dikkat — parametrik id bunun için var),
  Geri Yükle butonu. Basit satır-diff özeti (eklendi/silindi sayısı) yeterli; tam diff Faz 7 önerisi.
- Tüm metinler `useT` ile TR+EN; dark theme kontrolü; gerçek `fetch` testlere sızmaz.

## Faz 5 — Monitör formu seçici entegrasyonu

- `api/client.js`'e şablon uçları; `ScriptedMonitorPage` form açılışında şablon listesi yüklenir.
- Seçici zaten `SearchableSelect` ve seçenekler `group` alanıyla başlıklı gruplara bölünüyor
  (`scriptSourceOptions`, ~satır 1297) — yeni gruplar aynı mekanizmaya eklenir; sıra ve etiketler:
  1. *Takım şablonlarım* (çok-takımlıysa takım adıyla alt-etiket) — `team:<id>`
  2. *Genel şablonlar* — `gen:<id>`
  3. *Yerleşik şablonlar* (mevcut `tpl:<id>` — değer sözleşmesi DEĞİŞMEZ, kayıtlı monitörlerin
     `template` alanı geriye uyumlu kalır)
  4. *Kayıtlı script'ler* (mevcut `saved:<id>`)
- `TemplateInfo` paneli DB şablonları için de çalışır (whenToUse + env tanımları + kapsam rozeti
  + sürüm). Şablon seçilince script + env TANIMLARI forma kopyalanır (env değerleri boş gelir —
  mevcut yerleşik davranışla aynı).
- Monitör kaydına hangi şablondan türediği bilgisini yaz (mevcut `template` alanı zaten taşıyor) —
  Faz 7'deki "kullanım sayısı" önerisinin temeli.

## Faz 6 — Doğrulama ve smoke

- `mvn.cmd -f backend/pom.xml -B clean verify` (tam suite + jacoco kapısı) ve
  `cd frontend && npm run test:coverage && npm run build` — sayılarla raporla, floor ratchet.
- Vitest: görünürlük matrisi UI karşılığı (USER'da Genele Aç butonu YOK; başka takım kartı
  render edilmez), seçici grupları, TemplateInfo, sürüm modalı, boş durum, TR+EN + dark render.
- `i18n-parity` + `naming-consistency` + `PermissionCatalog` çapraz kontrolü.
- PostgreSQL ayaktaysa uçtan uca smoke: şablon oluştur → takım arkadaşı görür (ikinci kullanıcı) →
  düzenle (sürüm arttı) → genele aç → başka takım artık görüyor → sil → admin çöpten geri al →
  monitör formunda gruplu liste. `backend\app.log` temiz; bitince java süreçlerini kapat (uyarıyla).

## Faz 7 — Zenginleştirme önerileri (uygulama değil, öneri raporu)

Final rapora, her biri 2–3 cümlelik gerekçe + kabaca efor etiketiyle (S/M/L) öneri listesi yaz;
kullanıcı seçerse sonraki koşumda uygulanır:
- **Kullanım sayacı ve köken izi:** her şablon kartında "N monitör bu şablondan türedi" +
  tıklayınca liste (monitor.template alanından). Silme onay diyaloğunda "bu şablonu 7 monitör
  kullanıyor" uyarısı.
- **"Monitörden şablona":** monitör detayında "Scripti şablon olarak kaydet" eylemi — sahada
  kanıtlanmış scriptlerin şablonlaşma yolu (en organik büyüme kanalı).
- **Kaydetmeden dene:** şablon editöründe mevcut `POST /scripted/test` ucuyla tek-koşum deneme
  (k6 kuruluysa) — bozuk şablonun kütüphaneye girmesini baştan engeller.
- **Sürümler arası tam diff görünümü** (satır bazlı, CodeEditor çift panel).
- **Onay akışı:** genele açmayı istek/onay kuyruğuna bağlama (PO önerir, admin onaylar) —
  `LoginIssueReport` bildirim desenleri yeniden kullanılabilir.
- **Bayat şablon uyarısı:** k6 sürümü yükseltilirse (Dockerfile `K6_VERSION`) tüm şablonları
  sözdizimi denetiminden geçiren bir bakım komutu; `deprecated` rozeti.
- **Şablondan türeyen monitörlere "şablonun yeni sürümü var" rozeti** (opsiyonel senkron).
- **Dışa/içe aktarma:** şablonları JSON olarak export/import (ortamlar arası taşıma).
- **Favoriler + sık kullanılanlar** seçicide en üstte.

## Final rapor

Türkçe; içerik: karar noktaları ve verilen cevaplar; dosya-dosya değişiklik listesi; görünürlük
matrisi test tablosu (kim × ne × sonuç); önce/sonra test adetleri + coverage; smoke kanıtları;
Faz 7 öneri listesi (eforlu); kullanıcı onayı bekleyen kalemler. Komutlar/yollar/sınıf adları
olduğu gibi bırakılır.
