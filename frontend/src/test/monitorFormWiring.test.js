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
