/**
 * Tablo ilişki diyagramı — SAF düzen motoru (DOM yok; birim testli).
 *
 * Girdi: `/admin/sql/relationships` yanıtı `{ tables: [ad], edges: [{ from, column, to, inferred }] }` — `from` tablosunun
 * `column` kolonu `to` tablosuna başvurur (gerçek FK ya da `*_id` çıkarımı). Hiyerarşi: BAŞVURULAN (ebeveyn) tablo
 * üstte/solda, başvuran (çocuk) altta/sağda.
 *
 * Adımlar (Sugiyama benzeri):
 *  1. Döngü kırma — Eades–Lin–Smyth açgözlü geri-besleme yayı kümesi: çok başvurulan tablo (teams) kök kalır,
 *     karşılıklı başvuruda (teams.manager_id ↔ app_users.team_id) az başvurulanın kenarı düşer. Düşen kenar yine ÇİZİLİR,
 *     yalnız katmanlamada yok sayılır.
 *  2. Katman = ebeveyne en uzun yol (başvurmayan tablo 0. katman).
 *  3. Sıralama — barycentre süpürmeleri (aşağı: ebeveynlere, yukarı: çocuklara göre); en az kesişimli sıra saklanır.
 *  4. Yerleşim — katman içinde paketle + ortala; kalabalık katman alt satırlara sarılır (en-boy oranı okunur kalsın);
 *     tek satırlı katmanlarda düğümler komşularının ortasına çekilir (düz kenar), sıra ve boşluk korunur.
 *  5. İlişkisiz tablolar ayrı bir "ilişkisiz tablolar" ızgarasında, diyagramın altında.
 *  6. Kenar yolları — kolon kipinde FK satırından PK satırına yatay teğetli kübik eğri; ad kipinde kutudan kutuya.
 */

export const CARD_W = 248
export const HEADER_H = 44
export const ROW_H = 24
export const BODY_PAD = 6
export const COLLAPSE_AT = 8
export const PAD = 40
export const GROUP_GAP = 72
export const GROUP_LABEL_H = 28

const GAPS = {
  TB: { layer: 96, sub: 40, cross: 28 },
  LR: { layer: 136, sub: 56, cross: 22 },
}

const byName = (a, b) => (a < b ? -1 : a > b ? 1 : 0)

/**
 * İlişki verisini normalize eder: bilinmeyen tabloya giden/yinelenen kenarlar düşer; komşuluk haritaları kurulur.
 * `connected`: en az bir kenara (kendine başvuru dâhil) katılan tablolar; `isolated`: hiçbirine katılmayanlar.
 */
export function buildGraph(data) {
  const tables = [...new Set((data?.tables ?? []).filter((x) => typeof x === 'string' && x))].sort(byName)
  const known = new Set(tables)
  const edges = []
  const seen = new Set()
  for (const e of data?.edges ?? []) {
    if (!e || !known.has(e.from) || !known.has(e.to)) continue
    const id = `${e.from}.${e.column ?? ''}>${e.to}`
    if (seen.has(id)) continue
    seen.add(id)
    edges.push({ id, from: e.from, to: e.to, column: e.column ?? null, inferred: !!e.inferred, self: e.from === e.to })
  }
  const out = new Map(tables.map((t) => [t, []]))
  const inc = new Map(tables.map((t) => [t, []]))
  for (const e of edges) { out.get(e.from).push(e); inc.get(e.to).push(e) }
  const connected = tables.filter((t) => out.get(t).length > 0 || inc.get(t).length > 0)
  const isolated = tables.filter((t) => out.get(t).length === 0 && inc.get(t).length === 0)
  return { tables, edges, out, inc, connected, isolated }
}

/**
 * Döngüsüz kenar kümesi (çocuk → ebeveyn), kendine başvuru hariç. Eades–Lin–Smyth: kaynaklar (kimsenin başvurmadığı)
 * sıranın başına, kuyular (hiçbir şeye başvurmayan) sonuna; kalanlardan (dışa − içe derece) en büyüğü başa. Sırada
 * GERİYE bakan kenar geri-besleme yayıdır → katmanlamadan çıkar.
 */
