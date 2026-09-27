import { forwardRef, memo, useCallback, useEffect, useId, useImperativeHandle, useRef, useState } from 'react'
import { Maximize, Minus, Plus, Scan, Table2 } from 'lucide-react'
import { useDateLocale, useT } from '../../../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { ButtonGroup } from '@/components/shadcn/button-group'
import { cn } from '@/lib/utils'
import { formatCompact, shortType } from '../sqlUtils.js'

export const MIN_ZOOM = 0.15
export const MAX_ZOOM = 2.5
const INITIAL_MIN_ZOOM = 0.35
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

/** Görünüm alanı boyutu (ResizeObserver; jsdom'da no-op → 0). */
function useBox(ref) {
  const [box, setBox] = useState({ w: 0, h: 0 })
  useEffect(() => {
    const el = ref.current
    if (!el) return undefined
    const read = () => setBox((b) => (b.w === el.clientWidth && b.h === el.clientHeight ? b : { w: el.clientWidth, h: el.clientHeight }))
    read()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(read)
    ro.observe(el)
    return () => ro.disconnect()
  }, [ref])
  return box
}

/** Kart satırı: PK/FK rozeti + kolon + (FK hedefi | tip). */
function KeyBadge({ kind }) {
  if (kind === 'pk') return <Badge variant="outline" className="h-4 shrink-0 rounded-sm border-amber-500/40 bg-amber-500/10 px-1 text-[9px] leading-none font-bold text-amber-700 dark:text-amber-300">PK</Badge>
  if (kind === 'fk') return <Badge variant="outline" className="h-4 shrink-0 rounded-sm border-emerald-500/40 bg-emerald-500/10 px-1 text-[9px] leading-none font-bold text-emerald-700 dark:text-emerald-300">FK</Badge>
  return <span aria-hidden="true" className="inline-block w-[22px] shrink-0" />
}

/**
 * Tablo kartı. Başlık gerçek bir shadcn Button ("stretched": `after:` sözde öğesi tüm kartı kaplar → kartın her yerine
 * tıklanır, klavyede Enter/Space paneli açar). "N kolon daha" düğmesi `relative z-10` ile örtünün üstünde.
 * Konum/boyut düzen modelinden; çerçeve `ring` (kutu gölgesi) — kenarlık düzen piksellerini oynatmasın.
 */
