import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const COMPONENTS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'components')

/**
 * KART GÖRÜNÜMÜ STANDARDI — dokuz izleme türü AYNI biçimde listelenir.
 *
 * DNS ve Port satır (tablo) tabanlıydı, diğer yedi tür karttı. Aynı ürün, aynı soru ("bu izleme
 * ne durumda?") iki farklı düzende cevaplanıyordu: kullanıcı bir ekranda göz gezdirip diğerinde
 * sütun okuyordu. Faz C'de ikisi de `upt-card` desenine alındı.
 *
 * Kural yalnız "kart var mı" değil, paylaşılan parçaların KULLANILDIĞIdır: kendi meta/eylem
 * bloğunu yeniden yazan bir sayfa, standardın sessizce kaymasının ilk adımıdır.
 */
const MONITOR_PAGES = [
  'PingMonitorPage', 'DnsMonitorPage', 'DomainMonitorPage', 'HttpMonitorPage',
  'KeywordMonitorPage', 'PageMonitorPage', 'PageSpeedMonitorPage', 'PortMonitorPage',
  'ScriptedMonitorPage',
]

const read = (p) => fs.readFileSync(path.join(COMPONENTS, `${p}.jsx`), 'utf8')

describe('izleme listesi kart standardı', () => {
  it('dokuz sayfa da KART ızgarası çizer', () => {
    const missing = MONITOR_PAGES.filter(p => !/className="upt-grid"/.test(read(p)))
    expect(missing).toEqual([])
  })

  it('hiçbir izleme sayfası listeyi TABLO olarak çizmez', () => {
    // Not: detay modallarındaki tablolar (kontrol geçmişi vb.) ayrı bileşenlerde; burada
    // taranan yalnız sayfa dosyalarının kendisi.
    const offenders = MONITOR_PAGES.filter(p => /<table[\s>]/.test(read(p)))
    expect(offenders, 'liste hâlâ tablo olarak çiziliyor').toEqual([])
  })

  it('kart üstbilgisi ve eylemleri PAYLAŞILAN bileşenlerden gelir', () => {
    const missing = []
    for (const p of MONITOR_PAGES) {
      const src = read(p)
      if (!/<MonitorCardMeta/.test(src)) missing.push(`${p} → MonitorCardMeta`)
      if (!/<MonitorCardActions/.test(src)) missing.push(`${p} → MonitorCardActions`)
    }
    expect(missing).toEqual([])
  })

  it('durum sınıfı paylaşılan sözlükten seçilir (uydurma sınıf = tarayıcı varsayılanı)', () => {
    // 'warn' senaryo izlemesine ozel MESRU dorduncu durum (kismi gecis) ve App.css:2892'de
    // TANIMLI. Listeye alinmasi, uydurma bir sinifin gozden kacmasina izin vermez.
    const allowed = ['upt-card--up', 'upt-card--down', 'upt-card--unknown', 'upt-card--warn']
    const offenders = []
    for (const p of MONITOR_PAGES) {
      const used = [...read(p).matchAll(/'(upt-card--[a-z]+)'/g)].map(m => m[1])
      for (const cls of used) if (!allowed.includes(cls)) offenders.push(`${p} → ${cls}`)
    }
    expect(offenders).toEqual([])
  })
})
