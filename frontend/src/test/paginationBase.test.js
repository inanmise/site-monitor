import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * SAYFALAMA STANDART KAPISI (2026-09-26) — tüm listeler TEK yapıda: `usePagination` (istemci) ya da
 * `useServerPagination` (sunucu) + `<PaginationBar {...pager} />` / `<PaginationBar {...sp.bar} />`.
 *
 * Kapının doğuşu (S6): `ChangeHistoryTab` 0-tabanlı `useState(0)` sayfa state'ini 1-tabanlı çubuğa
 * dönüşümsüz veriyordu — hiçbir sayfa aktif görünmüyor, "1" API'nin İKİNCİ sayfasına gidiyor, "Son"
 * boş sayfaya ulaşıyordu. Hiçbir test kırılmadı. 2026-09-26 taramasında aynı sınıftan dört hata daha
 * çıktı (mount'ta derin-link sayfası kaybı, seçeneklerde olmayan varsayılan 20, `page > totalPages`,
 * el yapımı çubuklar). Kurallar (tarama: components/, pages/, App.jsx — yorumlar ayıklanarak):
 *
 *  1 keys        el yapımı çubuk metni: `t('x.pageInfo|prevPage|nextPage|perPage')` (pg.* dışı) — ve sözlükte
 *                böyle bir anahtar da kalmaz (ölü anahtar)
 *  2 chevronStep Chevron ikonlu el yapımı ‹ › : `setXPage(p => p - 1)` / `setXPage(page + 1)`
 *  3 slicePage   elle dilimleme `.slice((page - 1) * size …)` — usePagination.pageItems kullanılır
 *  4 barProps    HER `<PaginationBar …/>` yayılımla beslenir; `page=`/`totalPages=`/`rangeStart=`… elle bağlanmaz
 *  5 zeroState   0-tabanlı sayfa state'i (`const [page, setPage] = useState(0)`) ve `readUrlInt(…) - 1`
 *  6 sizeLiteral boyut listesi kaynakta yazılmaz (`sizeOptions: [` / `sizeOptions={[`) — hooks/paginationPresets.js
 *  7 mountReset  `useEffect(() => { setPage(0|1) }, [...])` — mount'ta da koşar, derin bağlantıyı düşürür
 *  8 showMore    tek yönlü `fc-show-more` açılımı sınırsız listede yok — liste sayfalanır
 *  9 previews    çubuksuz sabit boyutlu önizleme (`{ page: 0, size: N }`) yalnız BOUNDED listesindekilerde
 * 10 disclosure "Tümünü göster / +N daha" açılımı (`t('…showAll|showMore')`) yalnız DISCLOSURES'taki sınırlı içerikte
 *
 * PENDING: şu an BAŞKA ajanların üzerinde çalıştığı ve kuralı hâlâ bozan dosyalar (2. aşama göçü,
 * D:\site-monitor-shadcn\.migration\paging-phase2.md). Liste YALNIZ KÜÇÜLÜR: dosya düzelince girdisi
 * silinmek ZORUNDA (bayat girdi testi kırar), yeni dosya eklenmez.
 */
const SRC = path.resolve(__dirname, '..')
const COMPONENTS = path.join(SRC, 'components')

// shrinks only — phase 2 (dosya düzeltilince girdiyi SİL; yeni girdi EKLEME).
// 2026-09-26: 2. aşama bitti — AlertHistory, AuditLogViewer, Push/SmtpLogView, UserManager, UserPushSettings,
// AlertTeamCellModal, CertificatesTable, DeviceHistoryPanel, Change/CheckHistoryTab, TodayListModal, ExpiryForecastPage
// standarda geçti; liste BOŞ. Yeni bir kural ihlali için buraya girdi eklemek kural dışıdır — önce standarda geçir.
const PENDING = {
  chevronStep: [],
  barProps: [],
  zeroState: [],
  sizeLiteral: [],
  mountReset: [],
  showMore: [],
}

// Kalıcı izin: sınırlı önizlemeler (sayfalama gerekmez, gerekçesiyle). Liste yalnız küçülür.
// 2026-09-27: UserDetailPanel çıktı — Değişiklikler sekmesi artık standart useServerPagination + PaginationBar.
const BOUNDED = {}

