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
    const src = fs.readFileSync(path.join(COMPONENTS, 'ui', 'NotifyChannels.jsx'), 'utf8')
    // Pencere DAR olmali: genis alinirsa bir onceki (Sesli arama) dosemesinin --disabled'i
    // yanlislikla yakalanir. Webhook dosemesi kendi <label> etiketinden baslar.
    // DIKKAT: 'userpush.monitorToggle' ayni zamanda 'monitorToggleHint'in ONEKI; duz indexOf
    // title niteligini bulup pencereyi onChange'den ONCE kesiyordu. Tam etiketi ara.
    const at = src.indexOf("t('userpush.monitorToggle')")
    const webhookTile = src.slice(src.lastIndexOf('<label', at), at)
    expect(webhookTile).not.toContain('--disabled')
    expect(webhookTile).toContain('onChange')
    // Pasif olanlar SADECE iki tanedir.
    expect((src.match(/http-channel--disabled/g) || []).length).toBe(2)
  })
})
