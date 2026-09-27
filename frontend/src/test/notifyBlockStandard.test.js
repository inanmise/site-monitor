import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const COMPONENTS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'components')

/**
 * BİLDİRİM BLOĞU + ARALIK ÇUBUĞU STANDARDI — dokuz izleme formu AYNI dili konuşmalı.
 *
 * Yaşanan: aynı iş dokuz formda üç farklı biçimde yazılmıştı. HTTP/Keyword/Port dört döşemeli bir
 * ızgara, Page/PageSpeed/Scripted düz onay kutuları, DNS/Ping/Domain ise hiç e-posta kutusu
 * olmadan tek bir webhook satırı kullanıyordu. Aralık ayarı kimi formda kaydırma çubuğu, kimi
 * formda açılır listeydi. Kullanıcı her ekranda aynı soruyu farklı bir el alışkanlığıyla
 * cevaplıyordu.
 *
 * Daha kötüsü GÖRSEL BİR YALAN vardı: Keyword ve Port formlarında webhook döşemesi
 * `--disabled` sınıfı taşıyordu (`opacity:.55; cursor:not-allowed`). Kutu ÇALIŞIYORDU ama
 * pasif görünüyordu; kullanıcı haklı olarak "tikli ama değiştiremiyorum" dedi. Bu kapı, aynı
 * hatanın kopyala-yapıştırla geri gelmesini engeller.
 *
 * Kural: her form ortak bileşenleri kullanır — kendi kanal ızgarasını/çubuğunu YENİDEN YAZMAZ.
 */
const MONITOR_FORMS = [
  'PingMonitorPage', 'DnsMonitorPage', 'DomainMonitorPage', 'HttpMonitorPage',
  'KeywordMonitorPage', 'PageMonitorPage', 'PageSpeedMonitorPage', 'PortMonitorPage',
  'ScriptedMonitorPage',
]

const read = (form) => fs.readFileSync(path.join(COMPONENTS, `${form}.jsx`), 'utf8')

describe('bildirim bloğu standardı', () => {
  it('dokuz form da ORTAK bildirim bloğunu kullanır', () => {
    const missing = MONITOR_FORMS.filter(f => !/<NotifyChannels/.test(read(f)))
    expect(missing).toEqual([])
  })

  it('dokuz form da ORTAK aralık çubuğunu kullanır', () => {
    const missing = MONITOR_FORMS.filter(f => !/<IntervalSlider/.test(read(f)))
    expect(missing).toEqual([])
  })

  it('hiçbir form KENDİ kanal ızgarasını yeniden yazmaz (kopya blok = kayan standart)', () => {
    // `*-channels` ızgarası yalnız ortak bileşende olmalı; formda görülmesi eski bloğun
    // geri geldiği anlamına gelir.
    const offenders = MONITOR_FORMS.filter(f => /className="[^"]*-channels"/.test(read(f)))
    expect(offenders).toEqual([])
  })

  it('hiçbir form KONTROL ARALIĞI çubuğunu yeniden yazmaz', () => {
    // Dikkat: aynı CSS sınıfı (`*-interval-slider`) zaman aşımı gibi BAŞKA çubuklarda da
    // meşru biçimde kullanılıyor. Kural sınıfa değil BAĞLANMAYA bakar: `intervalSeconds`e
    // yazan elle bir range girdisi kalmamalı.
    const offenders = MONITOR_FORMS.filter(f => {
      const src = read(f)
      return /<input[^>]*type="range"[\s\S]{0,400}?intervalSeconds:/.test(src)
    })
    expect(offenders).toEqual([])
  })

  it('ortak blokta webhook döşemesi PASİF DEĞİL (görsel yalan kapısı)', () => {
    // Keyword/Port'ta yaşanan hata: kutu çalışıyor ama `--disabled` sınıfıyla soluk ve
    // "not-allowed" imleçle çiziliyordu. Yalnız SMS ve Sesli arama pasif olabilir.
    // Döşemeler shadcn "seçim kartı" (`<ChannelTile …>`): pasiflik `disabled` prop'uyla verilir.
    const src = fs.readFileSync(path.join(COMPONENTS, 'ui', 'NotifyChannels.jsx'), 'utf8')
    // Pencere DAR olmali: webhook dosemesi KENDI <ChannelTile acilisindan baslar ve bir sonraki
    // kapanisa (`/>`) kadar surer; genis alinirsa onceki (Sesli arama) dosemesinin disabled'i yakalanir.
    // DIKKAT: 'userpush.monitorToggle' ayni zamanda 'monitorToggleHint'in ONEKI; tam etiketi ara.
    const at = src.indexOf("t('userpush.monitorToggle')")
    expect(at, 'webhook döşemesi bulunamadı').toBeGreaterThan(0)
    const from = src.lastIndexOf('<ChannelTile', at)
    const webhookTile = src.slice(from, src.indexOf('/>', at))
    expect(webhookTile).not.toMatch(/(^|[^A-Za-z])disabled([^A-Za-z]|$)/)
    expect(webhookTile).toContain('onCheckedChange')
    // Pasif olanlar SADECE iki tanedir (SMS + Sesli arama).
    const tiles = src.split('<ChannelTile').slice(1).map((chunk) => chunk.slice(0, chunk.indexOf('/>')))
    expect(tiles).toHaveLength(4)
    expect(tiles.filter((tile) => /(^|[^A-Za-z])disabled([^A-Za-z]|$)/.test(tile))).toHaveLength(2)
  })
})
