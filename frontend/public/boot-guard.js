/*
 * SiteMonitor açılış koruması — boot guard (2026-10-23, çok pod sürüm 2b · MP-47).
 *
 * index.html bunu uygulama paketinden ÖNCE, hash'siz ve önbelleksiz (no-store) yükler; CSP satır içi betiğe izin
 * vermediği için ayrı dosyadır. Yuvarlanan dağıtımda eski ve yeni pod birlikte sunarken bir sayfa yüklemesi iki sürümün
 * dosyalarını karıştırabilir: index.html bir poddan, /assets/index-<hash>.js öbüründen → düz metin 404 → React hiç
 * başlamaz → BOŞ SAYFA. Bu betik ilk tam yüklemeye kadar giriş betiğinin / CSS'in / modulepreload'un yükleme hatasını
 * yakalar ve sayfayı BİR KEZ yeniler (yenileme taze index.html'i ve onun dosyalarını getirir). Hak bittiyse boş sayfa
 * yerine kısa bir uyarı ve "Yeniden yükle" düğmesi gösterir (giriş betiği yüklenemediyse; CSS'te sayfa çalışır durumda).
 *
 * Sayaç uygulamanın src/utils/chunkReloadGuard.js kuralıyla AYNI kayıttadır (sessionStorage "eb-chunk-reload"):
 * paket başına oturumda bir otomatik yenileme ve herhangi bir otomatik yenilemeden sonra 60 sn bekleme — açılış
 * koruması, ErrorBoundary ve dil yükleyici birlikte en çok BİR kez yeniler, asla döngüye girmez. Depolama
 * okunamıyor / yazılamıyorsa yenileme YOK. Kural burada derlemesiz düz JS olarak yinelenir (paket yüklenemediğinde o modül de
 * yoktur); src/test/bootGuard.test.js ikisini aynı doğruluk tablosuyla sınar — birini değiştirirsen öbürünü de.
 *
 * İlk "load" olayından sonra devre dışıdır: sonraki tembel parça hataları uygulamanın işidir (ErrorBoundary).
 */
