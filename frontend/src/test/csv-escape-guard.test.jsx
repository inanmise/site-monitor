import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * BEKÇİ: CSV kaçışı elle yazılamaz, {@code utils/csv.js} üzerinden geçer.
 *
 * <p><b>Neden var (2026-08-23 denetimi).</b> Projede dört ayrı dışa aktarım (envanter, aktivite,
 * SQL çalışma alanı, sayfa kaynakları) kendi kaçış fonksiyonunu yazmıştı. Üçü {@code \r}'yi
 * kaçırıyordu — tek başına bir CR satırı ortasından böler, çünkü satırlar CRLF ile birleşiyor.
 * Dördü de FORMÜL nötrlemesi yapmıyordu: {@code =}, {@code +}, {@code -}, {@code @} ile başlayan
 * bir hücre Excel/LibreOffice'te formüldür ({@code =cmd|'/c calc'!A1}), yani dosyayı açan kişinin
 * makinesinde komut çalıştırma denemesine dönüşebilir. Backend bu korumayı zaten uyguluyordu
 * ({@code AdminController.csvCell}, gerekçesi javadoc'ta) — arayüz ondan habersizdi.
 *
 * <p><b>Kural.</b> Kaynak ağacında {@code replace(/"/g, '""')} kalıbı yalnız {@code utils/csv.js}
 * içinde geçebilir. Yeni bir dışa aktarım yazan kişi ya ortak yardımcıyı kullanır ya da bu
 * listeye gerekçe yazar.
 */
const SRC = path.resolve(__dirname, '..')
const OWNER = path.join('utils', 'csv.js')

/** Bilinçli istisnalar — buraya satır eklemek "bu kaçış CSV DEĞİL" taahhüdüdür. */
const ALLOW = new Map()

/** CSV tırnak ikileme kalıbı: elle yazılmış bir kaçışın imzası. */
const HAND_ROLLED = /replace\(\s*\/"\/g\s*,\s*['"`]""['"`]\s*\)/

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) { if (e.name !== 'test') walk(p, acc) }
    else if (/\.jsx?$/.test(e.name)) acc.push(p)
  }
  return acc
}

describe('CSV kaçışı tek yerde', () => {
  const files = walk(SRC)

  it('kaynak ağacı taranabiliyor (boş tarama sessiz yeşil demektir)', () => {
    expect(files.length).toBeGreaterThan(100)
  })

  it('elle yazılmış CSV kaçışı kalmadı', () => {
    const hits = []
    for (const f of files) {
      const rel = path.relative(SRC, f)
      if (rel === OWNER || ALLOW.has(rel)) continue
      const text = fs.readFileSync(f, 'utf8')
      if (HAND_ROLLED.test(text)) hits.push(rel)
    }
    expect(hits, 'CSV hücresi için utils/csv.js → csvCell kullanın (formül nötrleme + CR dâhil)').toEqual([])
  })

  it('ortak yardımcı yerinde ve formül nötrlemesi içeriyor', () => {
    const src = fs.readFileSync(path.join(SRC, OWNER), 'utf8')
    expect(src).toMatch(/csvCell/)
    // Nötrleme kaldırılırsa bu satır kırılır: kural yardımcının içinden de sessizce çıkamaz.
    for (const ch of ["'='", "'+'", "'-'", "'@'"]) expect(src).toContain(ch)
  })

  it('kuralın kendisi çalışıyor: elle kaçış kalıbı YAKALANIR', () => {
    expect(HAND_ROLLED.test(`const esc = v => \`"\${String(v).replace(/"/g, '""')}"\``)).toBe(true)
    expect(HAND_ROLLED.test('const x = s.replace(/,/g, ";")')).toBe(false)
  })
})