// Kalıcı izin: "Tümünü göster" açılımları — yalnız SINIRLI içerikte (gerekçesiyle). Sınırsız bir liste açılımla değil
// sayfalamayla gösterilir (ExpiryForecastPage "+N tane daha" 2026-09-26'da PaginationBar'a çevrildi).
const DISCLOSURES = {
  'components/NetworkOutageHistory.jsx': 'ağ kesintisi geçmişi: sunucu son N kaydı döner; katlama (ilk N + süren) sınırlı listeyi açar',
  'components/CertHealthPanel.jsx': '"Yalnız sorunlar / Tümünü göster" süzgeç düğmesi — liste açılımı değil',
  'components/ScriptedMonitorPage.jsx': 'k6 çıktısının son satırları (kırpılmış metin, sunucu tavanlı) — liste değil',
  'components/admin/DeploymentHistoryPanel.jsx': 'sürüm × ortam matrisi `all=true` — sunucu tavanlı tablo, satır listesi değil',
  'components/incidents/ActionNoteDialog.jsx': 'onay penceresinde TEK alarm iletisinin kırpılmış metnini aç/kapa ("Tamamı") — liste değil',
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (/\.jsx?$/.test(e.name)) out.push(p)
  }
  return out
}

/**
 * Yorumları ayıklar (dizeler korunur — `t('…')` anahtarları ve sınıf adları kurallara lazım). Düzeltilmiş
 * dosyalar eski hatayı yorumda anlatıyor ("`useEffect(() => setPage(0), …)` mount'ta koşuyordu"); yorum
 * kural ihlali sayılmamalı. ' ve " dizeleri satır sonunda biter (JSX metnindeki kesme işareti kodu yutmasın).
 */
export function stripComments(src) {
  let out = ''
  let i = 0
  let q = null
  while (i < src.length) {
    const c = src[i]
    const n = src[i + 1]
    if (q) {
      if (c === '\\') { out += c + (n ?? ''); i += 2; continue }
      if (c === q || (c === '\n' && q !== '`')) q = null
      out += c; i++; continue
    }
    if (c === '/' && n === '/') { while (i < src.length && src[i] !== '\n') i++; continue }
    if (c === '/' && n === '*') {
      const e = src.indexOf('*/', i + 2)
      const stop = e < 0 ? src.length : e + 2
      out += src.slice(i, stop).replace(/[^\n]/g, ' ')
      i = stop; continue
    }
    if (c === '"' || c === "'" || c === '`') q = c
    out += c; i++
  }
  return out
}

/** Dosyadaki HER `<PaginationBar … />` etiketinin gövdesi (yalnız ilki değil). */
export function barTags(src) {
  const out = []
  let i = 0
  while ((i = src.indexOf('<PaginationBar', i)) >= 0) {
    const end = src.indexOf('/>', i)
    out.push(src.slice(i, end < 0 ? i + 800 : end))
    i += '<PaginationBar'.length
  }
  return out
}

const HAND_PROPS = ['page', 'totalPages', 'totalItems', 'rangeStart', 'rangeEnd', 'pageSize', 'sizeOptions', 'onPageChange', 'onPageSizeChange']
const uniq = (a) => [...new Set(a)]

