import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * KAYNAK TARAYAN KAPI — izleme düzenleme modalları KENDİ İÇİNDE kaydırmalı.
 *
 * <p><b>Neden var.</b> `.modal-overlay` `overflow-y: auto` taşıyor. Bir `.modal-box` kendi
 * `maxHeight`/`overflowY`ını tanımlamazsa taşan içerik overlay'e düşüyor: kaydırma çubuğu
 * modalın kenarında değil EKRANIN en sağında beliriyor ve kaydırınca modalın başlığı da yukarı
 * kayıyor. Sekiz izleme sayfası bunu inline stille taşıyordu, DNS taşımıyordu — yani kural
 * sekiz KOPYA hâlinde yaşıyordu ve dokuzuncusu unutulunca kimse fark etmedi (kullanıcı bildirdi).
 *
 * <p>Test kaynağa bakar çünkü jsdom düzen/boyama yapmaz: taşma ve kaydırma çubuğunun NEREDE
 * çıktığı orada ölçülemez ([[jsdom yerleşim kör noktası]]). Ölçülebilen şey kuralın beyan
 * edilmiş olmasıdır.
 *
 * <p>Kapsam bilinçli olarak izleme SAYFALARIYLA sınırlı: `WeeklyReportsPage` gibi ekranlarda
 * kendi iç kaydırma alanı olan modallar var, onları bu kurala zorlamak yanlış olurdu.
 */
// Yol çözümü cssClasses.test.js ile AYNI idiom: `new URL(...).pathname` Windows'ta başında
// eğik çizgiyle geliyor ve `readdirSync` "D:\src\components" diye arıyor.
const DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'components')

const PAGES = readdirSync(DIR).filter(f => /^(Dns|Domain|Http|Keyword|Page|PageSpeed|Ping|Port|Scripted)MonitorPage\.jsx$/.test(f))

describe('izleme düzenleme modalları — iç kaydırma', () => {
  it('dokuz izleme sayfasının tamamı taranır (tarayıcı bozulursa bu düşer)', () => {
    expect(PAGES.length).toBe(9)
  })

  it.each(PAGES)('%s: her modal-box maxHeight + overflowY beyan eder', (file) => {
    const src = readFileSync(path.join(DIR, file), 'utf8')
    // Her `className="modal-box"` açılışından sonraki ~200 karakterde stil beyanı aranır;
    // eleman tek satıra sığmayabildiği için pencere satır sonlarını da kapsar.
    const offenders = []
    const re = /className="modal-box"/g
    let m
    while ((m = re.exec(src)) !== null) {
      const window_ = src.slice(m.index, m.index + 220)
      const hasScroll = /maxHeight/.test(window_) && /overflowY/.test(window_)
      if (!hasScroll) {
        const line = src.slice(0, m.index).split('\n').length
        offenders.push(`${file}:${line}`)
      }
    }
    expect(offenders).toEqual([])
  })
})
