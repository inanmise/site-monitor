/**
 * Kişisel Panom TV / duvar ekranı yerleşimi (2026-10-13, kullanıcı isteği: "TV modunda bir ekranda daha fazla kutu olsun,
 * büyüklüğü ona göre ayarlansın, boş alanlar kullanılsın") — saf model.
 *
 * - Izgara ekrandan hesaplanır: yoğunluğa göre en küçük hücre (Rahat ≈ eski 4 × 2, Sık, Çok sık) → en çok sütun × satır.
 * - Kutular sayfalara İKİ BOYUTLU ilk-uyan yerleşimle dizilir (satır satır, soldan sağa — CSS `dense` ile aynı kural);
 *   ayrıntılı kutu (liste / ızgara — renkli panel değil) okunabilmek için birden çok SATIR kaplar.
 * - Her sayfa kendi ızgarasını seçer: o sayfanın kutularını TEK sayfaya sığdıran en büyük hücreli ızgara (en az hücre),
 *   hücre en-boy oranı makul aralıkta. Az kutulu son sayfa büyük hücrelerle ekranı doldurur; boş hücre kalmaz ya da en aza iner.
 * Tüm hesaplar sınırlı: sütun ≤ 12, satır ≤ 8, kutu ≤ 24 (pano sınırı) → aday ızgara ≤ 96.
 */

export const TV_DENSITIES = Object.freeze(['comfortable', 'compact', 'dense'])
export const TV_DEFAULT_DENSITY = 'compact'
/** Yoğunluğa göre en küçük hücre (px). Rahat = eski TV düzeni (1920 × 1080'de 4 × 2). */
export const TV_CELL = Object.freeze({
  comfortable: Object.freeze({ w: 420, h: 440 }),
  compact: Object.freeze({ w: 300, h: 260 }),
  dense: Object.freeze({ w: 240, h: 240 }),   // 200 px'te 1366 × 768'de iki satırlık etiket + bayat çipi sığmıyordu
})
/** Hücreler arası boşluk (px) — sayfa ızgarasının `gap`i ile aynı. */
export const TV_GAP = 16
/** Ayrıntılı (liste / ızgara) kutunun okunur en küçük yüksekliği (px) — daha alçak hücrede birden çok satır kaplar. */
export const TV_DETAIL_MIN_H = 300
/** Ayrıntılı kutunun okunur en küçük genişliği (px) — dar hücrede birden çok sütun kaplar (tek hücrede ısı haritası sözcükleri
 * ortadan bölüyordu: "Synthet ic Monitor ing"). */
export const TV_DETAIL_MIN_W = 520
export const TV_MAX_COLS = 12
export const TV_MAX_ROWS = 8
/** Hücre en-boy oranı (genişlik ÷ yükseklik) kabul aralığı — çok ince / çok basık hücre seçilmez. */
const ASPECT_MIN = 0.6
const ASPECT_MAX = 3
const ASPECT_IDEAL = 1.6

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

export function normalizeDensity(d) {
  return TV_DENSITIES.includes(d) ? d : TV_DEFAULT_DENSITY
}

/** Kullanılabilir alan + yoğunluk → en çok { cols, rows }. */
export function tvGrid(availW, availH, density) {
  const cell = TV_CELL[normalizeDensity(density)]
  const w = Math.max(0, Number(availW) || 0)
  const h = Math.max(0, Number(availH) || 0)
  return {
    cols: clamp(Math.floor((w + TV_GAP) / (cell.w + TV_GAP)), 1, TV_MAX_COLS),
    rows: clamp(Math.floor((h + TV_GAP) / (cell.h + TV_GAP)), 1, TV_MAX_ROWS),
  }
}

/** Izgaranın hücre boyu (px). */
export function cellSize(availW, availH, cols, rows) {
  return {
    w: (Math.max(0, availW) - (cols - 1) * TV_GAP) / cols,
    h: (Math.max(0, availH) - (rows - 1) * TV_GAP) / rows,
  }
}