const DiagramNode = memo(function DiagramNode({ node, state, expanded, onSelect, onToggleExpand, onReveal }) {
  const t = useT()
  const locale = useDateLocale()
  const rows = node.rows || []
  const rowsText = node.liveRows == null ? null : formatCompact(node.liveRows, locale)
  const canCollapse = !!node.collapsible
  return (
    <div data-slot="diagram-node" data-table={node.name} data-state={state || undefined}
      className={cn(
        'absolute flex flex-col overflow-hidden rounded-[10px] bg-card text-card-foreground shadow-sm ring-1 ring-border transition-[opacity,box-shadow] duration-200 motion-reduce:transition-none',
        node.isolated && 'bg-card/80',
        'data-[state=focus]:shadow-md data-[state=focus]:ring-2 data-[state=focus]:ring-primary data-[state=near]:ring-primary/50 data-[state=dim]:opacity-25',
      )}
      style={{ left: node.x, top: node.y, width: node.w, height: node.h }}>
      <Button type="button" variant="ghost" data-slot="diagram-node-open"
        onClick={() => onSelect(node.name)} onFocus={() => onReveal(node.name)}
        aria-label={t('sql.diag.nodeLabel', node.name, rowsText ?? '—', node.refs ?? 0, node.refdBy ?? 0)}
        className={cn('h-11 w-full shrink-0 justify-start gap-2 rounded-none px-3 hover:bg-muted/60 focus-visible:ring-inset after:absolute after:inset-0',
          (rows.length > 0 || node.more > 0 || canCollapse) && 'border-b')}>
        <Table2 aria-hidden="true" className="size-4 text-muted-foreground" />
        <span className="min-w-0 flex-1 truncate text-left font-mono text-[13px] font-semibold">{node.name}</span>
        {rowsText && <span className="shrink-0 text-[11px] font-normal text-muted-foreground tabular-nums">{t('sql.diag.rowsShort', rowsText)}</span>}
      </Button>
      {(rows.length > 0 || node.more > 0 || canCollapse) && (
        <div className="flex flex-col py-1.5">
          {rows.map((r) => (
            <div key={r.name} data-kind={r.kind} title={r.type ? `${r.name} · ${r.type}` : r.name}
              className="flex h-6 min-w-0 items-center gap-2 px-2.5 text-xs">
              <KeyBadge kind={r.kind} />
              <span className={cn('min-w-0 flex-1 truncate font-mono', r.kind === 'pk' && 'font-semibold')}>{r.name}</span>
              {r.kind === 'fk' && r.target
                ? <span className="max-w-[45%] shrink-0 truncate font-mono text-[10.5px] text-muted-foreground">→ {r.target}</span>
                : r.type && <span className="shrink-0 text-[10.5px] text-muted-foreground">{shortType(r.type)}</span>}
            </div>
          ))}
          {(node.more > 0 || canCollapse) && (
            <Button type="button" variant="ghost" size="xs" aria-expanded={!!expanded}
              onClick={() => onToggleExpand(node.name)}
              aria-label={node.more > 0 ? t('sql.diag.moreColsFor', node.more, node.name) : t('sql.diag.fewerColsFor', node.name)}
              className="relative z-10 h-6 justify-start rounded-none px-2.5 text-[11px] font-normal text-muted-foreground">
              {node.more > 0 ? t('sql.diag.moreCols', node.more) : t('sql.diag.fewerCols')}
            </Button>
          )}
        </div>
      )}
    </div>
  )
})

/** Kenar katmanı (SVG). `top`: yalnız vurgulu kenarlar (kartların ÜSTÜNDEKİ ikinci katman). */
const EdgeLayer = memo(function EdgeLayer({ model, highlight, focus, markerId, top = false, label }) {
  const edges = model.edges.filter((e) => {
    const hl = focus && (e.from === focus || e.to === focus)
    return top ? hl : !hl
  })
  const dimmed = !!highlight
  return (
    <svg aria-hidden={top ? 'true' : undefined} role={top ? undefined : 'img'} aria-label={top ? undefined : label}
      data-slot={top ? 'diagram-edges-focus' : 'diagram-edges'}
      className="pointer-events-none absolute top-0 left-0 overflow-visible" width={model.width} height={model.height}>
      {!top && (
        <defs>
          {['base', 'hl'].map((v) => (
            <g key={v}>
              <marker id={`${markerId}-arrow-${v}`} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9"
                markerUnits="userSpaceOnUse" orient="auto">
                <path d="M0,0 L10,5 L0,10 z" className={v === 'hl' ? 'fill-primary' : 'fill-muted-foreground'} />
              </marker>
              <marker id={`${markerId}-many-${v}`} viewBox="0 0 12 12" refX="12" refY="6" markerWidth="12" markerHeight="12"
                markerUnits="userSpaceOnUse" orient="auto-start-reverse">
                <path d="M0,6 L12,0 M0,6 L12,6 M0,6 L12,12"
                  className={cn('fill-none [stroke-width:1.3]', v === 'hl' ? 'stroke-primary' : 'stroke-muted-foreground')} />
              </marker>
            </g>
          ))}
        </defs>
      )}
      {edges.map((e) => (
        <path key={e.id} d={e.d} data-edge={e.id} data-inferred={e.inferred ? 'true' : undefined}
          data-state={top ? 'focus' : dimmed ? 'dim' : undefined}
          markerEnd={`url(#${markerId}-arrow-${top ? 'hl' : 'base'})`}
          markerStart={`url(#${markerId}-many-${top ? 'hl' : 'base'})`}
          className={cn('fill-none transition-opacity duration-200 motion-reduce:transition-none',
            top ? 'stroke-primary [stroke-width:2]' : 'stroke-muted-foreground/80 [stroke-width:1.4]',
            e.inferred && '[stroke-dasharray:5_4]', !top && dimmed && 'opacity-15')} />
      ))}
    </svg>
  )
})

