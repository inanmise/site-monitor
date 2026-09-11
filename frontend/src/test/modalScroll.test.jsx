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

  // 2026-09-10: DÜZENLEME modalı artık sabit alt barlı (`modal-sticky-actions`): başlık ve
  // [Test][Sil][İptal][Kaydet] barı sabit, yalnız `.modal-scroll-body` kaydırılır, taşınca
  // `ModalScrollHint` ("Devamı için kaydırın") görünür. Dokuz sayfada TEK kopya hook
  // (useModalScrollHint) — kural aşağıda, sayfa başına değil.
  it.each(PAGES)('%s: düzenleme modalı sabit alt barlı (sticky-actions + scroll-body + hint)', (file) => {
    const src = readFileSync(path.join(DIR, file), 'utf8')
    const boxes = src.match(/className="modal-box modal-sticky-actions"/g) || []
    expect(boxes.length, 'tam bir düzenleme modalı sticky olmalı').toBe(1)
    const at = src.indexOf('className="modal-box modal-sticky-actions"')
    const after = src.slice(at)
    const body = after.indexOf('className="modal-scroll-body" ref={scrollHint.ref}')
    const hint = after.indexOf('<ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />')
    const actions = after.indexOf('className="modal-actions"')
    expect(body, 'kaydırılan gövde').toBeGreaterThan(0)
    expect(hint, 'ipucu').toBeGreaterThan(body)
    expect(actions, 'alt bar gövdeden SONRA').toBeGreaterThan(hint)
    // Sticky kutu inline maxHeight/overflowY TAŞIMAZ — inline overflowY:auto CSS'in
    // overflow:hidden'ını ezer ve kutu yine tek parça kaydırılır (alt bar kaçar).
    const window_ = after.slice(0, 260)
    expect(window_).not.toMatch(/overflowY/)
    expect(window_).not.toMatch(/maxHeight/)
    // Hook sayfada çağrılmış (koşullu portal içinde ref boş kalmasın diye callback ref).
    expect(src).toMatch(/const scrollHint = useModalScrollHint\(\)/)
  })

  it.each(PAGES)('%s: geri kalan (ikincil) modal-box kutuları maxHeight + overflowY beyan eder', (file) => {
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
  // Regression: ISSUE-002 — `<ModalScrollHint {...scrollHint} />` yayılımı hook'un `ref`ini fonksiyon
  // bileşenine prop olarak geçiriyordu → React "Function components cannot be given refs" uyarısı
  // (dokuz sayfada). Found by /qa on 2026-09-11. Report: .gstack/qa-reports/qa-report-localhost-2026-09-11.md
  it('ModalScrollHint hook nesnesiyle YAYILMAZ (ref prop olarak sızar); show/scrollMore açık verilir', () => {
    for (const f of PAGES) {
      const src = readFileSync(path.join(DIR, f), 'utf8')
      expect(src, `${f}: {...scrollHint} yayılımı ref'i ModalScrollHint'e geçirir`).not.toMatch(/<ModalScrollHint\s+\{\.\.\./)
      expect(src, `${f}: ModalScrollHint show/scrollMore ile çağrılmalı`).toMatch(/<ModalScrollHint\s+show=\{scrollHint\.show\}\s+scrollMore=\{scrollHint\.scrollMore\}/)
    }
  })

})