(function (root) {
  'use strict'
  var KEY = 'eb-chunk-reload'
  var MAX_AUTO_RELOADS = 1
  var RELOAD_COOLDOWN_MS = 60000
  var MAX_REMEMBERED_BUILDS = 8
  var LANG_KEY = 'site-monitor-lang'
  var NOTICE_ID = 'sm-boot-failure'
  var doc = root && root.document
  if (!doc || typeof root.addEventListener !== 'function') return

  function pathOf(url) {
    try { return new URL(url, doc.baseURI).pathname } catch { return null }
  }

  function sameOrigin(url) {
    try { return new URL(url, doc.baseURI).origin === new URL(doc.baseURI).origin } catch { return false }
  }

  /** chunkReloadGuard.currentBuildId ile AYNI: ilk /assets/ modül betiği, yoksa ilk yerel (/@ hariç) modül betiği. */
  function buildId() {
    var scripts = doc.querySelectorAll('script[type="module"][src]')
    var local = ''
    for (var i = 0; i < scripts.length; i++) {
      var p = pathOf(scripts[i].getAttribute('src'))
      if (!p) continue
      if (p.indexOf('/assets/') === 0) return p
      if (!local && p.indexOf('/@') !== 0) local = p
    }
    return local || 'unknown'
  }

  function readRecord(raw) {
    var prev = null
    try { prev = JSON.parse(raw || 'null') } catch { prev = null }
    var rec = { at: NaN, builds: {} }
    if (!prev || typeof prev !== 'object') return rec
    if (prev.builds && typeof prev.builds === 'object') {
      for (var k in prev.builds) if (Object.prototype.hasOwnProperty.call(prev.builds, k)) rec.builds[k] = prev.builds[k]
      rec.at = Number(prev.at)
    } else if (typeof prev.version === 'string') {
      rec.builds[prev.version] = Number(prev.count) || 0
    }
    return rec
  }

  /** chunkReloadGuard.claimChunkReload ile AYNI kural. */
  function claim(version, now) {
    try {
      var storage = root.sessionStorage
      var rec = readRecord(storage.getItem(KEY))
      var v = String(version)
      if (isFinite(rec.at) && now >= rec.at && now - rec.at < RELOAD_COOLDOWN_MS) return false
      var count = Number(rec.builds[v]) || 0
      if (count >= MAX_AUTO_RELOADS) return false
      delete rec.builds[v]
      rec.builds[v] = count + 1
      var ids = Object.keys(rec.builds)
      for (var i = 0; i < ids.length - MAX_REMEMBERED_BUILDS; i++) delete rec.builds[ids[i]]
      storage.setItem(KEY, JSON.stringify({ v: 2, at: now, builds: rec.builds }))
      return true
    } catch {
      return false
    }
  }

  /** Sayfanın kendi dosyası mı: aynı kaynaktan betik ya da stil / modulepreload bağlantısı (bu betiğin kendisi hariç). */
  function bootAsset(el) {
    if (!el || !el.tagName) return null
    var tag = String(el.tagName).toUpperCase()
    if (tag === 'SCRIPT') {
      var src = el.getAttribute('src')
      if (!src || !sameOrigin(src) || pathOf(src) === '/boot-guard.js') return null
      return 'script'
    }
    if (tag === 'LINK') {
      var rel = String(el.getAttribute('rel') || '').toLowerCase()
      var href = el.getAttribute('href')
      if ((rel === 'stylesheet' || rel === 'modulepreload') && href && sameOrigin(href)) return rel
    }
    return null
  }

  function lang() {
    try { return root.localStorage.getItem(LANG_KEY) === 'tr' ? 'tr' : 'en' } catch { return 'en' }
  }

  /** Hak kalmadıysa ve uygulama hiç çizilmediyse: boş sayfa yerine görünür, erişilebilir bir uyarı. */
  function showNotice() {
    if (doc.getElementById(NOTICE_ID) || !doc.body) return
    var appRoot = doc.getElementById('root')
    if (appRoot && appRoot.firstElementChild) return
    var tr = lang() === 'tr'
    var box = doc.createElement('div')
    box.id = NOTICE_ID
    box.setAttribute('role', 'alert')
    box.setAttribute('data-slot', 'boot-failure')
    box.setAttribute('lang', tr ? 'tr' : 'en')
    var s = box.style
    s.boxSizing = 'border-box'; s.maxWidth = '32rem'; s.margin = '15vh auto 0'; s.padding = '24px 16px'
    s.fontFamily = 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif'; s.lineHeight = '1.5'
    s.color = 'CanvasText'; s.background = 'Canvas'; s.textAlign = 'center'; s.overflowWrap = 'anywhere'
    var title = doc.createElement('p')
    title.style.fontSize = '1.125rem'; title.style.fontWeight = '600'; title.style.margin = '0 0 8px'
    title.textContent = tr ? 'SiteMonitor güncelleniyor' : 'SiteMonitor is being updated'
    var body = doc.createElement('p')
    body.style.margin = '0 0 16px'
    body.textContent = tr
      ? 'Sayfanın bazı dosyaları yüklenemedi — büyük olasılıkla yeni sürüm şu anda dağıtılıyor. Birkaç saniye bekleyip sayfayı yeniden yükleyin.'
      : 'Some files of this page could not be loaded, most likely because a new version is being rolled out right now. Wait a few seconds, then reload the page.'
    var button = doc.createElement('button')
    button.type = 'button'
    button.textContent = tr ? 'Yeniden yükle' : 'Reload'
    var b = button.style
    b.minHeight = '44px'; b.minWidth = '44px'; b.padding = '8px 20px'; b.font = 'inherit'; b.cursor = 'pointer'
    b.borderRadius = '8px'; b.border = '1px solid ButtonBorder'; b.color = 'ButtonText'; b.background = 'ButtonFace'
    button.addEventListener('click', function () { try { root.location.reload() } catch { /* yoksay */ } })
    box.appendChild(title); box.appendChild(body); box.appendChild(button)
    doc.body.appendChild(box)
  }

  var active = true
  function stop() {
    active = false
    root.removeEventListener('error', onError, true)
    root.removeEventListener('load', stop)
  }

  function onError(event) {
    if (!active) return
    var kind = bootAsset(event && event.target)
    if (!kind) return
    if (claim(buildId(), Date.now())) {
      stop()
      try { root.location.reload() } catch { /* yoksay */ }
      return
    }
    // Hak yok: CSS / modulepreload'da sayfa yine çalışır (görünüm bozuk olabilir) — yalnız giriş betiğinde uyarı.
    if (kind !== 'script') return
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', showNotice)
    else showNotice()
  }

  // Yakalama evresi: kaynak yükleme hataları kabarmaz, ama window'un yakalama dinleyicisine önce uğrar.
  root.addEventListener('error', onError, true)
  root.addEventListener('load', stop)
})(this)
