import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * SAYFALAMA TABAN BEKÇİSİ: 0-tabanlı sayfa state'i 1-tabanlı PaginationBar'a dönüşümsüz verilemez.
 *
 * Bu tam olarak yaşandı (`ChangeHistoryTab`): state `useState(0)`, API 0-tabanlı, ama widget'a
 * `page={page}` veriliyordu. PaginationBar 1-tabanlıdır — `clamp = min(max(1, p), totalPages)`,
 * ilk/önceki `page <= 1`'de kapalı. Sonuç sessiz bir navigasyon bozukluğuydu:
 *
 *   - state=0 iken HİÇBİR sayfa numarası aktif görünmüyordu,
 *   - "1" düğmesi API'nin İKİNCİ sayfasına gidiyordu (bir kaydırma),
 *   - "Sonraki"/"Son" son sayfanın ÖTESİNDEKİ boş sayfaya ulaşıyordu.
 *
 * Hiçbir test kırılmadı, hiçbir hata çıkmadı; kullanıcı yalnızca beklediği kayda gidemedi.
 * Widget 9 izleme türünün "Değişiklik Geçmişi" sekmesinde ortak olduğu için etki genişti.
 *
 * Kural: bir dosya sunucu sayfalaması yapıyorsa (sayfa state'i `useState(0)`) ve PaginationBar
 * render ediyorsa, `page` prop'unu MUTLAKA `+ 1` ile vermelidir. İstemci sayfalaması yapanlar
 * (`usePagination` → `pager.page` zaten 1-tabanlı) bu kuralın dışındadır.
 */
const COMPONENTS = path.resolve(__dirname, '../components')

function jsxFiles(dir) {
  const out = []
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) out.push(...jsxFiles(p))
    else if (e.name.endsWith('.jsx')) out.push(p)
  }
  return out
}

/** `<PaginationBar ... page={X} ...>` içindeki page prop ifadesi (yoksa null). */
function pageProp(src) {
  const open = src.indexOf('<PaginationBar')
  if (open < 0) return null
  const close = src.indexOf('/>', open)
  const block = src.slice(open, close < 0 ? open + 800 : close)
  const m = block.match(/\bpage=\{([^}]*)\}/)
  return m ? m[1].trim() : null
}

describe('sayfalama taban sözleşmesi', () => {
  const files = jsxFiles(COMPONENTS)
    .filter(p => !p.endsWith(path.join('ui', 'PaginationBar.jsx')))
    .map(p => ({ p, src: fs.readFileSync(p, 'utf8') }))
    .filter(f => f.src.includes('<PaginationBar'))

  it('taramanın kapsadığı dosya bulunur (tarama boşa düşmesin)', () => {
    // Kapının kendisi de bir kapı: selector bozulup 0 dosya eşleşirse test sessizce yeşil kalırdı.
    expect(files.length).toBeGreaterThanOrEqual(10)
  })

  it("0-tabanli sayfa state kullanan her dosya PaginationBar icin + 1 ile verir", () => {
    const offenders = []
    for (const { p, src } of files) {
      const expr = pageProp(src)
      if (!expr) continue
      // Sayfa state'i 0'dan mı başlıyor? (`const [page, setPage] = useState(0)` — ad serbest)
      const decl = src.match(/const\s*\[\s*(\w+)\s*,\s*set\w+\s*\]\s*=\s*useState\(\s*0\s*\)/g) || []
      const zeroBased = decl.some((d) => {
        const name = d.match(/\[\s*(\w+)\s*,/)[1]
        return /page/i.test(name) && new RegExp(`\\b${name}\\b`).test(expr)
      })
      if (zeroBased && !expr.includes('+ 1') && !expr.includes('+1')) {
        offenders.push(`${path.relative(COMPONENTS, p)} → page={${expr}}`)
      }
    }
    expect(offenders, "0-tabanli state, 1-tabanli PaginationBar icin donusumsuz veriliyor").toEqual([])
  })

  it('PaginationBar 1-tabanlı kalır (kuralın dayandığı varsayım)', () => {
    // Widget bir gün 0-tabanlıya çevrilirse yukarıdaki kural TERSİNE döner; bu test o değişikliği
    // sessiz bırakmaz — kuralın dayanağını da pinler.
    const bar = fs.readFileSync(path.join(COMPONENTS, 'ui', 'PaginationBar.jsx'), 'utf8')
    expect(bar).toContain('Math.max(1, p)')
    expect(bar).toContain('page <= 1')
  })
})
