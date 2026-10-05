import { HEADER_H, rowCenter } from './layout.js'

/**
 * Diyagramı dışa aktarma — istemci tarafı, bağımlılık yok.
 *
 * Ekrandaki diyagram HTML kartlar + SVG kenarlardan oluşur (kartlar gerçek shadcn düğmeleri: klavye/ekran okuyucu);
 * dışa aktarım için AYNI düzen modelinden bağımsız, saf bir SVG belgesi kurulur (`buildDiagramSvg`, birim testli).
 * Renkler o anki temadan çözülür (`readPalette`) → koyu temada koyu dosya. PNG, SVG'nin tuvale çizilmesiyle üretilir
 * (dış kaynak yok → tuval kirlenmez).
 */

/** Varsayılan (açık tema) palet — test ve çözümleme başarısız olursa. */
export const LIGHT_PALETTE = {
  background: '#ffffff', card: '#ffffff', foreground: '#09090b', muted: '#f4f4f5', mutedForeground: '#71717a',
  border: '#e4e4e7', primary: '#2563eb', pk: '#b45309', fk: '#047857', edge: '#71717a',
}

const XML = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
export const xmlEscape = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => XML[c])

const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace"
const SANS = "'Inter Variable', Inter, 'Segoe UI', system-ui, -apple-system, sans-serif"

/** Eş aralıklı yazıda yaklaşık kesme (px genişliğe göre). */
function clip(text, maxPx, charPx) {
  const s = String(text ?? '')
  const max = Math.max(1, Math.floor(maxPx / charPx))
  return s.length > max ? `${s.slice(0, Math.max(1, max - 1))}…` : s
}

/**
 * Model → bağımsız SVG belgesi (dize).
 * @param {object} model   computeDiagram() çıktısı
 * @param {object} [opts]  { palette, title, rowsLabel(n) → '1.2K rows', unrelatedLabel, typeLabel(t) }
 */
