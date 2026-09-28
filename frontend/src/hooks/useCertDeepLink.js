import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { readUrlParam } from './useUrlQuerySync.js'
import { DEEP_OPEN, DEEP_OPEN_PARAM } from '../utils/monitorDeepLink.js'
import { requestNocFieldFocus } from '../components/noc/forms/nocFieldFocus.js'

/** utils/navigate.js yayını (App.jsx NAVIGATE_EVENT ile aynı ad; App'i içe aktarmamak için yerel). */
const NAVIGATE_EVENT = 'sm:navigate'
const ACTIONS = [DEEP_OPEN.CERT, DEEP_OPEN.NOC]

function requestFromUrl() {
  const action = readUrlParam(DEEP_OPEN_PARAM, null)
  const domain = readUrlParam('domain', null)
  return domain && ACTIONS.includes(action) ? { domain, action } : null
}

const sameDomain = (a, b) => a != null && b != null && String(a).toLowerCase() === String(b).toLowerCase()

function stripOpenParam() {
  try {
    const url = new URL(window.location.href)
    if (!url.searchParams.has(DEEP_OPEN_PARAM)) return
    url.searchParams.delete(DEEP_OPEN_PARAM)
    const qs = url.searchParams.toString()
    window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
  } catch { /* history yok — en iyi çaba */ }
}

/**
 * SSL sertifikası derin bağlantısı (2026-09-28, 7/24 Kapsamı → SSL satırı):
 * `?tab=dashboard&domain=<d>&open=cert` → Pano'da alanın SERTİFİKA PENCERESİ açılır (kartına tıklamakla aynı pencere);
 * `open=noc` → düzenleyebilene envanter formu, "7/24 izleme ekibine bildir" alanına kaydırılmış (yetkisizde pencere).
 * `domain` eskisi gibi Pano aramasını da süzer (App.jsx) — `open`'sız eski e-posta/palet bağlantıları değişmedi.
 *
 * <p>Kaynaklar: sayfa açılışındaki adres (yeni sekme / Ctrl+tık — istek MOUNT'ta okunur, oturum ve Pano verisi gelene
 * dek bekler) ve uygulama içi `navigateTo('dashboard', { domain, open })` olayı (aynı sekme dâhil).
 *
 * <p>Karar sırası (liste `ready` olunca, yalnız Pano sekmesindeyken — kullanıcı beklerken başka sekmeye geçtiyse istek
 * DÜŞER, pencere başka ekranın üstünde kendiliğinden açılmaz):
 * <ol>
 *   <li>Alan Pano listesinde (`certs`, harf duyarsız) → `openCert(domain)` (kart eylemleri dâhil tam pencere).</li>
 *   <li>Listede yok → tekil uç `/history/{domain}` sorulur: Pano listesi yalnız SY takımının alanlarını taşır, 7/24
 *       kapsamı ise UG takımının ve henüz kontrol edilmemiş alanları da gösterir. Uç görmeye izin verirse
 *       `openByDomain(domain)` (Uyarılar sayfasının yedeğiyle aynı: pencere alan adıyla açılır, verisini kendi okur).</li>
 *   <li>Uç reddederse (yok / silinmiş / yetki yok) → `onNotFound()` (toast) — sessizce hiçbir şey olmaması yerine.</li>
 * </ol>
 * `open` tüketilince adresten silinir (Geri/yenile pencereyi yeniden açmasın); `domain` süzgeç olarak kalır.
 *
 * <p><b>Geri</b>: pencere/form App düzeyinde yaşar (sekmeden bağımsız) — izleme sayfalarının penceresi sayfayla birlikte
 * sökülürken bu pencere Geri'de kapsam sayfasının ÜSTÜNDE açık kalıyordu (Playwright, 2026-09-28). Kancanın açtığı
 * pencere Pano'dan ayrılınca `close(kind)` ile kapanır — YALNIZ o pencere/form ve yalnız hâlâ açıksa: `shownCert` /
 * `shownForm` (App'te o an açık pencerenin / formun alan adı) izlenir; kullanıcı açılanı kendisi kapatınca sahiplik biter.
 * Yoksa sonradan elle açtığı başka bir pencere ya da "Düzenle" formu Pano'dan ayrılınca (Geri, 7/24 alanının Ayarlar
 * bağlantısı) sessizce kapanır, formdaki düzenlemeler kaybolurdu.
 */
