/**
 * Satır bazlı minimal diff (klasik LCS/DP — Myers değil).
 *
 * <p><b>Neden kendi yazımı:</b> paket ağacında diff kütüphanesi yok ve k6 script'leri birkaç
 * yüz satır; 300×300 = 90 bin hücrelik bir DP göz kırpması. Bunun için bağımlılık eklemek
 * (ve onu güncel tutmak) maliyeti faydasından büyük.
 *
 * <p><b>TAVAN ŞART:</b> DP hafızası O(n×m). 5.000 satırlık iki sürüm 25 milyon hücre demektir
 * ve sekme donar. MAX_DIFF_LINES üstünde DP'ye HİÇ girilmez; yalnız "çok büyük" özeti döner.
 */

/** Üstünde diff hesaplanmayan satır sayısı. Aşılırsa yalnız satır sayıları raporlanır. */
export const MAX_DIFF_LINES = 800

function splitLines(text) {
  if (text == null || text === '') return []
  return String(text).split(/\r\n|\r|\n/)
}

/**
 * İki metni satır bazında karşılaştırır.
 * @returns {{rows: Array, added: number, removed: number, truncated: boolean, oldLines: number, newLines: number}}
 *   rows: `{ type: 'ctx'|'add'|'del', oldNo, newNo, text }`
 */
export function lineDiff(oldText, newText) {
  const a = splitLines(oldText)
  const b = splitLines(newText)

  if (a.length > MAX_DIFF_LINES || b.length > MAX_DIFF_LINES) {
    return { rows: [], added: 0, removed: 0, truncated: true, oldLines: a.length, newLines: b.length }
  }

  // LCS uzunluk tablosu.
  const n = a.length, m = b.length
  const dp = new Uint32Array((n + 1) * (m + 1))
  const at = (i, j) => i * (m + 1) + j
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[at(i, j)] = a[i] === b[j]
        ? dp[at(i + 1, j + 1)] + 1
        : Math.max(dp[at(i + 1, j)], dp[at(i, j + 1)])
    }
  }

  const rows = []
  let added = 0, removed = 0
  let i = 0, j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      rows.push({ type: 'ctx', oldNo: i + 1, newNo: j + 1, text: a[i] }); i++; j++
    } else if (dp[at(i + 1, j)] >= dp[at(i, j + 1)]) {
      rows.push({ type: 'del', oldNo: i + 1, newNo: null, text: a[i] }); removed++; i++
    } else {
      rows.push({ type: 'add', oldNo: null, newNo: j + 1, text: b[j] }); added++; j++
    }
  }
  while (i < n) { rows.push({ type: 'del', oldNo: i + 1, newNo: null, text: a[i] }); removed++; i++ }
  while (j < m) { rows.push({ type: 'add', oldNo: null, newNo: j + 1, text: b[j] }); added++; j++ }

  return { rows, added, removed, truncated: false, oldLines: n, newLines: m }
}

/**
 * Değişikliklerden `pad` satırdan uzaktaki bağlam satırlarını `{type:'gap', count}` ile katlar.
 *
 * <p>Amaç okunabilirlik: 200 satırlık bir script'te tek satır değiştiyse, 199 değişmemiş satırı
 * kaydırarak aramak "ne değişti?" sorusunu cevaplamaz.
 */
export function collapseContext(rows, pad = 3) {
  const keep = new Array(rows.length).fill(false)
  rows.forEach((r, idx) => {
    if (r.type === 'ctx') return
    for (let k = Math.max(0, idx - pad); k <= Math.min(rows.length - 1, idx + pad); k++) keep[k] = true
  })

  const out = []
  let gap = 0
  rows.forEach((r, idx) => {
    if (keep[idx]) {
      if (gap > 0) { out.push({ type: 'gap', count: gap }); gap = 0 }
      out.push(r)
    } else gap++
  })
  if (gap > 0) out.push({ type: 'gap', count: gap })
  return out
}

/**
 * İki ortam-değişkeni listesini AD düzeyinde karşılaştırır.
 *
 * <p>Değer karşılaştırması BİLEREK yok: secret değerleri sunucuda maskeleniyor, yani "değişti mi"
 * sorusuna dürüst cevap veremeyiz. Ad düzeyi ise çoğu zaman gerçek sebep (eksik/fazla değişken).
 */
export function envNameDiff(oldEnv, newEnv) {
  const names = (list) => new Set((Array.isArray(list) ? list : [])
    .map(e => (e && (e.name ?? e.key)) || null).filter(Boolean))
  const a = names(oldEnv), b = names(newEnv)
  return {
    added: [...b].filter(x => !a.has(x)),
    removed: [...a].filter(x => !b.has(x)),
  }
}