export function acyclicEdges(nodes, edges) {
  const inSet = new Set(nodes)
  const list = edges.filter((e) => !e.self && inSet.has(e.from) && inSet.has(e.to))
  const pairs = [...new Map(list.map((e) => [`${e.from}>${e.to}`, { from: e.from, to: e.to }])).values()]
  const outs = new Map(nodes.map((n) => [n, new Set()]))
  const ins = new Map(nodes.map((n) => [n, new Set()]))
  for (const p of pairs) { outs.get(p.from).add(p.to); ins.get(p.to).add(p.from) }
  const alive = new Set(nodes)
  const head = []
  const tail = []
  const remove = (n) => {
    alive.delete(n)
    for (const m of outs.get(n)) ins.get(m).delete(n)
    for (const m of ins.get(n)) outs.get(m).delete(n)
  }
  const sorted = () => [...alive].sort(byName)
  while (alive.size) {
    let changed = true
    while (changed) {
      changed = false
      for (const n of sorted()) if (alive.has(n) && outs.get(n).size === 0) { tail.unshift(n); remove(n); changed = true }
      for (const n of sorted()) if (alive.has(n) && ins.get(n).size === 0) { head.push(n); remove(n); changed = true }
    }
    if (!alive.size) break
    let best = null
    let bestScore = -Infinity
    for (const n of sorted()) {
      const s = outs.get(n).size - ins.get(n).size
      if (s > bestScore) { best = n; bestScore = s }
    }
    head.push(best)
    remove(best)
  }
  const rank = new Map([...head, ...tail].map((n, i) => [n, i]))
  return pairs.filter((p) => rank.get(p.from) < rank.get(p.to))
}

/** Katman: hiçbir şeye başvurmayan 0; çocuk = 1 + ebeveynlerinin en büyük katmanı (döngüsüz kümede). */
export function assignLayers(nodes, dag) {
  const parents = new Map(nodes.map((n) => [n, []]))
  for (const e of dag) parents.get(e.from)?.push(e.to)
  const level = new Map()
  const visit = (n, guard = new Set()) => {
    if (level.has(n)) return level.get(n)
    if (guard.has(n)) return 0
    guard.add(n)
    let m = 0
    for (const p of parents.get(n) || []) m = Math.max(m, visit(p, guard) + 1)
    guard.delete(n)
    level.set(n, m)
    return m
  }
  nodes.forEach((n) => visit(n))
  const layers = []
  for (const n of [...nodes].sort(byName)) (layers[level.get(n)] ??= []).push(n)
  return layers.filter(Boolean)
}

/** İki komşu katman arasındaki kenar kesişimi sayısı (sıralama kalitesi). */
function crossings(layers, dag) {
  const layerOf = new Map()
  const idx = new Map()
  layers.forEach((l, L) => l.forEach((n, i) => { layerOf.set(n, L); idx.set(n, i) }))
  let total = 0
  for (let L = 1; L < layers.length; L++) {
    const es = dag.filter((e) => layerOf.get(e.from) === L && layerOf.get(e.to) === L - 1)
      .map((e) => [idx.get(e.from), idx.get(e.to)])
    for (let i = 0; i < es.length; i++) {
      for (let j = i + 1; j < es.length; j++) {
        if ((es[i][0] - es[j][0]) * (es[i][1] - es[j][1]) < 0) total++
      }
    }
  }
  return total
}