export function useCertDeepLink({ ready, tab, certs, openCert, openByDomain, editCert, canEdit, onNotFound, close, shownCert, shownForm }) {
  const pending = useRef(undefined)
  if (pending.current === undefined) pending.current = requestFromUrl()
  const [nonce, setNonce] = useState(0)
  const cb = useRef({})
  cb.current = { openCert, openByDomain, editCert, canEdit, onNotFound, close }
  const shown = useRef({})
  shown.current = { cert: shownCert, form: shownForm }
  // Bu kancanın açtığı: { kind: 'cert'|'form', domain, seen } | null. `seen`: ekranda görüldü mü — açma isteği ile
  // yansıması arasında (sonraki render, yedek sorgu) "kullanıcı kapattı" sanılmasın.
  const owned = useRef(null)
  useEffect(() => {
    const o = owned.current
    if (!o) return
    if (sameDomain(o.kind === 'form' ? shownForm : shownCert, o.domain)) o.seen = true
    else if (o.seen) owned.current = null   // kullanıcı kapattı / yerine başka alan açıldı → artık bizim değil
  }, [shownCert, shownForm])
  useEffect(() => {
    if (tab === 'dashboard' || !owned.current) return
    const { kind } = owned.current
    owned.current = null
    cb.current.close?.(kind)
  }, [tab])
  // Yedek sorgunun yanıtı geldiğinde GÜNCEL durum okunur (etki temizliği ile iptal DEĞİL: StrictMode'un bağla/sök/bağla
  // döngüsü ilk koşunun sorgusunu iptal eder, ikinci koşuda istek çoktan tüketilmiş olurdu → geliştirmede hiç açılmazdı).
  const tabRef = useRef(tab)
  tabRef.current = tab
  const mounted = useRef(true)
  const seq = useRef(0)   // yeni istek eskisinin geç yanıtını geçersizler
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])

  useEffect(() => {
    const on = (e) => {
      const p = e?.detail?.params || {}
      if (!p.domain || !ACTIONS.includes(p[DEEP_OPEN_PARAM])) return
      pending.current = { domain: String(p.domain), action: p[DEEP_OPEN_PARAM] }
      setNonce((n) => n + 1)
    }
    window.addEventListener(NAVIGATE_EVENT, on)
    return () => window.removeEventListener(NAVIGATE_EVENT, on)
  }, [])

  useEffect(() => {
    const req = pending.current
    if (!req || !ready) return
    pending.current = null
    const my = ++seq.current
    if (tab !== 'dashboard') return   // beklerken başka sekmeye geçildi → istek düşer
    const own = (kind, domain) => { owned.current = { kind, domain, seen: sameDomain(shown.current[kind], domain) } }
    stripOpenParam()
    const key = req.domain.toLowerCase()
    const cert = (certs || []).find((c) => String(c?.domain ?? '').toLowerCase() === key)
    if (cert) {
      if (req.action === DEEP_OPEN.NOC && cb.current.editCert && cb.current.canEdit?.(cert)) {
        requestNocFieldFocus('SSL')
        own('form', cert.domain)
        cb.current.editCert(cert.domain)
      } else { own('cert', cert.domain); cb.current.openCert(cert.domain) }
      return
    }
    const live = () => mounted.current && my === seq.current && tabRef.current === 'dashboard'
    Promise.resolve()
      .then(() => api.getHistory(req.domain))
      .then((res) => {
        if (!live()) return
        if (res?.success) { own('cert', req.domain); cb.current.openByDomain(req.domain) }
        else cb.current.onNotFound?.(req.domain)
      })
      .catch(() => { if (live()) cb.current.onNotFound?.(req.domain) })
  }, [ready, tab, certs, nonce])
}
