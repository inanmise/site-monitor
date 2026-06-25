import { useMemo } from 'react'
import { Network, Loader2 } from 'lucide-react'

const NODE_W = 158
const NODE_H = 30
const GAP_X = 26
const GAP_Y = 64
const PAD = 28

/**
 * SQL Playground — tablolar arası İLİŞKİ (hiyerarşi) diyagramı.
 * Saf SVG; bağımlılık yok. Katmanlı (Sugiyama-lite) düzen: referans EDİLEN (üst/parent) tablolar
 * üstte, referans EDEN (alt/child: log/check tabloları) altta. Düz çizgi = gerçek FK, kesik çizgi =
 * `*_id` kolonundan ÇIKARIM (bu şemada DB seviyesinde gerçek FK yok — ilişkiler örtük).
 */
export default function SchemaDiagramModal({ data, loading, onClose, t }) {
  const { nodes, edges, width, height, isolatedCount } = useMemo(() => layout(data), [data])

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box modal-wide sqlpg-diagram-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--user">
          <div className="modal-icon-hdr-badge"><Network size={20} /></div>
          <h3>{t('sql.diag.title')}</h3>
        </div>

        <div className="sqlpg-diag-legend">
          <span><i className="sqlpg-diag-lg-line" /> {t('sql.diag.realFk')}</span>
          <span><i className="sqlpg-diag-lg-line sqlpg-diag-lg-inf" /> {t('sql.diag.inferred')}</span>
          <span className="sqlpg-diag-lg-note">{t('sql.diag.note')}</span>
        </div>

        {loading ? (
          <div className="sqlpg-td-loading"><Loader2 size={18} className="spin" /> {t('sql.td.loading')}</div>
        ) : nodes.length === 0 ? (
          <div className="sqlpg-td-empty">{t('sql.diag.empty')}</div>
        ) : (
          <div className="sqlpg-diag-canvas">
            <svg width={width} height={height} className="sqlpg-diag-svg">
              <defs>
                <marker id="sqlpg-arrow" markerWidth="9" markerHeight="9" refX="7" refY="4"
                        orient="auto" markerUnits="userSpaceOnUse">
                  <path d="M0,0 L8,4 L0,8 z" className="sqlpg-diag-arrowhead" />
                </marker>
                <marker id="sqlpg-arrow-inf" markerWidth="9" markerHeight="9" refX="7" refY="4"
                        orient="auto" markerUnits="userSpaceOnUse">
                  <path d="M0,0 L8,4 L0,8 z" className="sqlpg-diag-arrowhead sqlpg-diag-arrowhead-inf" />
                </marker>
              </defs>

              {edges.map((e, i) => {
                if (e.from === e.to) return null   // self-ref (ör. manager_id) — diyagramda çizilmez
                const x1 = e.p1.x + NODE_W / 2, y1 = e.p1.y                 // child üst-orta
                const x2 = e.p2.x + NODE_W / 2, y2 = e.p2.y + NODE_H        // parent alt-orta
                const my = (y1 + y2) / 2
                const d = `M ${x1} ${y1} C ${x1} ${my}, ${x2} ${my}, ${x2} ${y2}`
                return (
                  <path key={i} d={d}
                        className={`sqlpg-diag-edge${e.inferred ? ' sqlpg-diag-edge-inf' : ''}`}
                        markerEnd={`url(#sqlpg-arrow${e.inferred ? '-inf' : ''})`}>
                    <title>{`${e.from}.${e.column} → ${e.to}`}</title>
                  </path>
                )
              })}

              {nodes.map((n) => (
                <g key={n.name} transform={`translate(${n.x},${n.y})`}>
                  <rect width={NODE_W} height={NODE_H} rx="6"
                        className={`sqlpg-diag-node${n.refdBy >= 3 ? ' sqlpg-diag-node-hub' : ''}`} />
                  <text x={NODE_W / 2} y={NODE_H / 2 + 4} textAnchor="middle" className="sqlpg-diag-node-label">
                    {n.name}
                  </text>
                  <title>{t('sql.diag.nodeTip', n.name, n.refdBy, n.refs)}</title>
                </g>
              ))}
            </svg>
          </div>
        )}

        <div className="modal-actions">
          {isolatedCount > 0 && (
            <span className="sqlpg-diag-isolated">{t('sql.diag.isolated', isolatedCount)}</span>
          )}
          <button className="btn btn-secondary" onClick={onClose}>{t('sql.closeRowDetails')}</button>
        </div>
      </div>
    </div>
  )
}