/** Barycentre sıralaması (yerinde değil — yeni dizi döner). Kenarlar tüm katmanlar arası sayılır (uzun kenar dâhil). */
export function orderLayers(layers, dag, iterations = 8) {
  const parents = new Map()
  const children = new Map()
  layers.flat().forEach((n) => { parents.set(n, []); children.set(n, []) })
  for (const e of dag) { parents.get(e.from)?.push(e.to); children.get(e.to)?.push(e.from) }
  let cur = layers.map((l) => [...l])
  const pos = new Map()
  const index = (l) => l.forEach((n, i) => pos.set(n, (i + 0.5) / l.length))
  cur.forEach(index)
  let best = cur.map((l) => [...l])
  let bestX = crossings(best, dag)
  for (let it = 0; it < iterations && bestX > 0; it++) {
    const down = it % 2 === 0
    const order = cur.map((_, i) => (down ? i : cur.length - 1 - i))
    for (const L of order) {
      const layer = cur[L]
      const bc = new Map(layer.map((n) => {
        const nb = (down ? parents : children).get(n) || []
        return [n, nb.length ? nb.reduce((s, x) => s + pos.get(x), 0) / nb.length : pos.get(n)]
      }))
      layer.sort((a, b) => (bc.get(a) - bc.get(b)) || (pos.get(a) - pos.get(b)))
      index(layer)
    }
    const x = crossings(cur, dag)
    if (x < bestX) { bestX = x; best = cur.map((l) => [...l]) }
  }
  return best
}

/**
 * Kartın satırları (kip × kolon verisi). `names`: yalnız başlık; `keys`: PK (`id`, başvurulan ya da kolonu bilinen
 * tabloda) + FK satırları (ilişkiden — ek istek yok); `columns`: tüm kolonlar (PK, FK, diğerleri), COLLAPSE_AT'ten
 * uzunsa "N kolon daha" satırıyla katlanır. Kolonlar henüz gelmediyse `keys` satırlarıyla `partial: true` döner.
 */
export function nodeRows(name, graph, { mode = 'keys', columns = null, expanded = null, pkCols = null } = {}) {
  if (mode === 'names') return { rows: [], more: 0, partial: false }
  const outs = [...(graph.out.get(name) || [])].sort((a, b) => byName(a.column ?? '', b.column ?? ''))
  const fk = new Map(outs.filter((e) => e.column).map((e) => [e.column, e]))
  const cols = columns?.[name] ?? null
  const pk = pkCols?.[name] ? new Set(pkCols[name]) : null
  const isPk = (c) => (pk ? pk.has(c) : c === 'id')
  const typeOf = (c) => cols?.find((x) => x.column_name === c)?.data_type ?? null
  if (mode === 'keys' || !cols) {
    const rows = []
    const referenced = (graph.inc.get(name) || []).length > 0
    const pkNames = cols ? cols.map((c) => c.column_name).filter(isPk) : (referenced ? ['id'] : [])
    for (const c of pkNames) rows.push({ name: c, kind: 'pk', type: typeOf(c) })
    for (const e of outs) {
      if (e.column && !pkNames.includes(e.column)) rows.push({ name: e.column, kind: 'fk', target: e.to, inferred: e.inferred, type: typeOf(e.column) })
    }
    return { rows, more: 0, partial: mode === 'columns' && !cols }
  }
  const rank = (c) => (isPk(c.column_name) ? 0 : fk.has(c.column_name) ? 1 : 2)
  const all = cols
    .map((c, i) => ({ c, i }))
    .sort((a, b) => (rank(a.c) - rank(b.c)) || (a.i - b.i))
    .map(({ c }) => {
      const e = fk.get(c.column_name)
      return {
        name: c.column_name, kind: isPk(c.column_name) ? 'pk' : e ? 'fk' : 'col', type: c.data_type ?? null,
        nullable: c.is_nullable === 'YES', target: e?.to ?? null, inferred: e?.inferred ?? false,
      }
    })
  if (all.length <= COLLAPSE_AT + 1) return { rows: all, more: 0, partial: false }
  if (expanded?.has?.(name)) return { rows: all, more: 0, partial: false, collapsible: true }
  return { rows: all.slice(0, COLLAPSE_AT), more: all.length - COLLAPSE_AT, partial: false }
}

/** Kart yüksekliği satır sayısından. */
export function cardHeight(model) {
  const n = model.rows.length + (model.more || model.collapsible ? 1 : 0)
  return HEADER_H + (n ? n * ROW_H + BODY_PAD * 2 : 0)
}

