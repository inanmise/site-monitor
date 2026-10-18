/**
 * GÖRSEL PANELİ penceresi (2026-10-18, kullanıcı isteği: "ayrı bir panelde görsellerin olduğu sayfa açık olacak, site
 * monitör üzerinden diğer sayfaya geçip ekran görüntüsünü alabilmeliyim"). Panel, raporun YALNIZ görsel bölümlerini
 * gösteren SiteMonitor sayfasıdır (`?tab=channelreports&te_view=images&te_rep=<id>`, kenar çubuğu / gezinme yok); ana
 * pencere başka sayfaya ya da başka siteye geçse de çalışmaya devam eder.
 */

export const VIEW_IMAGES = 'images'
export const PANEL_FEATURES = 'popup,width=520,height=900'
const ID_RE = /^[1-9][0-9]{0,17}$/

/** Bu pencerede paneli açılmış raporlar: düzenleyici pencereye dönünce görselleri yeniden okur (kaçan ileti güvencesi). */
const opened = new Set()
export const panelOpenedFor = (reportId) => opened.has(String(reportId))

/** Rapor için pencere adı: aynı raporun paneli ikinci kez açılmaz, öne gelir. */
export const panelWindowName = (reportId) => `sm-te-images-${reportId}`

/** Panelin adresi (aynı kök yol). */
export function imagesPanelUrl(reportId, loc = globalThis.location) {
  const path = loc?.pathname || '/'
  return `${path}?tab=channelreports&te_view=${VIEW_IMAGES}&te_rep=${encodeURIComponent(reportId)}`
}

/** Ana penceredeki rapor düzenleyicisinin adresi. */
export function reportEditorUrl(reportId, channelId, loc = globalThis.location) {
  const path = loc?.pathname || '/'
  const ch = channelId != null ? `&te_ch=${encodeURIComponent(channelId)}` : ''
  return `${path}?tab=channelreports&te_view=report${ch}&te_rep=${encodeURIComponent(reportId)}`
}

/** Adres görsel paneli mi? Öyleyse rapor kimliği, değilse null (App bunu açılışta bir kez okur). */
export function imagesPanelReportFromSearch(search = globalThis.location?.search || '') {
  try {
    const p = new URLSearchParams(search)
    if (p.get('tab') !== 'channelreports' || p.get('te_view') !== VIEW_IMAGES) return null
    const id = p.get('te_rep') || ''
    return ID_RE.test(id) ? id : null
  } catch {
    return null
  }
}

/**
 * Paneli açar ya da (açıksa) öne getirir. Önce adla boş açılır: pencere zaten varsa YENİDEN YÜKLENMEZ (yarım yükleme
 * kaybolmaz). Açılır pencere engellendiyse `{ blocked: true, url }` — çağıran "yeni sekmede aç" bağlantısı gösterir.
 */
export function openImagesPanel(reportId, win = globalThis.window) {
  const url = imagesPanelUrl(reportId, win?.location)
  opened.add(String(reportId))
  let w = null
  try { w = win.open('', panelWindowName(reportId), PANEL_FEATURES) } catch { w = null }
  if (!w) return { blocked: true, url }
  let needsNav = true
  try {
    const href = String(w.location?.href || '')
    needsNav = !href || href === 'about:blank' || imagesPanelReportFromSearch(w.location.search) !== String(reportId)
  } catch { needsNav = true }
  if (needsNav) {
    try { w.location.replace(url) } catch {
      try { w.location.href = url } catch { /* yok */ }
    }
  }
  try { w.focus() } catch { /* yok */ }
  return { blocked: false, url, win: w }
}

/**
 * Panelden "Ana pencerede aç": açan pencere hâlâ SiteMonitor'deyse oraya raporu açar (zaten o rapordaysa yalnız öne
 * getirir); açan pencere başka siteye geçtiyse ya da kapandıysa rapor yeni sekmede açılır (kullanıcının Qlik sekmesi
 * ezilmez).
 */
export function openInMainWindow(reportId, channelId, win = globalThis.window) {
  const url = reportEditorUrl(reportId, channelId, win?.location)
  const opener = (() => { try { return win.opener && !win.opener.closed ? win.opener : null } catch { return null } })()
  if (opener) {
    try {
      const same = opener.location.origin === win.location.origin
      if (same) {
        const p = new URLSearchParams(opener.location.search)
        const onReport = p.get('tab') === 'channelreports' && p.get('te_view') === 'report' && p.get('te_rep') === String(reportId)
        if (!onReport) opener.location.assign(url)
        opener.focus()
        return 'opener'
      }
    } catch { /* başka kökene geçmiş: dokunulmaz */ }
  }
  try { win.open(url, '_blank', 'noopener') } catch { /* yok */ }
  return 'tab'
}