/** Katmanlı düzen hesabı: seviye = bir sink'e (referans etmeyen tabloya) en uzun çıkış yolu. */
function layout(data) {
  const tables = data?.tables ?? []
  const rawEdges = (data?.edges ?? []).filter((e) => e && e.from && e.to)

  const tableSet = new Set(tables)
  const targetsOf = new Map()
  tables.forEach((t) => targetsOf.set(t, new Set()))
  rawEdges.forEach((e) => {
    if (e.from !== e.to && targetsOf.has(e.from) && tableSet.has(e.to)) targetsOf.get(e.from).add(e.to)
  })

  const level = new Map()
  const visiting = new Set()
  const lvl = (tname) => {
    if (level.has(tname)) return level.get(tname)
    if (visiting.has(tname)) return 0          // döngü kır (ör. karşılıklı *_id)
    visiting.add(tname)
    let m = 0
    for (const tgt of targetsOf.get(tname) || []) m = Math.max(m, 1 + lvl(tgt))
    visiting.delete(tname)
    level.set(tname, m)
    return m
  }

  // Sadece en az bir kenara dahil olan tablolar çizilir (izole olanlar diyagramı kalabalıklaştırmasın).
  const connected = new Set()
  rawEdges.forEach((e) => { if (tableSet.has(e.from)) connected.add(e.from); if (tableSet.has(e.to)) connected.add(e.to) })
  const shown = tables.filter((tname) => connected.has(tname))
  shown.forEach(lvl)

  const refdBy = new Map()
  shown.forEach((tname) => refdBy.set(tname, 0))
  rawEdges.forEach((e) => { if (e.from !== e.to && refdBy.has(e.to)) refdBy.set(e.to, refdBy.get(e.to) + 1) })

  const byLevel = new Map()
  shown.forEach((tname) => {
    const L = level.get(tname) || 0
    if (!byLevel.has(L)) byLevel.set(L, [])
    byLevel.get(L).push(tname)
  })
  const levels = [...byLevel.keys()].sort((a, b) => a - b)
  byLevel.forEach((row) => row.sort((a, b) => (refdBy.get(b) - refdBy.get(a)) || a.localeCompare(b)))

  let maxRowW = 0
  levels.forEach((L) => {
    const row = byLevel.get(L)
    maxRowW = Math.max(maxRowW, row.length * (NODE_W + GAP_X) - GAP_X)
  })

  const pos = new Map()
  levels.forEach((L, rowIdx) => {
    const row = byLevel.get(L)
    const rowW = row.length * (NODE_W + GAP_X) - GAP_X
    const startX = PAD + (maxRowW - rowW) / 2
    row.forEach((tname, i) => {
      pos.set(tname, { x: startX + i * (NODE_W + GAP_X), y: PAD + rowIdx * (NODE_H + GAP_Y) })
    })
  })

  const nodes = shown.map((tname) => ({
    name: tname, ...pos.get(tname),
    refs: (targetsOf.get(tname) || new Set()).size,
    refdBy: refdBy.get(tname) || 0,
  }))
  const edges = rawEdges
    .filter((e) => pos.has(e.from) && pos.has(e.to))
    .map((e) => ({ ...e, p1: pos.get(e.from), p2: pos.get(e.to) }))

  const width = Math.max(maxRowW + PAD * 2, 320)
  const height = PAD * 2 + (levels.length > 0 ? (levels.length - 1) * (NODE_H + GAP_Y) + NODE_H : 0)
  return { nodes, edges, width, height, isolatedCount: tables.length - shown.length }
}