/** Küçük harita: kartlar + görünüm çerçevesi; tıkla/sürükle → oraya git. Dekoratif (klavye karşılığı var). */
function Minimap({ model, view, box, highlight, onJump }) {
  const scale = Math.min(168 / Math.max(1, model.width), 112 / Math.max(1, model.height))
  const mw = Math.max(40, model.width * scale)
  const mh = Math.max(30, model.height * scale)
  const vx = (-view.x / view.k) * scale
  const vy = (-view.y / view.k) * scale
  const vw = (box.w / view.k) * scale
  const vh = (box.h / view.k) * scale
  const jump = (e) => {
    e.stopPropagation()
    const r = e.currentTarget.getBoundingClientRect()
    onJump((e.clientX - r.left) / scale, (e.clientY - r.top) / scale)
  }
  return (
    <div aria-hidden="true" data-slot="diagram-minimap"
      className="absolute right-3 bottom-3 hidden cursor-pointer rounded-md border bg-background/90 p-1.5 shadow-sm backdrop-blur-sm md:block"
      onPointerDown={(e) => { e.stopPropagation(); e.currentTarget.setPointerCapture?.(e.pointerId); jump(e) }}
      onPointerMove={(e) => { if (e.buttons & 1) jump(e) }}>
      <svg width={mw} height={mh} className="block overflow-hidden">
        {model.nodes.map((n) => (
          <rect key={n.name} x={n.x * scale} y={n.y * scale} width={Math.max(2, n.w * scale)} height={Math.max(2, n.h * scale)} rx="1.5"
            className={highlight?.has(n.name) ? 'fill-primary' : 'fill-muted-foreground/35'} />
        ))}
        <rect x={vx} y={vy} width={vw} height={vh} className="fill-primary/10 stroke-primary [stroke-width:1.2]" />
      </svg>
    </div>
  )
}

/**
 * Kaydırılabilir / yakınlaştırılabilir tuval.
 * - Tekerlek: imleç çevresinde yakınlaştırma (dokunmatik yüzey kıstırması = ctrl+tekerlek, daha hassas).
 * - Sürükle: kaydır (karttan başlasa da — 4 px eşiği aşınca; aşarsa tıklama bastırılır). İki parmak: kıstır-yakınlaştır.
 * - Klavye (tuval odaktayken): oklar kaydırır, + / − yakınlaştırır, 0 %100, F sığdırır.
 * Ref API: fit(), zoomBy(f), reset(), centreOn(ad, { zoom }), view().
 */
