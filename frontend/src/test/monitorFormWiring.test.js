import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const COMPONENTS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'components')

/**
 * İZLEME FORMU BAĞLANTI KAPISI — bir alan formda GÖRÜNÜP kaydedilmemesin.
 *
 * Bu projenin bilinen 1 numaralı form hatası: alan eklenirken zincirin bir halkası atlanıyor,
 * kullanıcı değeri seçiyor, "kaydedildi" toast'ını görüyor ve sayfayı yenileyince değer
 * kayboluyor. Hiçbir yerde hata çıkmıyor.
 *
 * Gerçek vaka: Bildirim Grubu seçicisi 9 izleme formuna toplu düzenlemeyle eklendi. Beş formda
 * KAYDETME YÜKÜ eklenmedi — çünkü toplu düzenleme `str.replace` kullanıyordu ve eşleşme
 * olmadığında sessizce özgün metni döndürüyor, script de "ok" yazıyordu. EMPTY varsayılanı,
 * düzenlemede yükleme ve JSX yerindeydi; yalnız gönderim yoktu. Ekran doğru görünüyor,
 * seçim asla kaydedilmiyordu.
 *
 * Kapı yapısaldır: dört halkanın DÖRDÜNÜ de her formda arar. Davranışı değil BAĞLANTIYI
 * doğrular — davranış testi form başına ayrıca var, ama bu kapı tek bir eksik halkayı
 * dokuz dosyada birden yakalar.
 */

/**
 * Form adı → dosya, HARF DUYARSIZ çözülür.
 *
 * Aşağıdaki liste elle yazılmış PascalCase adlardan oluşuyor ve dosya yolu daha önce
 * `path.join(COMPONENTS, `${form}.jsx`)` + `existsSync` ile kuruluyordu. Listeye bir harf
 * hatası girseydi (ör. `PagespeedMonitorPage`) Windows'ta GÖRÜNMEZDİ — dosya sistemi harf
 * duyarsız, `existsSync` true döner — ama CI Linux'ta kapı kırılırdı. Yani kapı, geliştiricinin
 * işletim sistemine göre farklı davranıyordu. `monitorTypeSurfaces.test.jsx` tam bu tuzağa
 * düşüp aynı şekilde düzeltilmişti; desen buraya da taşındı.
 */
const COMPONENT_FILES = fs.readdirSync(COMPONENTS)
function formFile(form) {
  const wanted = `${form.toLowerCase()}.jsx`
  const hit = COMPONENT_FILES.find(f => f.toLowerCase() === wanted)
  expect(hit, `${form}.jsx bulunamadı — liste güncel mi? (harf duyarsız arandı)`).toBeTruthy()
  return path.join(COMPONENTS, hit)
}

/** Seçicinin bağlı olduğu tüm izleme formları. Yeni bir tür eklenirse buraya da eklenir. */
const MONITOR_FORMS = [
  'PingMonitorPage', 'DnsMonitorPage', 'DomainMonitorPage', 'HttpMonitorPage',
  'KeywordMonitorPage', 'PageMonitorPage', 'PageSpeedMonitorPage', 'PortMonitorPage',
  'ScriptedMonitorPage',
]

/**
 * Bildirim grubu alanının dört halkası.
 *
 * `notification_group_id` (snake_case) API'den OKUMA tarafıdır — yanıtlar snake_case döner.
 * `notificationGroupId` (camelCase) ise izleme uçlarına YAZMA tarafıdır: o uçlar
 * `@RequestBody Map` alıp anahtarı düz okuyor (`body.get("notificationGroupId")`).
 * Envanter ucu bunun TERSİ — orada gövde entity'ye bağlanıyor ve snake_case gerekiyor;
 * karışıklık gerçek bir hataya yol açtığı için ikisi de testle pinli.
 */
const LINKS = [
  { name: 'EMPTY varsayılanı', re: /notificationGroupId:\s*''/ },
  { name: 'düzenlemede yükleme', re: /notificationGroupId:\s*m\.notification_group_id/ },
  { name: 'kaydetme yükü', re: /notificationGroupId:\s*form\.notificationGroupId/ },
  // Seçici artık ORTAK bildirim bloğunun (NotifyChannels) içinde de olabilir — B1'de üç form
  // o bloğa taşındı. Kural DEĞİŞMEDİ ("form grubu bağlamalı"), yalnız iki meşru bağlama
  // biçimi var: doğrudan seçici ya da onu saran ortak blok.
  { name: 'JSX seçici', re: /<NotificationGroupSelect|<NotifyChannels/ },
]

describe('İzleme formu bağlantı kapısı (Bildirim Grubu)', () => {
  it('dokuz formun HEPSİ dört halkayı da taşır', () => {
    const missing = []
    for (const form of MONITOR_FORMS) {
      const src = fs.readFileSync(formFile(form), 'utf8')
      for (const link of LINKS) {
        if (!link.re.test(src)) missing.push(`${form} → ${link.name}`)
      }
    }
    expect(missing).toEqual([])
  })

  it('seçici bileşeni gerçekten import ediliyor (JSX var ama import yoksa ekran çöker)', () => {
    const missing = MONITOR_FORMS.filter(form => {
      const src = fs.readFileSync(formFile(form), 'utf8')
      // Doğrudan seçici YA DA onu saran ortak blok — ikisi de meşru; JSX'te ne
      // kullanılıyorsa importu da o olmalı (import yoksa ekran çöker).
      return !/import\s+(NotificationGroupSelect|NotifyChannels)\s+from/.test(src)
    })
    expect(missing).toEqual([])
  })
})

