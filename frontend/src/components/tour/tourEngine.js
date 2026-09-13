/**
 * Ürün turu motoru — saf model (2026-09-13). Bileşen yok: yerleşim matematiği, adım süzme,
 * otomatik başlatma kararı, "yenilikler" farkı ve localStorage aynası. TourProvider bunları çizer.
 */

/** Tur içeriği büyüyünce artır → tamamlamış kullanıcılara yalnız yeni adımlar "Yenilikler" olarak sunulur. */
export const TOUR_VERSION = 1
export const SNOOZE_MAX = 3
export const LS_KEY = 'sm.tour'
export const MOBILE_MAX = 640

/** Balon ölçüleri (CSS ile aynı; yerleşim hesabı için). */
export const TIP_W = 340
export const TIP_H_GUESS = 190
export const GAP = 12
export const EDGE = 12

/**
 * Balonu hedefin yanına yerleştir: tercih edilen yön sığmıyorsa sırayla alt → üst → sağ → sol dener;
 * hiçbiri sığmazsa merkezde durur. Hedef yoksa (merkez adım) ekran ortası.
 * @returns {{ top:number, left:number, placement:string, arrow:{x:number,y:number}|null }}
 */
export function placeTooltip(target, tip = { w: TIP_W, h: TIP_H_GUESS }, vp = { w: 1280, h: 800 }, preferred = 'bottom') {
  if (!target) {
    return { top: Math.max(EDGE, (vp.h - tip.h) / 2), left: Math.max(EDGE, (vp.w - tip.w) / 2), placement: 'center', arrow: null }
  }
  const order = [preferred, 'bottom', 'top', 'right', 'left'].filter((p, i, a) => a.indexOf(p) === i)
  const fits = {
    bottom: target.top + target.height + GAP + tip.h <= vp.h - EDGE,
    top: target.top - GAP - tip.h >= EDGE,
    right: target.left + target.width + GAP + tip.w <= vp.w - EDGE,
    left: target.left - GAP - tip.w >= EDGE,
  }
  const placement = order.find((p) => fits[p]) || 'center'
  const clampX = (x) => Math.min(Math.max(EDGE, x), Math.max(EDGE, vp.w - tip.w - EDGE))
  const clampY = (y) => Math.min(Math.max(EDGE, y), Math.max(EDGE, vp.h - tip.h - EDGE))
  const cx = target.left + target.width / 2
  const cy = target.top + target.height / 2
  switch (placement) {
    case 'bottom': return { top: target.top + target.height + GAP, left: clampX(cx - tip.w / 2), placement, arrow: { x: cx, y: target.top + target.height } }
    case 'top': return { top: target.top - GAP - tip.h, left: clampX(cx - tip.w / 2), placement, arrow: { x: cx, y: target.top } }
    case 'right': return { top: clampY(cy - tip.h / 2), left: target.left + target.width + GAP, placement, arrow: { x: target.left + target.width, y: cy } }
    case 'left': return { top: clampY(cy - tip.h / 2), left: target.left - GAP - tip.w, placement, arrow: { x: target.left, y: cy } }
    default: return { top: clampY((vp.h - tip.h) / 2), left: clampX((vp.w - tip.w) / 2), placement: 'center', arrow: null }
  }
}

/** Spot ışığı deliği: hedef dikdörtgeni + kenar payı, viewport'a kırpılmış. */
export function spotlightRect(target, pad = 6, vp = { w: 1280, h: 800 }) {
  if (!target) return null
  const x = Math.max(0, target.left - pad), y = Math.max(0, target.top - pad)
  const r = Math.min(vp.w, target.left + target.width + pad), b = Math.min(vp.h, target.top + target.height + pad)
  return { x, y, w: Math.max(0, r - x), h: Math.max(0, b - y) }
}

/**
 * Adımları bağlama göre süz: `when(ctx)` yanlışsa adım çıkar; mobilde `mobile:false` adımlar çıkar.
 * ctx: { role, globalAdmin, canView(key), isMobile }
 */
export function filterSteps(steps, ctx) {
  return (steps || []).filter((s) => {
    if (!s || !s.id) return false
    if (ctx?.isMobile && s.mobile === false) return false
    if (typeof s.when === 'function') { try { return !!s.when(ctx || {}) } catch { return false } }
    return true
  })
}

/** Yalnız `since > version` olan adımlar (yenilikler turu). */
export function newStepsSince(steps, version) {
  const v = Number(version) || 0
  return (steps || []).filter((s) => (Number(s.since) || 1) > v)
}

/**
 * Otomatik başlatma kararı — girişten sonra bir kez.
 * @returns {'welcome'|'whatsnew'|null}
 */
export function decideAutoStart(tourState, { mustChangePwd = false, isMobile = false, steps = [] } = {}) {
  if (mustChangePwd) return null
  const st = tourState && typeof tourState === 'object' ? tourState : null
  if (!st || !st.status) return 'welcome'
  if (st.status === 'dismissed') return null
  if (st.status === 'completed') {
    if ((Number(st.version) || 0) < TOUR_VERSION && newStepsSince(steps, st.version).length > 0 && !isMobile) return 'whatsnew'
    return null
  }
  if (st.status === 'snoozed') return (Number(st.snoozed) || 0) < SNOOZE_MAX ? 'welcome' : null
  // 'started' (yarıda bırakılmış) → yeniden sor
  return 'welcome'
}

/** Sayfa turu çipi: sayfanın turu var ve daha önce görülmedi/oynatılmadı. */
export function shouldOfferPageTour(pageId, tourState, pageTours) {
  if (!pageId || !pageTours?.[pageId]?.length) return false
  if (!tourState) return true
  if (tourState.status === 'dismissed') return false   // ana turu kapatan kişiye sayfa çipi de çıkmaz
  const seen = Array.isArray(tourState.seen_pages) ? tourState.seen_pages : []
  return !seen.includes(pageId)
}

/** Başlangıç listesi ilerlemesi. items: [{key}], checklist: {key: bool} */
export function checklistProgress(items, checklist) {
  const done = (items || []).filter((i) => checklist?.[i.key] === true).length
  const total = (items || []).length
  return { done, total, pct: total ? Math.round((done / total) * 100) : 0, complete: total > 0 && done === total }
}

// ── localStorage aynası (ilk boyamada titreme olmasın; sunucu doğruluk kaynağı) ──
export function readMirror() {
  try { const v = JSON.parse(localStorage.getItem(LS_KEY) || 'null'); return v && typeof v === 'object' ? v : null } catch { return null }
}
export function writeMirror(state) {
  try { if (state) localStorage.setItem(LS_KEY, JSON.stringify(state)); else localStorage.removeItem(LS_KEY) } catch { /* yoksay */ }
}

/** Sunucu ile ayna arasında: sunucu varsa sunucu; yoksa ayna (çevrimdışı/eski sunucu). */
export function mergeState(server, mirror) {
  if (server && typeof server === 'object') return server
  return mirror || null
}

/** Klavye: → / Enter ileri, ← geri, Esc kapat (metin alanlarında dinlenmez). */
export function keyAction(e) {
  const tag = (e?.target?.tagName || '').toLowerCase()
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return null
  if (e.key === 'ArrowRight' || e.key === 'Enter') return 'next'
  if (e.key === 'ArrowLeft') return 'prev'
  if (e.key === 'Escape') return 'close'
  return null
}