const DiagramCanvas = forwardRef(function DiagramCanvas({
  model, focus, highlight, expanded, onSelect, onToggleExpand, fitKey, initialCentre = null, children, className,
}, ref) {
  const t = useT()
  const vpRef = useRef(null)
  const box = useBox(vpRef)
  const boxRef = useRef(box)
  boxRef.current = box
  const [view, setView] = useState({ x: 0, y: 0, k: 1 })
  const viewRef = useRef(view)
  viewRef.current = view
  const [animate, setAnimate] = useState(false)
  const markerId = `dg${useId().replace(/[^a-zA-Z0-9]/g, '')}`
  const modelRef = useRef(model)
  modelRef.current = model

  const apply = useCallback((next, anim = false) => {
    setAnimate(anim)
    setView({ x: next.x, y: next.y, k: clamp(next.k, MIN_ZOOM, MAX_ZOOM) })
  }, [])

  /** Sığdır. `initial`: ilk açılışta okunamayacak kadar küçülmesin — en az %35, üstteki kök katman ortada görünür. */
  const fit = useCallback((anim = true, initial = false) => {
    const { w, h } = boxRef.current
    const m = modelRef.current
    if (!w || !h || !m) return
    const pad = 24
    let k = clamp(Math.min((w - pad * 2) / m.width, (h - pad * 2) / m.height, 1.1), MIN_ZOOM, MAX_ZOOM)
    if (initial && k < INITIAL_MIN_ZOOM) {
      k = INITIAL_MIN_ZOOM
      apply({ k, x: (w - m.width * k) / 2, y: pad }, anim)
      return
    }
    apply({ k, x: (w - m.width * k) / 2, y: Math.max(pad, (h - m.height * k) / 2) }, anim)
  }, [apply])

  const zoomAt = useCallback((factor, px, py, anim = false) => {
    const v = viewRef.current
    const k = clamp(v.k * factor, MIN_ZOOM, MAX_ZOOM)
    const f = k / v.k
    apply({ k, x: px - (px - v.x) * f, y: py - (py - v.y) * f }, anim)
  }, [apply])

  const centreOn = useCallback((name, { zoom = true } = {}) => {
    const n = modelRef.current?.nodes.find((x) => x.name === name)
    const { w, h } = boxRef.current
    if (!n || !w || !h) return
    const k = zoom ? Math.max(viewRef.current.k, 0.9) : viewRef.current.k
    apply({ k, x: w / 2 - (n.x + n.w / 2) * k, y: h / 2 - (n.y + Math.min(n.h, h / k) / 2) * k }, true)
  }, [apply])

  /** Klavye odağı ekran dışındaki karta geçince görünür alana kaydır. */
  const reveal = useCallback((name) => {
    const n = modelRef.current?.nodes.find((x) => x.name === name)
    const { w, h } = boxRef.current
    if (!n || !w || !h) return
    const v = viewRef.current
    const left = n.x * v.k + v.x, top = n.y * v.k + v.y
    const right = left + n.w * v.k, bottom = top + Math.min(n.h, 200) * v.k
    let { x, y } = v
    const m = 16
    if (left < m) x += m - left
    else if (right > w - m) x -= right - (w - m)
    if (top < m) y += m - top
    else if (bottom > h - m) y -= bottom - (h - m)
    if (x !== v.x || y !== v.y) apply({ ...v, x, y }, true)
  }, [apply])

  useImperativeHandle(ref, () => ({
    fit: () => fit(true),
    reset: () => { const { w } = boxRef.current; apply({ k: 1, x: Math.max(24, (w - modelRef.current.width) / 2), y: 24 }, true) },
    zoomBy: (f) => { const { w, h } = boxRef.current; zoomAt(f, w / 2, h / 2, true) },
    centreOn,
    view: () => viewRef.current,
  }), [fit, apply, zoomAt, centreOn])

  // İlk ölçüm + düzen kimliği (yön/kip/ilişkisizler) değişince sığdır.
  const fittedKey = useRef(null)
  useEffect(() => {
    if (!box.w || !box.h) return
    if (fittedKey.current === fitKey) return
    const first = fittedKey.current === null
    fittedKey.current = fitKey
    fit(false, true)
    if (first && initialCentre) centreOn(initialCentre)
  }, [box.w, box.h, fitKey, fit, centreOn, initialCentre])

  // Tekerlek (pasif OLMAYAN dinleyici — sayfa kaymasın).
  useEffect(() => {
    const el = vpRef.current
    if (!el) return undefined
    const onWheel = (e) => {
      e.preventDefault()
      const r = el.getBoundingClientRect()
      const dy = e.deltaMode === 1 ? e.deltaY * 16 : e.deltaY
      zoomAt(Math.exp(-dy * (e.ctrlKey ? 0.01 : 0.0015)), e.clientX - r.left, e.clientY - r.top)
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [zoomAt])

  // Sürükle / kıstır.
  const pointers = useRef(new Map())
  const gesture = useRef(null)
  const suppressClick = useRef(false)
  const local = (e) => {
    const r = vpRef.current.getBoundingClientRect()
    return { x: e.clientX - r.left, y: e.clientY - r.top }
  }
  const startPan = (id, p) => { gesture.current = { type: 'pan', id, start: p, view: viewRef.current, moved: false } }
  const onPointerDown = (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return
    const p = local(e)
    pointers.current.set(e.pointerId, p)
    if (pointers.current.size === 1) startPan(e.pointerId, p)
    else if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()]
      gesture.current = { type: 'pinch', dist: Math.hypot(a.x - b.x, a.y - b.y) || 1, mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, view: viewRef.current, moved: true }
      for (const id of pointers.current.keys()) { try { vpRef.current.setPointerCapture(id) } catch { /* yok */ } }
    }
  }
  const onPointerMove = (e) => {
    if (!pointers.current.has(e.pointerId)) return
    const p = local(e)
    pointers.current.set(e.pointerId, p)
    const g = gesture.current
    if (!g) return
    if (g.type === 'pan') {
      const dx = p.x - g.start.x, dy = p.y - g.start.y
      if (!g.moved) {
        if (Math.hypot(dx, dy) < 4) return
        g.moved = true
        try { vpRef.current.setPointerCapture(e.pointerId) } catch { /* yok */ }
      }
      apply({ ...g.view, x: g.view.x + dx, y: g.view.y + dy })
    } else if (pointers.current.size >= 2) {
      const [a, b] = [...pointers.current.values()]
      const d = Math.hypot(a.x - b.x, a.y - b.y) || 1
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
      const k = clamp(g.view.k * (d / g.dist), MIN_ZOOM, MAX_ZOOM)
      const f = k / g.view.k
      apply({ k, x: mid.x - (g.mid.x - g.view.x) * f, y: mid.y - (g.mid.y - g.view.y) * f })
    }
  }
  const onPointerEnd = (e) => {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.delete(e.pointerId)
    if (gesture.current?.moved) suppressClick.current = true
    if (pointers.current.size === 1) {
      const [[id, p]] = [...pointers.current.entries()]
      startPan(id, p)
      gesture.current.moved = true
    } else if (pointers.current.size === 0) {
      gesture.current = null
      setTimeout(() => { suppressClick.current = false }, 0)
    }
  }

  const onKeyDown = (e) => {
    if (e.target !== e.currentTarget) return
    const v = viewRef.current
    const step = 64
    const map = {
      ArrowLeft: () => apply({ ...v, x: v.x + step }, true), ArrowRight: () => apply({ ...v, x: v.x - step }, true),
      ArrowUp: () => apply({ ...v, y: v.y + step }, true), ArrowDown: () => apply({ ...v, y: v.y - step }, true),
      '+': () => zoomAt(1.2, box.w / 2, box.h / 2, true), '=': () => zoomAt(1.2, box.w / 2, box.h / 2, true),
      '-': () => zoomAt(1 / 1.2, box.w / 2, box.h / 2, true), '0': () => apply({ k: 1, x: 24, y: 24 }, true),
      f: () => fit(true), F: () => fit(true),
    }
    if (map[e.key]) { e.preventDefault(); map[e.key]() }
  }

  const stateOf = (name) => {
    if (!highlight) return null
    if (name === focus) return 'focus'
    return highlight.has(name) ? 'near' : 'dim'
  }
  const srId = `${markerId}-sr`
  const summary = t('sql.diag.imgSummary', model.stats.connected, model.stats.edges, model.stats.isolated)

  return (
    <div ref={vpRef} data-slot="schema-diagram" tabIndex={0} role="group"
      aria-label={t('sql.diag.canvasLabel')} aria-describedby={srId}
      onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerEnd} onPointerCancel={onPointerEnd}
      onClickCapture={(e) => { if (suppressClick.current) { e.preventDefault(); e.stopPropagation(); suppressClick.current = false } }}
      onKeyDown={onKeyDown}
      className={cn('relative min-h-0 touch-none overflow-hidden rounded-lg border bg-muted/30 outline-none select-none focus-visible:ring-2 focus-visible:ring-ring/50',
        'cursor-grab active:cursor-grabbing', className)}
      style={{
        backgroundImage: 'radial-gradient(var(--border) 1px, transparent 1px)',
        backgroundSize: `${22 * view.k}px ${22 * view.k}px`,
        backgroundPosition: `${view.x}px ${view.y}px`,
      }}>
      <div data-slot="diagram-world"
        className={cn('absolute top-0 left-0 origin-top-left', animate && 'transition-transform duration-300 ease-out motion-reduce:transition-none')}
        style={{ width: model.width, height: model.height, transform: `translate(${view.x}px, ${view.y}px) scale(${view.k})` }}
        onTransitionEnd={() => setAnimate(false)}>
        <EdgeLayer model={model} highlight={highlight} focus={focus} markerId={markerId} label={summary} />
        {model.isolatedLabel && (
          <div className="absolute flex items-center gap-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase"
            style={{ left: model.isolatedLabel.x, top: model.isolatedLabel.y }}>
            {t('sql.diag.unrelatedGroup', model.stats.isolated)}
          </div>
        )}
        {model.nodes.map((n) => (
          <DiagramNode key={n.name} node={n} state={stateOf(n.name)} expanded={expanded?.has(n.name)}
            onSelect={onSelect} onToggleExpand={onToggleExpand} onReveal={reveal} />
        ))}
        {focus && <EdgeLayer model={model} highlight={highlight} focus={focus} markerId={markerId} top />}
      </div>

      {/* Ekran okuyucu metin karşılığı: her tablonun başvurduğu ve ona başvuranlar */}
      <ul id={srId} className="sr-only">
        {model.nodes.filter((n) => !n.isolated).map((n) => {
          const outs = model.graph.out.get(n.name) || []
          const ins = model.graph.inc.get(n.name) || []
          return (
            <li key={n.name}>
              {t('sql.diag.srLine', n.name, outs.map((e) => `${e.to} (${e.column})`).join(', ') || '—', ins.map((e) => `${e.from} (${e.column})`).join(', ') || '—')}
            </li>
          )
        })}
      </ul>

      {/* Yakınlaştırma denetimleri — sürükleme başlatmasın */}
      <div className="absolute top-3 right-3 flex flex-col items-end gap-1.5" onPointerDown={(e) => e.stopPropagation()}>
        <ButtonGroup orientation="vertical" aria-label={t('sql.diag.zoomControls')} className="rounded-md bg-background shadow-sm">
          <Button type="button" variant="outline" size="icon" className="pointer-coarse:size-10" aria-label={t('sql.diag.zoomIn')}
            onClick={() => zoomAt(1.25, box.w / 2, box.h / 2, true)}><Plus /></Button>
          <Button type="button" variant="outline" size="icon" className="pointer-coarse:size-10" aria-label={t('sql.diag.zoomOut')}
            onClick={() => zoomAt(1 / 1.25, box.w / 2, box.h / 2, true)}><Minus /></Button>
          <Button type="button" variant="outline" size="icon" className="pointer-coarse:size-10" aria-label={t('sql.diag.fit')}
            onClick={() => fit(true)}><Maximize /></Button>
          <Button type="button" variant="outline" size="icon" className="pointer-coarse:size-10" aria-label={t('sql.diag.reset')}
            onClick={() => apply({ k: 1, x: Math.max(24, (box.w - model.width) / 2), y: 24 }, true)}><Scan /></Button>
        </ButtonGroup>
        <span data-slot="diagram-zoom" aria-live="polite"
          className="rounded bg-background/90 px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground tabular-nums shadow-xs">
          {Math.round(view.k * 100)}%
        </span>
      </div>

      <Minimap model={model} view={view} box={box} highlight={highlight}
        onJump={(wx, wy) => apply({ ...viewRef.current, x: box.w / 2 - wx * viewRef.current.k, y: box.h / 2 - wy * viewRef.current.k })} />
      {children}
    </div>
  )
})

export default DiagramCanvas
