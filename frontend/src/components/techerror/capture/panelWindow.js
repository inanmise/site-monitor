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

/** "Raporu aç" isteği ve yanıtı (panel → açan pencere, yalnız aynı köken; ileti yalnız kimlik taşır). */
export const OPEN_REPORT_REQUEST = 'sm-te-open-report'
export const OPEN_REPORT_ACK = 'sm-te-open-report-ack'
/** Açan pencerenin yanıtı bu kadar beklenir; gelmezse adres değiştirilerek açılır (eski yol). */
export const OPEN_REPORT_TIMEOUT_MS = 1000

/** Adres bu raporun düzenleyicisi mi? */
export function isReportEditorSearch(search, reportId) {
  try {
    const p = new URLSearchParams(search || '')
    return p.get('tab') === 'channelreports' && p.get('te_view') === 'report' && p.get('te_rep') === String(reportId)
  } catch {
    return false
  }
}

/**
 * Panelden "Ana pencerede aç" (inceleme 2026-10-18): açan pencere hâlâ SiteMonitor'deyse ona İLETİ gönderilir
 * (`postMessage`, hedef köken = bu köken) — açan pencere raporu UYGULAMA İÇİNDE açar (`App`, `onOpenReportRequest`):
 * kaydedilmemiş form / taslak sayfa yeniden yüklenmeden ayrılır, uygulamanın geçiş kuralları geçerlidir. Yalnız açan
 * pencere {@link OPEN_REPORT_TIMEOUT_MS} içinde yanıt vermezse (eski sürüm, oturum kapalı) adres değiştirilir
 * (`location.assign`, zaten o rapordaysa yalnız öne getirilir). Açan pencere başka siteye geçtiyse ya da kapandıysa rapor
 * yeni sekmede açılır — hemen, tıklama anında (açılır pencere engellenmesin; kullanıcının Qlik sekmesi ezilmez).
 *
 * @returns {Promise<'opener'|'assign'|'tab'>}
 */
export function openInMainWindow(reportId, channelId, win = globalThis.window, { timeoutMs = OPEN_REPORT_TIMEOUT_MS } = {}) {
  const url = reportEditorUrl(reportId, channelId, win?.location)
  const opener = (() => { try { return win.opener && !win.opener.closed ? win.opener : null } catch { return null } })()
  let same = false
  if (opener) {
    try { same = opener.location.origin === win.location.origin } catch { same = false }   // başka kökene geçmiş
  }
  if (!same) {
    try { win.open(url, '_blank', 'noopener') } catch { /* yok */ }
    return Promise.resolve('tab')
  }
  // Tıklamanın kullanıcı etkinliği sürerken öne getir (bazı tarayıcılar sonradan izin vermez)
  try { opener.focus() } catch { /* yok */ }
  const origin = win.location.origin
  const rid = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
  return new Promise((resolve) => {
    let done = false
    let timer = null
    const finish = (how) => {
      if (done) return
      done = true
      clearTimeout(timer)
      try { win.removeEventListener?.('message', onMsg) } catch { /* yok */ }
      resolve(how)
    }
    const fallback = () => {
      try {
        if (!isReportEditorSearch(opener.location.search, reportId)) opener.location.assign(url)
        opener.focus()
        finish('assign')
      } catch {
        try { win.open(url, '_blank', 'noopener') } catch { /* yok */ }
        finish('tab')
      }
    }
    function onMsg(e) {
      if (e?.origin !== origin || e.data?.type !== OPEN_REPORT_ACK || e.data.rid !== rid) return
      finish('opener')
    }
    try { win.addEventListener?.('message', onMsg) } catch { /* yok */ }
    timer = setTimeout(() => { if (!done) fallback() }, Math.max(0, timeoutMs))
    try {
      opener.postMessage({ type: OPEN_REPORT_REQUEST, rid, reportId: String(reportId), channelId: channelId != null ? String(channelId) : null }, origin)
    } catch {
      fallback()
    }
  })
}

/**
 * Açan pencere tarafı: panelin "raporu aç" isteğine abone olur (App bağlar — bu modül açılış paketindedir, bağımlılığı
 * yoktur). `handler({ reportId, channelId })` true dönerse istek işlendi sayılır ve panele yanıt gider; false (oturum yok,
 * pencere kendisi panel) yanıt vermez — panel eski yola düşer. Yalnız AYNI kökenden ve geçerli kimlikle gelen istek.
 */
export function onOpenReportRequest(handler, win = globalThis.window) {
  const h = (e) => {
    try {
      if (e?.origin !== win.location.origin) return
      const d = e.data
      if (!d || d.type !== OPEN_REPORT_REQUEST || typeof d.rid !== 'string' || d.rid.length > 40) return
      const reportId = String(d.reportId ?? '')
      if (!ID_RE.test(reportId)) return
      const channelId = d.channelId != null && ID_RE.test(String(d.channelId)) ? String(d.channelId) : null
      if (!handler({ reportId, channelId })) return
      e.source?.postMessage?.({ type: OPEN_REPORT_ACK, rid: d.rid }, e.origin)
    } catch { /* yanıt verilemedi: panel eski yola düşer */ }
  }
  try { win.addEventListener?.('message', h) } catch { /* yok */ }
  return () => { try { win.removeEventListener?.('message', h) } catch { /* yok */ } }
}