export const RULES = {
  keys: (s) => [...s.matchAll(/\bt\(\s*['"`]((?!pg\.)[\w.]+\.(?:pageInfo|prevPage|nextPage|perPage))['"`]/g)].map(m => m[1]),
  chevronStep: (s) => (/\bChevron(?:Left|Right)\b/.test(s)
    ? [...s.matchAll(/\bset\w*Page\w*\(\s*(?:\(?\s*\w+\s*\)?\s*=>\s*)?\w+\s*[-+]\s*1\s*\)/g)].map(m => m[0]) : []),
  slicePage: (s) => [...s.matchAll(/\.slice\(\s*\(?\s*\w*[pP]age\w*\s*(?:-\s*1\s*\)?\s*)?\*/g)].map(m => m[0]),
  barProps: (s) => barTags(s).flatMap(tag => [
    ...(/\{\s*\.\.\.[\w.?]+\s*\}/.test(tag) ? [] : ['yayılım yok']),
    ...HAND_PROPS.filter(p => new RegExp(`\\s${p}=`).test(tag)).map(p => `${p}=`),
  ]),
  zeroState: (s) => [
    ...[...s.matchAll(/const\s*\[\s*(\w+)\s*,\s*set\w+\s*\]\s*=\s*useState\(\s*0\s*\)/g)].filter(m => /page/i.test(m[1])).map(m => m[0]),
    ...[...s.matchAll(/readUrlInt\([^)]*\)\s*-\s*1/g)].map(m => m[0]),
  ],
  sizeLiteral: (s) => [...s.matchAll(/sizeOptions\s*(?:=\s*\{\s*\[|:\s*\[)/g)].map(m => m[0]),
  mountReset: (s) => [...s.matchAll(/useEffect\(\s*\(\)\s*=>\s*\{?\s*set\w*Page\w*\(\s*[01]\s*\)\s*;?\s*\}?\s*,\s*\[/g)].map(m => m[0]),
  showMore: (s) => [...s.matchAll(/\bfc-show-more\b/g)].map(m => m[0]),
  previews: (s) => (s.includes('<PaginationBar') ? [] : [...s.matchAll(/\{\s*page:\s*0\s*,\s*size:\s*\d+\s*\}/g)].map(m => m[0])),
  disclosure: (s) => [...s.matchAll(/\bt\(\s*['"`]([\w.]*(?:[sS]howAll|[sS]howMore))['"`]/g)].map(m => m[1]),
}

const rel = (p) => path.relative(SRC, p).split(path.sep).join('/')
const FILES = [...walk(COMPONENTS), ...walk(path.join(SRC, 'pages')), path.join(SRC, 'App.jsx')]
  .filter(p => !p.endsWith(path.join('ui', 'PaginationBar.jsx')))
  .map(p => ({ file: rel(p), src: stripComments(fs.readFileSync(p, 'utf8')) }))

/** Kural → { dosya: [bulgular] } */
function scan(rule) {
  const out = {}
  for (const { file, src } of FILES) {
    const hits = uniq(RULES[rule](src))
    if (hits.length) out[file] = hits
  }
  return out
}

describe('sayfalama standart kapısı', () => {
  it('tarama gerçekten dosya buluyor (seçici bozulup 0 dosyada sessizce yeşil kalmasın)', () => {
    expect(FILES.length).toBeGreaterThan(100)
    expect(FILES.filter(f => f.src.includes('<PaginationBar')).length).toBeGreaterThanOrEqual(30)
    expect(FILES.some(f => f.file === 'App.jsx')).toBe(true)
    expect(FILES.some(f => f.file.startsWith('pages/'))).toBe(true)
  })

  it('kuralların ısırdığı: her kural kendi örnek ihlalini yakalar, yorumdaki anlatımı YAKALAMAZ', () => {
    const bad = {
      keys: "t('act.pageInfo', 1, 2)",
      chevronStep: "import { ChevronLeft } from 'lucide-react'\nonClick={() => setHistPage((p) => p - 1)}",
      slicePage: 'rows.slice((page - 1) * size, page * size)',
      barProps: '<PaginationBar page={page + 1} totalPages={n} />',
      zeroState: 'const [page, setPage] = useState(0)',
      sizeLiteral: "usePagination(rows, { sizeOptions: [10, 20, 50] })",
      mountReset: 'useEffect(() => { setPage(0) }, [filters, size])',
      showMore: '<button className="fc-show-more">+5</button>',
      previews: "api.history('USER', id, { page: 0, size: 10 })",
      disclosure: "<Button onClick={() => setShowAll(true)}>+{n} {t('forecast.showMore')}</Button>",
    }
    for (const [rule, code] of Object.entries(bad)) {
      expect(RULES[rule](stripComments(code)).length, `${rule} örneği yakalanmadı`).toBeGreaterThan(0)
      expect(RULES[rule](stripComments(`// eskiden: ${code.replace(/\n/g, ' ')}`)), `${rule} yorumu ihlal saydı`).toEqual([])
    }
    // Doğru kullanım temiz: yayılım + compact, kanca çubuğu
    expect(RULES.barProps('<PaginationBar {...pager} compact />')).toEqual([])
    expect(RULES.barProps('<PaginationBar {...sp.bar} />')).toEqual([])
    // Her etiket ayrı ayrı denetlenir (eskiden yalnız ilk etiket okunuyordu)
    expect(RULES.barProps('<PaginationBar {...a} />\n<PaginationBar page={1} />')).toEqual(['yayılım yok', 'page='])
  })

  for (const rule of Object.keys(RULES)) {
    it(`kural ${rule}: PENDING dışında ihlal yok; PENDING'deki her girdi hâlâ ihlal ediyor (liste yalnız küçülür)`, () => {
      const found = scan(rule)
      const pending = new Set(PENDING[rule] || [])
      const allowed = rule === 'previews' ? new Set(Object.keys(BOUNDED))
        : rule === 'disclosure' ? new Set(Object.keys(DISCLOSURES)) : pending
      const offenders = Object.entries(found).filter(([f]) => !allowed.has(f)).map(([f, h]) => `${f} → ${h.join(' | ')}`)
      expect(offenders, `${rule}: standart dışı sayfalama (hooks/usePagination · useServerPagination · PaginationBar)`).toEqual([])
      const stale = [...allowed].filter(f => !found[f])
      expect(stale, `${rule}: bu dosyalar artık temiz — izin listesinden SİLİN`).toEqual([])
    })
  }

  it('sözlükte el yapımı çubuk anahtarı kalmaz (x.pageInfo / prevPage / nextPage / perPage — pg.* hariç)', () => {
    const dict = fs.readFileSync(path.join(SRC, 'i18n', 'index.jsx'), 'utf8')
    const dead = uniq([...dict.matchAll(/^\s*'((?!pg\.)[\w.]+\.(?:pageInfo|prevPage|nextPage|perPage))'\s*:/gm)].map(m => m[1]))
    expect(dead).toEqual([])
  })

  it('ön ayarlar tek kaynaktan: 20 varsayılanı ve [10,20,50,100] / [10,25,50,100] listeleri yok', () => {
    const presets = fs.readFileSync(path.join(SRC, 'hooks', 'paginationPresets.js'), 'utf8')
    expect(presets).toMatch(/page:\s*Object\.freeze\(\{ sizeOptions: Object\.freeze\(\[25, 50, 100, 200\]\), defaultSize: 50/)
    expect(presets).toMatch(/panel:\s*Object\.freeze\(\{ sizeOptions: Object\.freeze\(\[25, 50, 100, 200\]\), defaultSize: 25/)
    expect(presets).toMatch(/modal:\s*Object\.freeze\(\{ sizeOptions: Object\.freeze\(\[10, 25, 50\]\), defaultSize: 10, compact: true/)
    const legacy = FILES.filter(f => /\[\s*10\s*,\s*(?:20|25)\s*,\s*50\s*,\s*100\s*\]|readPageSize\([^)]*,\s*20\s*\)/.test(f.src)
      && !(PENDING.sizeLiteral || []).includes(f.file)).map(f => f.file)
    expect(legacy).toEqual([])
  })

  it('0-tabanlı sayfa state kullanan her dosya HER PaginationBar etiketinde page için + 1 verir (S6)', () => {
    const offenders = []
    for (const { file, src } of FILES) {
      const decl = src.match(/const\s*\[\s*(\w+)\s*,\s*set\w+\s*\]\s*=\s*useState\(\s*0\s*\)/g) || []
      const zeroNames = decl.map(d => d.match(/\[\s*(\w+)\s*,/)[1]).filter(n => /page/i.test(n))
      if (!zeroNames.length) continue
      for (const tag of barTags(src)) {
        const m = tag.match(/\bpage=\{([^}]*)\}/)
        if (!m) continue
        const expr = m[1].trim()
        if (zeroNames.some(n => new RegExp(`\\b${n}\\b`).test(expr)) && !/\+\s*1/.test(expr)) offenders.push(`${file} → page={${expr}}`)
      }
    }
    expect(offenders, '0-tabanlı state, 1-tabanlı PaginationBar için dönüşümsüz veriliyor').toEqual([])
  })

  it('PaginationBar 1-tabanlı kalır (kuralların dayandığı varsayım)', () => {
    // Çubuk bir gün 0-tabanlıya çevrilirse yukarıdaki kurallar TERSİNE döner; bu test onu sessiz bırakmaz.
    const bar = fs.readFileSync(path.join(COMPONENTS, 'ui', 'PaginationBar.jsx'), 'utf8')
    expect(bar).toContain('Math.max(1, p)')
    expect(bar).toContain('page <= 1')
  })
})
