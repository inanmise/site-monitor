import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * KAYNAK TARAYAN KAPI — izleme düzenleme modalları KENDİ İÇİNDE kaydırmalı.
 *
 * <p><b>Neden var.</b> `.modal-overlay` `overflow-y: auto` taşıyordu. Bir `.modal-box` kendi
 * `maxHeight`/`overflowY`ını tanımlamazsa taşan içerik overlay'e düşüyor: kaydırma çubuğu
 * modalın kenarında değil EKRANIN en sağında beliriyor ve kaydırınca modalın başlığı da yukarı
 * kayıyordu. Sekiz izleme sayfası bunu inline stille taşıyordu, DNS taşımıyordu — yani kural
 * sekiz KOPYA hâlinde yaşıyordu ve dokuzuncusu unutulunca kimse fark etmedi (kullanıcı bildirdi).
 *
 * <p>2026-09-25 (shadcn geçişi): dokuz formun penceresi artık TEK bileşen —
 * `monitoring/MonitorForm.jsx` `MonitorFormModal` (ui/ModalShell + `scrollBody` + sabit `footer` +
 * "devamı için kaydırın" ipucu). Kural artık dokuz KOPYA değil bir yerde; bu kapı (1) dokuz sayfanın
 * o pencereyi kullandığını ve elle kurulu `.modal-box`/`.modal-overlay` kalmadığını, (2) pencerenin
 * sözleşmeyi (kaydırılan gövde + sabit alt çubuk + ipucu) gerçekten kurduğunu pinler.
 *
 * <p>Test kaynağa bakar çünkü jsdom düzen/boyama yapmaz: taşma ve kaydırma çubuğunun NEREDE
 * çıktığı orada ölçülemez ([[jsdom yerleşim kör noktası]]). Ölçülebilen şey kuralın beyan
 * edilmiş olmasıdır.
 */
// Yol çözümü cssClasses.test.js ile AYNI idiom: `new URL(...).pathname` Windows'ta başında
// eğik çizgiyle geliyor ve `readdirSync` "D:\src\components" diye arıyor.
const DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'components')

const PAGES = readdirSync(DIR).filter(f => /^(Dns|Domain|Http|Keyword|Page|PageSpeed|Ping|Port|Scripted)MonitorPage\.jsx$/.test(f))
const FORM = readFileSync(path.join(DIR, 'monitoring', 'MonitorForm.jsx'), 'utf8')

describe('izleme düzenleme modalları — iç kaydırma', () => {
  it('dokuz izleme sayfasının tamamı taranır (tarayıcı bozulursa bu düşer)', () => {
    expect(PAGES.length).toBe(9)
  })

  it.each(PAGES)('%s: düzenleme formu ORTAK pencereyi kullanır (MonitorFormModal), elle kutu yok', (file) => {
    const src = readFileSync(path.join(DIR, file), 'utf8')
    expect((src.match(/<MonitorFormModal[\s>]/g) || []).length, 'tam bir düzenleme penceresi').toBe(1)
    // Elle kurulu kutu/örtü geri gelmesin: katmansız App.css kuralları shadcn Dialog'u ezerdi ve
    // iç kaydırma sözleşmesi yine sayfa başına kopyaya dönerdi.
    expect(src).not.toMatch(/className="modal-box/)
    expect(src).not.toMatch(/className="modal-overlay/)
    expect(src).not.toMatch(/className="modal-scroll-body/)
    // Butonlar kaydırılan gövdede değil sabit alt çubukta: `footer` ile verilir.
    const at = src.indexOf('<MonitorFormModal')
    expect(src.slice(at, at + 1200), 'alt çubuk footer ile verilmeli').toMatch(/footer=\{/)
  })

  it('ortak pencere sözleşmesi: scrollBody + gövde ref (ipucu kancası) + sabit footer + ipucu', () => {
    expect(FORM).toMatch(/const scrollHint = useModalScrollHint\(\)/)
    const shell = FORM.slice(FORM.indexOf('<ModalShell'), FORM.indexOf('</ModalShell>'))
    expect(shell).toMatch(/\bscrollBody\b/)
    expect(shell).toMatch(/bodyRef=\{scrollHint\.ref\}/)
    expect(shell).toMatch(/footer=\{/)
    // Emek biriken form: örtü tıklaması ve Escape KAPATMAZ (eski elle kurulu kutunun davranışı).
    expect(shell).toMatch(/dismissOnBackdrop=\{false\}/)
    expect(shell).toMatch(/dismissOnEscape=\{false\}/)
    // İpucu alt çubuğun içinde (kabın üst kenarına yaslı) — gövde kaydıkça yerinde kalır.
    expect(shell).toMatch(/<ModalScrollHint show=\{scrollHint\.show\} scrollMore=\{scrollHint\.scrollMore\} \/>/)
  })

  // Regression: ISSUE-002 — `<ModalScrollHint {...scrollHint} />` yayılımı hook'un `ref`ini fonksiyon
  // bileşenine prop olarak geçiriyordu → React "Function components cannot be given refs" uyarısı
  // (dokuz sayfada). Found by /qa on 2026-09-11. Report: .gstack/qa-reports/qa-report-localhost-2026-09-11.md
  it('ModalScrollHint hook nesnesiyle YAYILMAZ (ref prop olarak sızar); show/scrollMore açık verilir', () => {
    for (const src of [FORM, ...PAGES.map(f => readFileSync(path.join(DIR, f), 'utf8'))]) {
      expect(src).not.toMatch(/<ModalScrollHint\s+\{\.\.\./)
    }
    expect(FORM).toMatch(/<ModalScrollHint\s+show=\{scrollHint\.show\}\s+scrollMore=\{scrollHint\.scrollMore\}/)
  })
})