/** Kart içindeki satırın dikey merkezi (kart üstüne göre). */
export function rowCenter(i) {
  return HEADER_H + BODY_PAD + i * ROW_H + ROW_H / 2
}

/**
 * Konumlar. `sizeOf(name) → { w, h }`. Dönüş: `{ nodes: Map(ad → { name, x, y, w, h, layer, isolated }), layers,
 * width, height, isolatedLabel: { x, y } | null }`.
 */
export function layoutDiagram(graph, sizeOf, { direction = 'TB', includeIsolated = true } = {}) {
  const TB = direction !== 'LR'
  const g = TB ? GAPS.TB : GAPS.LR
  const cross = (n) => (TB ? sizeOf(n).w : sizeOf(n).h)
  const mainOf = (n) => (TB ? sizeOf(n).h : sizeOf(n).w)
  const nodesList = graph.connected
  const dag = acyclicEdges(nodesList, graph.edges)
  const layers = orderLayers(assignLayers(nodesList, dag), dag)
  const cap = Math.max(4, Math.ceil(Math.sqrt(Math.max(1, nodesList.length)) * 1.8))

  // Kalabalık katman DENGELİ alt satırlara bölünür (9 düğüm, tavan 8 → 5 + 4; 8 + 1 değil).
  const rows = []
  layers.forEach((names, L) => {
    const parts = Math.ceil(names.length / cap)
    const size = Math.ceil(names.length / parts)
    for (let i = 0; i < names.length; i += size) rows.push({ layer: L, names: names.slice(i, i + size), wrapped: parts > 1 })
  })
  const len = (names) => names.reduce((s, n) => s + cross(n), 0) + g.cross * Math.max(0, names.length - 1)
  const maxCross = Math.max(0, ...rows.map((r) => len(r.names)))
  const start = new Map()   // ad → çapraz eksen başlangıcı
  const mainPos = new Map() // ad → ana eksen başlangıcı
  let main = PAD
  rows.forEach((r, i) => {
    if (i > 0) main += rows[i - 1].layer === r.layer ? g.sub : g.layer
    let c = PAD + (maxCross - len(r.names)) / 2
    for (const n of r.names) { start.set(n, c); mainPos.set(n, main); c += cross(n) + g.cross }
    main += Math.max(...r.names.map(mainOf))
  })

  // Tek satırlı katmanlarda düğümleri komşularının ortasına çek (sıra + boşluk korunur, sınır içinde kalır).
  const nb = new Map(nodesList.map((n) => [n, []]))
  for (const e of dag) { nb.get(e.from).push(e.to); nb.get(e.to).push(e.from) }
  const centre = (n) => start.get(n) + cross(n) / 2
  for (let pass = 0; pass < 4; pass++) {
    for (const r of rows) {
      if (r.wrapped || r.names.length === 0) continue
      const want = r.names.map((n) => {
        const ns = nb.get(n)
        return ns.length ? ns.reduce((s, x) => s + centre(x), 0) / ns.length - cross(n) / 2 : start.get(n)
      })
      const s = []
      let prevEnd = -Infinity
      r.names.forEach((n, i) => { s[i] = Math.max(want[i], prevEnd + g.cross); prevEnd = s[i] + cross(n) })
      let nextStart = PAD + maxCross + g.cross
      for (let i = r.names.length - 1; i >= 0; i--) {
        s[i] = Math.min(s[i], nextStart - g.cross - cross(r.names[i]))
        nextStart = s[i]
      }
      const shift = s[0] < PAD ? PAD - s[0] : 0
      r.names.forEach((n, i) => start.set(n, s[i] + shift))
    }
  }

  const nodes = new Map()
  const layerOf = new Map()
  layers.forEach((l, L) => l.forEach((n) => layerOf.set(n, L)))
  for (const n of nodesList) {
    const { w, h } = sizeOf(n)
    nodes.set(n, {
      name: n, w, h, layer: layerOf.get(n), isolated: false,
      x: TB ? start.get(n) : mainPos.get(n), y: TB ? mainPos.get(n) : start.get(n),
    })
  }
  let width = PAD * 2 + (TB ? maxCross : Math.max(0, main - PAD))
  let height = PAD * 2 + (TB ? Math.max(0, main - PAD) : maxCross)
  if (!nodesList.length) { width = PAD * 2; height = PAD }

  let isolatedLabel = null
  if (includeIsolated && graph.isolated.length) {
    const top = nodesList.length ? height - PAD + GROUP_GAP : PAD
    isolatedLabel = { x: PAD, y: top }
    const avail = Math.max(width - PAD * 2, CARD_W * 4 + GAPS.TB.cross * 3)
    const perRow = Math.max(1, Math.floor((avail + GAPS.TB.cross) / (CARD_W + GAPS.TB.cross)))
    let y = top + GROUP_LABEL_H
    for (let i = 0; i < graph.isolated.length; i += perRow) {
      const chunk = graph.isolated.slice(i, i + perRow)
      let x = PAD
      let rowH = 0
      for (const n of chunk) {
        const { w, h } = sizeOf(n)
        nodes.set(n, { name: n, w, h, layer: null, isolated: true, x, y })
        x += w + GAPS.TB.cross
        rowH = Math.max(rowH, h)
      }
      y += rowH + GAPS.TB.sub
    }
    width = Math.max(width, PAD * 2 + Math.min(perRow, graph.isolated.length) * (CARD_W + GAPS.TB.cross) - GAPS.TB.cross)
    height = y - GAPS.TB.sub + PAD
  }
  return { nodes, layers, width, height, isolatedLabel, direction: TB ? 'TB' : 'LR' }
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

/**
 * Kenar yolları. `rowsOf(ad) → nodeRows(...)` sonucu. Kolonlu kipte FK satırından (kaynak) PK satırına (hedef)
 * yatay teğetli eğri; kart üst üste (aynı sütun) ise ikisi de dış kenardan döner. Ad kipinde kutu kenarından kutu
 * kenarına (TB: dikey, LR: yatay). Kendine başvuru kartın sağında küçük bir döngü.
 */
export function routeEdges(graph, lay, rowsOf) {
  const TB = lay.direction !== 'LR'
  const out = []
  for (const e of graph.edges) {
    const a = lay.nodes.get(e.from)
    const b = lay.nodes.get(e.to)
    if (!a || !b) continue
    const ra = rowsOf(e.from)
    const rb = rowsOf(e.to)
    const hasRows = ra.rows.length + rb.rows.length > 0
    const srcIdx = ra.rows.findIndex((r) => r.kind === 'fk' && r.name === e.column)
    const srcY = srcIdx >= 0 ? rowCenter(srcIdx) : ra.more ? rowCenter(ra.rows.length) : HEADER_H / 2
    const dstIdx = rb.rows.findIndex((r) => r.kind === 'pk')
    const dstY = dstIdx >= 0 ? rowCenter(dstIdx) : HEADER_H / 2
    let sx, sy, tx, ty, d
    if (e.self) {
      sx = a.x + a.w; sy = a.y + srcY; tx = a.x + a.w; ty = a.y + (dstY === srcY ? Math.max(12, srcY - ROW_H) : dstY)
      const bulge = 40
      d = `M ${sx} ${sy} C ${sx + bulge} ${sy}, ${tx + bulge} ${ty}, ${tx} ${ty}`
    } else if (!hasRows) {
      const acx = a.x + a.w / 2, acy = a.y + a.h / 2, bcx = b.x + b.w / 2, bcy = b.y + b.h / 2
      if (TB && b.y + b.h <= a.y) { sx = acx; sy = a.y; tx = bcx; ty = b.y + b.h }
      else if (TB && b.y >= a.y + a.h) { sx = acx; sy = a.y + a.h; tx = bcx; ty = b.y }
      else if (!TB && b.x + b.w <= a.x) { sx = a.x; sy = acy; tx = b.x + b.w; ty = bcy }
      else if (!TB && b.x >= a.x + a.w) { sx = a.x + a.w; sy = acy; tx = b.x; ty = bcy }
      else if (bcx < acx) { sx = a.x; sy = acy; tx = b.x + b.w; ty = bcy }
      else { sx = a.x + a.w; sy = acy; tx = b.x; ty = bcy }
      const vertical = sx === acx && tx === bcx
      if (vertical) {
        const k = Math.max(24, Math.abs(ty - sy) / 2)
        const dir = ty < sy ? -1 : 1
        d = `M ${sx} ${sy} C ${sx} ${sy + dir * k}, ${tx} ${ty - dir * k}, ${tx} ${ty}`
      } else {
        const k = Math.max(24, Math.abs(tx - sx) / 2)
        const dir = tx < sx ? -1 : 1
        d = `M ${sx} ${sy} C ${sx + dir * k} ${sy}, ${tx - dir * k} ${ty}, ${tx} ${ty}`
      }
    } else {
      sy = a.y + srcY
      ty = b.y + dstY
      let sDir, tDir
      if (b.x + b.w + 16 <= a.x) { sDir = -1; tDir = 1 }
      else if (b.x >= a.x + a.w + 16) { sDir = 1; tDir = -1 }
      else { sDir = a.x + a.w / 2 < lay.width / 2 ? -1 : 1; tDir = sDir }
      sx = sDir < 0 ? a.x : a.x + a.w
      tx = tDir < 0 ? b.x : b.x + b.w
      const k = sDir !== tDir ? clamp(Math.abs(tx - sx) / 2, 28, 180) : clamp(Math.abs(ty - sy) / 4 + 40, 40, 140)
      d = `M ${sx} ${sy} C ${sx + sDir * k} ${sy}, ${tx + tDir * k} ${ty}, ${tx} ${ty}`
    }
    out.push({ ...e, d, sx, sy, tx, ty })
  }
  return out
}

/**
 * Tek çağrıda tam model: kartlar (satırlarla), konumlar, kenarlar, sınırlar, sayımlar.
 * @param {object} data  ilişki yanıtı
 * @param {object} opts  { mode, direction, columns, expanded, pkCols, includeIsolated, rowCounts }
 */
export function computeDiagram(data, opts = {}) {
  const graph = buildGraph(data)
  const models = new Map()
  const rowsOf = (n) => {
    if (!models.has(n)) models.set(n, nodeRows(n, graph, opts))
    return models.get(n)
  }
  const sizeOf = (n) => ({ w: CARD_W, h: cardHeight(rowsOf(n)) })
  const lay = layoutDiagram(graph, sizeOf, { direction: opts.direction, includeIsolated: opts.includeIsolated !== false })
  const edges = routeEdges(graph, lay, rowsOf)
  const nodes = [...lay.nodes.values()].map((n) => ({
    ...n, ...rowsOf(n.name), liveRows: opts.rowCounts?.[n.name] ?? null,
    refs: (graph.out.get(n.name) || []).filter((e) => !e.self).length,
    refdBy: (graph.inc.get(n.name) || []).filter((e) => !e.self).length,
  }))
  return {
    graph, nodes, edges, width: lay.width, height: lay.height, layers: lay.layers, isolatedLabel: lay.isolatedLabel,
    direction: lay.direction,
    stats: {
      tables: graph.tables.length, connected: graph.connected.length, isolated: graph.isolated.length,
      edges: graph.edges.length, real: graph.edges.filter((e) => !e.inferred).length, inferred: graph.edges.filter((e) => e.inferred).length,
    },
  }
}

/** Seçili tablonun doğrudan komşuları (başvurduğu + ona başvuranlar), kendisi dâhil. */
export function neighbourhood(graph, name) {
  if (!name || !graph?.out?.has(name)) return null
  const set = new Set([name])
  for (const e of graph.out.get(name)) set.add(e.to)
  for (const e of graph.inc.get(name)) set.add(e.from)
  return set
}