/**
 * Kutunun hücre açıklığı. Sık / Çok sık yoğunlukta renkli panel TEK hücre (tek sayı gösterir, geniş olması bir şey katmaz,
 * iki hücrelik paneller ızgarayı parçalayıp sayfa harcıyordu); Rahat yoğunlukta ve ayrıntılı kutuda kullanıcının boyu:
 * sütun sm 1 · md 2 · lg ≤ 4 sütunda tüm satır, daha genişte yarısı (en az 4); satır: ayrıntılı kutu TV_DETAIL_MIN_H'yi
 * karşılayacak kadar (en çok ızgara yüksekliği), renkli panel 1.
 */
export function tvSpan(item, cols, rows, cellH, density = TV_DEFAULT_DENSITY, cellW = Infinity) {
  if (!item?.detail && normalizeDensity(density) !== 'comfortable') return { c: 1, r: 1 }
  const size = item?.size
  let c = size === 'lg' ? (cols <= 4 ? cols : Math.max(4, Math.ceil(cols / 2))) : size === 'md' ? Math.min(2, cols) : 1
  if (item?.detail) c = Math.max(c, Math.ceil(TV_DETAIL_MIN_W / Math.max(1, cellW)))
  const r = item?.detail ? clamp(Math.ceil(TV_DETAIL_MIN_H / Math.max(1, cellH)), 1, rows) : 1
  return { c: clamp(c, 1, cols), r }
}

/**
 * İki boyutlu ilk-uyan yerleşim: her kutu, AÇIK sayfaların ilkinde satır satır soldan sağa ilk boş yere (CSS
 * `grid-auto-flow: row dense` ile aynı kural); hiçbir sayfaya sığmıyorsa yeni sayfa (sıralı kesme son sayfada tek kutu
 * bırakırken önceki sayfada yer kalıyordu). Sayfa içi sıra korunur. Dönüş: sayfalar → [{ item, col, row, c, r }] (1 tabanlı).
 * Boş sayfaya bile sığmayan açıklık sol üste konur (açıklık zaten ızgarayla sınırlı).
 */
export function packTv(items, cols, rows, spanFor) {
  const pages = []   // { grid, placed }
  const fits = (grid, r0, c0, sp) => {
    if (r0 + sp.r > rows || c0 + sp.c > cols) return false
    for (let r = r0; r < r0 + sp.r; r++) for (let c = c0; c < c0 + sp.c; c++) if (grid[r][c]) return false
    return true
  }
  const place = (grid, sp) => {
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) if (fits(grid, r, c, sp)) return { r, c }
    return null
  }
  const fresh = () => {
    const p = { grid: Array.from({ length: rows }, () => Array(cols).fill(false)), placed: [] }
    pages.push(p)
    return p
  }
  for (const item of items || []) {
    const sp = spanFor(item)
    let page = null
    let at = null
    for (const p of pages) { at = place(p.grid, sp); if (at) { page = p; break } }
    if (!page) { page = fresh(); at = place(page.grid, sp) || { r: 0, c: 0 } }
    for (let r = at.r; r < Math.min(rows, at.r + sp.r); r++) for (let c = at.c; c < Math.min(cols, at.c + sp.c); c++) page.grid[r][c] = true
    page.placed.push({ item, row: at.r + 1, col: at.c + 1, r: sp.r, c: sp.c })
  }
  return pages.map((p) => p.placed)
}

/**
 * Bir sayfanın ızgarası: kutuları TEK sayfaya sığdıran, hücresi en büyük (en az hücreli) ızgara; hücre oranı kabul aralığında,
 * eşitlikte ideal orana yakın olan. Hiçbiri yoksa en çok ızgara. Sayfada AYRINTILI kutu (liste / ızgara) varsa sütun sayısı
 * sabit kalır, yalnız satırlar azalıp uzar: listenin içeriği hücreyle büyümez — az sütunda "orta" kutu ekran genişliğine
 * yayılıp çoğu boş kalıyordu (2026-10-13 ekran görüntüsü).
 */