/**
 * 7/24 İZLEME EKİBİ (NOC) ALANI — aynı dört halka (2026-09-27; sözleşme `.migration/noc/CONTRACT.md`).
 *
 * Alan dokuz izleme formuna + sertifika envanter formuna TOPLU eklendi — tam da yukarıdaki "beş formda kaydetme yükü
 * yok" vakasının yeniden doğabileceği iş. Halkalar: varsayılan KAPALI (yeni izleme), düzenlemede kayıtlı değer
 * (yanıt snake_case `noc_notify` / `noc_group_ids`), kaydetme yükü (izleme uçları camelCase `nocNotify` / `nocGroupIds`;
 * envanter ucu varlığa bağlandığı için snake_case) ve ortak alan bileşeni (import dâhil). Kopyala (duplicate) düzenleme
 * eşlemesini (formFrom) kullandığı için ayrıca halka gerekmez — davranış testi form başına ayrı.
 */
const NOC_MONITOR_LINKS = [
  { name: 'EMPTY varsayılanı (kapalı)', re: /nocNotify:\s*false,\s*nocGroupIds:\s*\[\]/ },
  { name: 'düzenlemede yükleme', re: /nocNotify:\s*!!m\.noc_notify,\s*nocGroupIds:\s*nocIdsFrom\(m\.noc_group_ids\)/ },
  { name: 'kaydetme yükü', re: /nocNotify:\s*!!form\.nocNotify,\s*nocGroupIds:\s*nocGroupIdsBody\(form\.nocGroupIds\)/ },
  { name: 'JSX alan', re: /<NocNotifyField\b[^>]*\btype="[A-Z]+"[^>]*checked=\{form\.nocNotify\}[^>]*groupIds=\{form\.nocGroupIds\}/ },
  { name: 'alan import', re: /import\s+NocNotifyField\s+from\s+'\.\/noc\/forms\/NocNotifyField\.jsx'/ },
  { name: 'toplu işlem 7/24', re: /<BulkActionBar\b[^>]*\bnocType="[A-Z]+"/ },
]
/** Tür anahtarı (sözleşme) — form ve toplu işlem AYNI anahtarı taşımalı (yanlış tür = başka tablonun kaydı). */
const NOC_TYPE_OF = {
  PingMonitorPage: 'PING', DnsMonitorPage: 'DNS', DomainMonitorPage: 'DOMAIN', HttpMonitorPage: 'HTTP',
  KeywordMonitorPage: 'KEYWORD', PageMonitorPage: 'PAGE', PageSpeedMonitorPage: 'PAGESPEED', PortMonitorPage: 'PORT',
  ScriptedMonitorPage: 'SCRIPTED',
}

describe('İzleme formu bağlantı kapısı (7/24 izleme ekibi)', () => {
  it('dokuz formun HEPSİ altı halkayı da taşır', () => {
    const missing = []
    for (const form of MONITOR_FORMS) {
      const src = fs.readFileSync(formFile(form), 'utf8')
      for (const link of NOC_MONITOR_LINKS) {
        if (!link.re.test(src)) missing.push(`${form} → ${link.name}`)
      }
    }
    expect(missing).toEqual([])
  })

  it('form alanı ve toplu işlem DOĞRU tür anahtarını taşır', () => {
    const wrong = []
    for (const form of MONITOR_FORMS) {
      const src = fs.readFileSync(formFile(form), 'utf8')
      const want = NOC_TYPE_OF[form]
      // Şablon dizgede ters bölü YOK (kaçış tuzağı): sözcük sınırı yerine açık boşluk sınıfı.
      if (!new RegExp(`<NocNotifyField[^>]*[ ]type="${want}"`).test(src)) wrong.push(`${form} → alan type≠${want}`)
      if (!new RegExp(`<BulkActionBar[^>]*[ ]nocType="${want}"`).test(src)) wrong.push(`${form} → nocType≠${want}`)
    }
    expect(wrong).toEqual([])
  })

  it('envanter formu: snake_case dört halka + tür SSL', () => {
    const src = fs.readFileSync(path.join(COMPONENTS, 'inventory', 'InventoryFormModal.jsx'), 'utf8')
    const links = [
      ['EMPTY varsayılanı (kapalı)', /noc_notify:\s*false,\s*noc_group_ids:\s*\[\]/],
      ['düzenlemede yükleme', /noc_notify:\s*!!item\.noc_notify,[\s\S]{0,80}noc_group_ids:\s*nocIdsFrom\(item\.noc_group_ids\)/],
      ['kaydetme yükü (snake_case)', /noc_notify:\s*!!form\.noc_notify,[\s\S]{0,80}noc_group_ids:\s*nocGroupIdsBody\(form\.noc_group_ids\)/],
      ['JSX alan (SSL)', /<NocNotifyField\b[^>]*\btype="SSL"[^>]*checked=\{form\.noc_notify\}[^>]*groupIds=\{form\.noc_group_ids\}/],
    ]
    expect(links.filter(([, re]) => !re.test(src)).map(([name]) => name)).toEqual([])
    // camelCase anahtar envanter gövdesinde SESSİZCE yok sayılır (varlığa bağlanan uç) — yükte olmamalı.
    expect(src).not.toMatch(/\bnocNotify:\s*!!form/)
  })
})