export function buildDiagramSvg(model, opts = {}) {
  const p = { ...LIGHT_PALETTE, ...(opts.palette || {}) }
  const W = Math.ceil(model.width)
  const H = Math.ceil(model.height)
  const rowsLabel = opts.rowsLabel || ((n) => (n == null ? '' : String(n)))
  const typeLabel = opts.typeLabel || ((t) => t || '')
  const out = []
  out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="${xmlEscape(SANS)}">`)
  if (opts.title) out.push(`<title>${xmlEscape(opts.title)}</title>`)
  out.push('<defs>',
    `<marker id="dg-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" markerUnits="userSpaceOnUse" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="${p.edge}"/></marker>`,
    `<marker id="dg-many" viewBox="0 0 12 12" refX="12" refY="6" markerWidth="12" markerHeight="12" markerUnits="userSpaceOnUse" orient="auto-start-reverse"><path d="M0,6 L12,0 M0,6 L12,6 M0,6 L12,12" fill="none" stroke="${p.edge}" stroke-width="1.3"/></marker>`,
    '</defs>')
  out.push(`<rect width="${W}" height="${H}" fill="${p.background}"/>`)
  if (model.isolatedLabel && opts.unrelatedLabel) {
    out.push(`<text x="${model.isolatedLabel.x}" y="${model.isolatedLabel.y + 14}" font-size="12" font-weight="600" fill="${p.mutedForeground}">${xmlEscape(opts.unrelatedLabel)}</text>`)
  }
  for (const e of model.edges) {
    const dash = e.inferred ? ' stroke-dasharray="5 4"' : ''
    out.push(`<path d="${e.d}" fill="none" stroke="${p.edge}" stroke-width="1.4"${dash} marker-end="url(#dg-arrow)" marker-start="url(#dg-many)"><title>${xmlEscape(`${e.from}.${e.column ?? ''} → ${e.to}`)}</title></path>`)
  }
  for (const n of model.nodes) {
    const g = []
    g.push(`<g transform="translate(${n.x},${n.y})">`)
    g.push(`<rect width="${n.w}" height="${n.h}" rx="10" fill="${p.card}" stroke="${p.border}"/>`)
    g.push(`<text x="14" y="${HEADER_H / 2 + 4.5}" font-family="${xmlEscape(MONO)}" font-size="13" font-weight="600" fill="${p.foreground}">${xmlEscape(clip(n.name, n.w - 96, 7.9))}</text>`)
    const rl = rowsLabel(n.liveRows)
    if (rl) g.push(`<text x="${n.w - 12}" y="${HEADER_H / 2 + 4}" text-anchor="end" font-size="11" fill="${p.mutedForeground}">${xmlEscape(rl)}</text>`)
    const rows = n.rows || []
    if (rows.length || n.more) {
      g.push(`<path d="M0 ${HEADER_H} H${n.w}" stroke="${p.border}"/>`)
      rows.forEach((r, i) => {
        const cy = rowCenter(i)
        const badge = r.kind === 'pk' ? 'PK' : r.kind === 'fk' ? 'FK' : ''
        if (badge) {
          const col = r.kind === 'pk' ? p.pk : p.fk
          g.push(`<rect x="10" y="${cy - 8}" width="22" height="16" rx="4" fill="${col}" fill-opacity="0.14"/>`,
            `<text x="21" y="${cy + 3.5}" text-anchor="middle" font-size="9" font-weight="700" fill="${col}">${badge}</text>`)
        }
        g.push(`<text x="40" y="${cy + 4}" font-family="${xmlEscape(MONO)}" font-size="12" fill="${p.foreground}">${xmlEscape(clip(r.name, n.w - 130, 7.3))}</text>`)
        const ty = typeLabel(r.type)
        if (ty) g.push(`<text x="${n.w - 12}" y="${cy + 4}" text-anchor="end" font-size="10.5" fill="${p.mutedForeground}">${xmlEscape(clip(ty, 72, 6.2))}</text>`)
      })
      if (n.more) {
        const cy = rowCenter(rows.length)
        g.push(`<text x="40" y="${cy + 4}" font-size="11" fill="${p.mutedForeground}">${xmlEscape(opts.moreLabel ? opts.moreLabel(n.more) : `+${n.more}`)}</text>`)
      }
    }
    g.push('</g>')
    out.push(g.join(''))
  }
  out.push('</svg>')
  return out.join('\n')
}

/** Bir CSS değişkenini gerçek renge çözer (tarayıcıda; `var(--brand-primary, …)` gibi zincirler dâhil). */
function resolveVar(probe, name, fallback) {
  probe.style.color = `var(${name}, ${fallback})`
  const c = getComputedStyle(probe).color
  return c && c !== '' ? c : fallback
}

/** O anki temanın paleti (koyu tema → koyu dosya). */
export function readPalette() {
  if (typeof document === 'undefined') return { ...LIGHT_PALETTE }
  const probe = document.createElement('span')
  probe.style.display = 'none'
  document.body.appendChild(probe)
  try {
    // Şemaya bağlı (2026-10-05, temalar): koyu şemalı her tema koyu dışa aktarım paletini alır.
    const dark = document.documentElement.getAttribute('data-scheme') === 'dark'
    return {
      background: resolveVar(probe, '--background', LIGHT_PALETTE.background),
      card: resolveVar(probe, '--card', LIGHT_PALETTE.card),
      foreground: resolveVar(probe, '--foreground', LIGHT_PALETTE.foreground),
      muted: resolveVar(probe, '--muted', LIGHT_PALETTE.muted),
      mutedForeground: resolveVar(probe, '--muted-foreground', LIGHT_PALETTE.mutedForeground),
      border: resolveVar(probe, '--border', LIGHT_PALETTE.border),
      primary: resolveVar(probe, '--primary', LIGHT_PALETTE.primary),
      edge: resolveVar(probe, '--muted-foreground', LIGHT_PALETTE.edge),
      pk: dark ? '#fbbf24' : LIGHT_PALETTE.pk,
      fk: dark ? '#34d399' : LIGHT_PALETTE.fk,
    }
  } finally {
    probe.remove()
  }
}

/** PNG ölçeği: 2× hedef, çok büyük diyagramda ~24 MP tavanı (tuval sınırı + bellek). */
export function pngScale(w, h) {
  const area = Math.max(1, w * h)
  return Math.max(0.5, Math.min(2, Math.sqrt(24e6 / area)))
}

/** SVG dizesi → PNG Blob (tarayıcıda). */
export async function svgToPngBlob(svg, width, height) {
  const scale = pngScale(width, height)
  const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }))
  try {
    const img = new Image()
    await new Promise((resolve, reject) => { img.onload = resolve; img.onerror = reject; img.src = url })
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(width * scale)
    canvas.height = Math.round(height * scale)
    const ctx = canvas.getContext('2d')
    ctx.scale(scale, scale)
    ctx.drawImage(img, 0, 0, width, height)
    return await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'))
  } finally {
    URL.revokeObjectURL(url)
  }
}