export function fitPage(items, maxCols, maxRows, availW, availH, density = TV_DEFAULT_DENSITY) {
  // Tek kutu her ekranda TÜM alanı alır (dikey ekranda oran kuralı 1 × 2 seçip yarısını boş bırakıyordu)
  if ((items || []).length === 1) return { cols: 1, rows: 1, placed: [{ item: items[0], row: 1, col: 1, c: 1, r: 1 }] }
  if ((items || []).some((it) => it?.detail)) {
    // Ayrıntılı kutulu sayfa: sütunlar sabit, sığdıran EN AZ satır (en uzun hücre, boş satır yok); oran kuralı uygulanmaz
    for (let rows = 1; rows <= maxRows; rows++) {
      const cell = cellSize(availW, availH, maxCols, rows)
      const pages = packTv(items, maxCols, rows, (it) => tvSpan(it, maxCols, rows, cell.h, density, cell.w))
      if (pages.length === 1) return { cols: maxCols, rows, placed: pages[0] }
    }
  }
  let best = null
  for (let cols = 1; cols <= maxCols; cols++) {
    for (let rows = 1; rows <= maxRows; rows++) {
      const cell = cellSize(availW, availH, cols, rows)
      if (cell.w <= 0 || cell.h <= 0) continue
      const aspect = cell.w / cell.h
      if (aspect < ASPECT_MIN || aspect > ASPECT_MAX) continue
      const pages = packTv(items, cols, rows, (it) => tvSpan(it, cols, rows, cell.h, density, cell.w))
      if (pages.length !== 1) continue
      const area = cell.w * cell.h
      const off = Math.abs(Math.log(aspect / ASPECT_IDEAL))
      if (!best || area > best.area + 1 || (Math.abs(area - best.area) <= 1 && off < best.off)) {
        best = { cols, rows, placed: pages[0], area, off }
      }
    }
  }
  if (best) return { cols: best.cols, rows: best.rows, placed: best.placed }
  const cell = cellSize(availW, availH, maxCols, maxRows)
  return { cols: maxCols, rows: maxRows, placed: packTv(items, maxCols, maxRows, (it) => tvSpan(it, maxCols, maxRows, cell.h, density, cell.w))[0] || [] }
}

/**
 * Satır sonundaki boş hücreleri doldurur: bir satırın son kutusu (yalnız o satırda duran, tek satırlık) sağındaki boş
 * hücrelere uzatılır — ızgara bir bütün olarak biter, sağ altta boş delik kalmaz. Yeni dizi döner (girdi değişmez).
 */
export function fillTrailingGaps(placed, cols, rows) {
  const out = placed.map((p) => ({ ...p }))
  const owner = Array.from({ length: rows }, () => Array(cols).fill(null))
  for (const p of out) {
    for (let r = p.row - 1; r < Math.min(rows, p.row - 1 + p.r); r++) {
      for (let c = p.col - 1; c < Math.min(cols, p.col - 1 + p.c); c++) owner[r][c] = p
    }
  }
  for (let r = 0; r < rows; r++) {
    let last = -1
    for (let c = cols - 1; c >= 0; c--) if (owner[r][c]) { last = c; break }
    if (last < 0 || last === cols - 1) continue
    const p = owner[r][last]
    if (p.r !== 1 || p.row - 1 !== r) continue
    p.c += cols - 1 - last
    for (let c = last + 1; c < cols; c++) owner[r][c] = p
  }
  // Tümüyle boş alt satırlar: son dolu satırda biten kutular aşağı uzar (ızgara alttan da boşluksuz biter)
  let lastRow = -1
  for (let r = rows - 1; r >= 0; r--) if (owner[r].some(Boolean)) { lastRow = r; break }
  if (lastRow >= 0 && lastRow < rows - 1) {
    for (const p of out) if (p.row - 1 + p.r - 1 === lastRow) p.r += rows - 1 - lastRow
  }
  return out
}

/**
 * TV yerleşimi: en çok ızgarayla sayfalara böl, sonra her sayfayı kendi en iyi ızgarasına yerleştir.
 * `items`: [{ id, size, detail }] (detail = renkli panel çizilmeyecek kutu). Dönüş: [{ cols, rows, placed }].
 */
export function layoutTv(items, availW, availH, density) {
  const list = (items || []).slice(0, 24)
  if (!list.length) return []
  const { cols, rows } = tvGrid(availW, availH, density)
  const cell = cellSize(availW, availH, cols, rows)
  const pages = packTv(list, cols, rows, (it) => tvSpan(it, cols, rows, cell.h, density, cell.w))
  return pages.map((p) => {
    const page = fitPage(p.map((x) => x.item), cols, rows, availW, availH, density)
    return { ...page, placed: fillTrailingGaps(page.placed, page.cols, page.rows) }
  })
}
