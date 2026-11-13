/**
 * Sorgu planı modeli (2026-11-13, C1) — PostgreSQL `EXPLAIN (FORMAT JSON[, ANALYZE, BUFFERS])` çıktısını ağaca çevirir.
 *
 * Sunucu `plan` (PostgreSQL JSON — `[{ Plan, Planning Time?, Execution Time? }]` ya da tek nesne) ve `summary` döner;
 * özet yoksa (eski / eksik yanıt) buradan hesaplanır. Düğüm sayısı sınırlı ({@link MAX_NODES}) — çok büyük plan
 * kesilir (`truncated`). Paylaşım (`share`): ANALYZE varsa düğümün GERÇEK toplam süresinin kökünkine oranı, yoksa
 * toplam maliyet oranı (0–1).
 */
export const MAX_NODES = 500

const num = (v) => (v == null || v === '' || Number.isNaN(Number(v)) ? null : Number(v))

/** Kök plan nesnesi (`{ Plan, … }`) — dizi / tek nesne / metin JSON kabul edilir. */
export function planRoot(plan) {
  let p = plan
  if (typeof p === 'string') { try { p = JSON.parse(p) } catch { return null } }
  if (Array.isArray(p)) p = p[0]
  if (!p || typeof p !== 'object') return null
  if (p.Plan && typeof p.Plan === 'object') return p
  if (p['Node Type']) return { Plan: p }
  return null
}

/**
 * Düz düğüm listesi (önce-derinlik sırası; görünüm girintiyi `depth`'ten çizer).
 * @returns {{ nodes: Array, truncated: boolean, planningMs: number|null, executionMs: number|null, analyzed: boolean }}
 */
export function flattenPlan(plan) {
  const root = planRoot(plan)
  if (!root) return { nodes: [], truncated: false, planningMs: null, executionMs: null, analyzed: false }
  const nodes = []
  let truncated = false
  const rootNode = root.Plan
  const analyzed = rootNode['Actual Total Time'] != null
  const rootTotal = analyzed ? num(rootNode['Actual Total Time']) : num(rootNode['Total Cost'])
  // Yinelemesiz önce-derinlik (yığın) — derin plan yığın taşırmaz
  const stack = [{ n: rootNode, depth: 0 }]
  while (stack.length) {
    const { n, depth } = stack.pop()
    if (nodes.length >= MAX_NODES) { truncated = true; break }
    const loops = num(n['Actual Loops']) ?? 1
    const actualMs = num(n['Actual Total Time'])
    const total = analyzed ? (actualMs != null ? actualMs * loops : null) : num(n['Total Cost'])
    const share = rootTotal && total != null ? Math.max(0, Math.min(1, total / (analyzed ? rootTotal * (num(rootNode['Actual Loops']) ?? 1) : rootTotal))) : null
    nodes.push({
      id: nodes.length, depth, type: String(n['Node Type'] ?? '?'),
      relation: n['Relation Name'] ?? null, alias: n['Alias'] && n['Alias'] !== n['Relation Name'] ? n['Alias'] : null,
      index: n['Index Name'] ?? null, joinType: n['Join Type'] ?? null, strategy: n['Strategy'] ?? null,
      startupCost: num(n['Startup Cost']), totalCost: num(n['Total Cost']), planRows: num(n['Plan Rows']),
      actualMs, actualRows: num(n['Actual Rows']), loops: n['Actual Loops'] != null ? loops : null,
      filter: n['Filter'] ?? n['Index Cond'] ?? n['Hash Cond'] ?? n['Join Filter'] ?? null,
      rowsRemoved: num(n['Rows Removed by Filter']),
      share,
      seqScan: String(n['Node Type'] ?? '') === 'Seq Scan',
    })
    const kids = Array.isArray(n.Plans) ? n.Plans : []
    for (let i = kids.length - 1; i >= 0; i--) stack.push({ n: kids[i], depth: depth + 1 })
  }
  return {
    nodes, truncated, analyzed,
    planningMs: num(root['Planning Time']), executionMs: num(root['Execution Time']),
  }
}

/**
 * Özet: sunucunun `summary`'si varsa o (eksik alanlar plandan tamamlanır), yoksa plandan.
 * @returns {{ totalCost, planRows, actualMs, nodes, seqScans: [{table, rows}], topNodes: [{nodeType, relation, cost, actualMs, share}] }}
 */
export function planSummary(plan, summary) {
  const flat = flattenPlan(plan)
  const s = summary && typeof summary === 'object' ? summary : {}
  const root = flat.nodes[0]
  const fromPlanSeq = flat.nodes.filter((n) => n.seqScan).map((n) => ({ table: n.relation || n.alias || '?', rows: n.actualRows ?? n.planRows }))
  const fromPlanTop = [...flat.nodes].filter((n) => n.share != null)
    .sort((a, b) => b.share - a.share).slice(0, 5)
    .map((n) => ({ nodeType: n.type, relation: n.relation, cost: n.totalCost, actualMs: n.actualMs, share: n.share }))
  const seq = Array.isArray(s.seq_scans)
    ? s.seq_scans.map((x) => ({ table: x?.table ?? '?', rows: num(x?.rows) }))
    : fromPlanSeq
  const top = Array.isArray(s.top_nodes)
    ? s.top_nodes.map((x) => ({ nodeType: x?.node_type ?? '?', relation: x?.relation ?? null, cost: num(x?.cost), actualMs: num(x?.actual_ms), share: num(x?.share) }))
    : fromPlanTop
  return {
    totalCost: num(s.total_cost) ?? root?.totalCost ?? null,
    planRows: num(s.plan_rows) ?? root?.planRows ?? null,
    actualMs: num(s.actual_ms) ?? flat.executionMs ?? (flat.analyzed ? root?.actualMs ?? null : null),
    nodes: num(s.nodes) ?? flat.nodes.length,
    seqScans: seq,
    topNodes: top,
  }
}
