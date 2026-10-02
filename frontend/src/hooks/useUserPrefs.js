import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client'
import {
  isSyncedLocalKey, readLocalSnapshot, planHydration, cleanServerLocal, MAX_LOCAL_VALUE,
  normalizeFavorites, favKey, toggleFavorite as toggleFav, viewsFor as viewsOf, upsertView, renameView as renameV,
  deleteView as deleteV,
} from './userPrefsModel.js'

/**
 * KİŞİSEL TERCİHLER — sunucuda saklama + tarayıcı aynası (2026-10-02, onaylı öneri 23). Tek modül: App.jsx girişten sonra
 * {@link useUserPrefsController}'ı BİR KEZ çağırır ve değeri {@link UserPrefsContext} ile ağaca verir; ekranlar
 * {@link useUserPrefs} ile okur. Aynalanan localStorage anahtarlarının listesi ve gerekçesi `userPrefsModel.js` başlığında.
 *
 * <p><b>Akış:</b>
 * <ol>
 *   <li><b>Giriş:</b> `GET /api/me/preferences` BİR KEZ (ilk boyamayı bekletmez — ekranlar localStorage'dan bugünkü gibi
 *       açılır). Sunucu KAZANIR: sunucudaki değerler localStorage'a yazılır (bundan sonra bağlanan ekranlar onları okur;
 *       kenar çubuğu App'te `hydratedKeys` ile hemen eşitlenir). Tarayıcıda olup sunucuda OLMAYAN değerler TEK PUT ile
 *       yüklenir — boş sunucuda bu, "mevcut tercihler ilk girişte sunucuya taşınır" sözünün kendisidir. Paylaşılan
 *       makinede (tarayıcının kişisel kayıt sahibi BAŞKA bir kullanıcıysa — `canMigrate`, App personalStorage'a bakar)
 *       önceki kişinin tarayıcı tercihleri bu kullanıcının belgesine yüklenmez.</li>
 *   <li><b>Sonraki yazımlar:</b> beyaz listeli anahtarlara yapılan localStorage yazımları (ekran kodu DEĞİŞMEDEN —
 *       `Storage.prototype.setItem/removeItem` oturum boyunca dar bir dokunuşla izlenir) ve açık seçimler (favori, açılış
 *       sekmesi, görünüm) 1 sn toplanıp TEK PUT gönderilir; aynı anda en çok bir istek uçuştadır. Değeri değişmeyen yazım
 *       (ör. ekranın açılışta aynı değeri yeniden yazması) istek üretmez.</li>
 *   <li><b>Hata:</b> GET başarısızsa hiçbir şey aynalanmaz ve yeni özellikler (yıldız, görünümler) çizilmez — uygulama
 *       BUGÜNKÜ gibi localStorage'dan çalışır; sekmeye dönüşte en çok dakikada bir yeniden denenir. PUT ağ/sunucu hatası
 *       sessizdir, gönderilemeyen değişiklik bir SONRAKİ değişiklikle yeniden gönderilir (döngü yok); 4xx (doğrulama)
 *       düzelmeyeceği için atılır (bir sonraki yazımı zehirlemesin).</li>
 *   <li><b>Çıkış / sayfa gizlenmesi:</b> bekleyen değişiklik hemen gönderilir (`keepalive`); App çıkıştan önce
 *       `flush()`'ı bekler (en çok ~2 sn).</li>
 * </ol>
 *
 * <p><b>Bilinen sınır:</b> bir tercih bir cihazda SİLİNİR (removeItem) ama diğer cihazın tarayıcısında hâlâ duruyorsa, o
 * cihaz bir sonraki girişte onu sunucuda "eksik" görüp yeniden yükler. Aynalanan anahtarların neredeyse tamamı yalnız
 * yazılır (silinmez), bu yüzden mezar taşı tutulmadı.
 */

export const UserPrefsContext = createContext(null)

const RETRY_GET_MS = 60_000
const FLUSH_TIMEOUT_MS = 2_000

function safeStorage() {
  try { return globalThis.localStorage || null } catch { return null }
}

/**
 * localStorage yazımlarını izler (ekran kodu değişmeden). Storage nesnesinde ad özelliği atamak bir ANAHTAR yazar
 * (`localStorage.setItem = f` yanlış olurdu) → gerçek Storage'da prototip sarılır; çağrı her zaman özgün yönteme gider,
 * yalnız bu depo için `onWrite(anahtar, değer|null)` bildirilir. Söküm yalnız sarmalayıcı hâlâ en üstteyse geri alır;
 * değilse (üstüne başka sarmalayıcı/test casusu binmiş) sarmalayıcı pasifleşir ve yalnız çağrıyı geçirir.
 */
export function tapStorage(storage, onWrite) {
  if (!storage) return () => {}
  const target = (typeof Storage !== 'undefined' && storage instanceof Storage) ? Storage.prototype : storage
  const origSet = target.setItem
  const origRemove = target.removeItem
  if (typeof origSet !== 'function' || typeof origRemove !== 'function') return () => {}
  let active = true
  function setItem(key, value) {
    const r = origSet.call(this, key, value)
    if (active && this === storage) { try { onWrite(String(key), String(value)) } catch { /* izleme yazımı asla bozmaz */ } }
    return r
  }
  function removeItem(key) {
    const r = origRemove.call(this, key)
    if (active && this === storage) { try { onWrite(String(key), null) } catch { /* yoksay */ } }
    return r
  }
  try { target.setItem = setItem; target.removeItem = removeItem } catch { return () => {} }
  return () => {
    active = false
    try {
      if (target.setItem === setItem) target.setItem = origSet
      if (target.removeItem === removeItem) target.removeItem = origRemove
    } catch { /* yoksay */ }
  }
}

/**
 * Eşitleme motoru — React'ten bağımsız (birim testte sahte zamanlayıcıyla sınanır). `save(patch, opts)` PUT'u yapar,
 * `onSaved(doc)` sunucunun döndürdüğü belgeyi (henüz gönderilmemiş açık seçimler üstüne yazılmış hâliyle) bildirir.
 */
export function createPrefsEngine({ storage, debounceMs = 1000, save, onSaved = () => {} }) {
  let ready = false
  let disposed = false
  let suppress = false
  let timer = null
  let inflight = null
  let again = false
  let pendingTop = {}
  const dirty = new Map()      // localStorage anahtarı → gönderilecek değer (null = sil)
  let sending = new Map()      // uçuştaki local girdileri
  const synced = new Map()     // sunucunun bildiği local değerleri
  const early = new Map()      // belge gelmeden önce kullanıcının değiştirdiği anahtarlar
  const initial = readLocalSnapshot(storage)

  const hasPending = () => dirty.size > 0 || Object.keys(pendingTop).length > 0

  function schedule() {
    if (disposed || !ready) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { timer = null; flush() }, debounceMs)
  }

  function onWrite(key, value) {
    if (suppress || disposed || !isSyncedLocalKey(key)) return
    if (value != null && value.length > MAX_LOCAL_VALUE) return   // sınır üstü değer aynalanmaz (yerelde kalır)
    if (!ready) {
      // Ekranın açılışta AYNI değeri yeniden yazması kullanıcı değişikliği değildir — sunucunun değerini ezmesin.
      if ((initial[key] ?? null) === value) early.delete(key); else early.set(key, value)
      return
    }
    const base = sending.has(key) ? sending.get(key) : (synced.has(key) ? synced.get(key) : null)
    if (base === value) { if (dirty.delete(key) && !hasPending() && timer) { clearTimeout(timer); timer = null } return }
    dirty.set(key, value)
    schedule()
  }

  const untap = tapStorage(storage, onWrite)

  function mergePending(doc) {
    const out = { ...(doc && typeof doc === 'object' ? doc : {}) }
    for (const [k, v] of Object.entries(pendingTop)) { if (v == null) delete out[k]; else out[k] = v }
    return out
  }

  function flush(opts) {
    if (timer) { clearTimeout(timer); timer = null }
    if (!ready || disposed) return Promise.resolve()
    if (inflight) { again = true; return inflight }
    if (!hasPending()) return Promise.resolve()
    const patch = { ...pendingTop }
    const sentTop = pendingTop
    const sentLocal = new Map(dirty)
    if (sentLocal.size) patch.local = Object.fromEntries(sentLocal)
    pendingTop = {}
    dirty.clear()
    sending = sentLocal
    inflight = (async () => {
      let res = null
      let threw = false
      try { res = await save(patch, opts) } catch { threw = true }
      if (disposed) return
      if (res?.success) {
        for (const [k, v] of sentLocal) { if (v == null) synced.delete(k); else synced.set(k, v) }
        // Belgesiz başarılı yanıtta (beklenmez) iyimser durum korunur — boş belgeyle ezilmez.
        if (res.prefs && typeof res.prefs === 'object' && !Array.isArray(res.prefs)) onSaved(mergePending(res.prefs))
        return
      }
      const status = Number(res?.status)
      const permanent = !threw && status >= 400 && status < 500
      if (permanent) return
      // Geçici hata: gönderilemeyen girdiler (daha yenisi yoksa) kuyruğa döner; bir SONRAKİ değişiklikle gider.
      for (const [k, v] of sentLocal) if (!dirty.has(k)) dirty.set(k, v)
      for (const [k, v] of Object.entries(sentTop)) if (!(k in pendingTop)) pendingTop[k] = v
    })().finally(() => {
      inflight = null
      sending = new Map()
      if (again) { again = false; if (hasPending()) schedule() }
    })
    return inflight
  }

  /** Bekleyen her şeyi ŞİMDİ gönderir ve bitmesini bekler (çıkış öncesi). */
  async function flushNow(opts) {
    for (let i = 0; i < 3 && (inflight || hasPending()); i++) {
      if (inflight) { try { await inflight } catch { /* yoksay */ } } else await flush(opts)
    }
  }

  /**
   * Sunucu belgesi geldi: localStorage'a yaz (sunucu kazanır), eksikleri yükle. Döner: yazılan anahtarlar.
   * `migrate=false` (paylaşılan makine: tarayıcıdaki tercihler BAŞKA bir kullanıcıdan kalma — App, personalStorage sahibine
   * bakar): tarayıcıda olup sunucuda olmayanlar bu kullanıcının belgesine YÜKLENMEZ; yalnız bu oturumda yapılan değişiklikler.
   */
  function hydrate(doc, { migrate = true } = {}) {
    if (disposed) return []
    const snapshot = readLocalSnapshot(storage)
    const skip = new Set(early.keys())
    const plan = planHydration(doc?.local, snapshot, skip)
    const { toWrite, server } = plan
    const toUpload = migrate ? plan.toUpload : Object.fromEntries(Object.entries(plan.toUpload).filter(([k]) => skip.has(k)))
    const written = []
    suppress = true
    try {
      for (const [k, v] of Object.entries(toWrite)) {
        try { storage.setItem(k, v); written.push(k) } catch { /* kota / kapalı depo — bu anahtar yerelde eski kalır */ }
      }
    } finally { suppress = false }
    synced.clear()
    for (const [k, v] of Object.entries(server)) synced.set(k, v)
    early.clear()
    for (const [k, v] of Object.entries(toUpload)) dirty.set(k, v)
    ready = true
    if (dirty.size) flush()   // ilk girişte taşıma: TEK PUT, hemen
    return written
  }

  /** Açık seçim (favoriler / açılış sekmesi / görünümler): bir sonraki toplu PUT'a girer. */
  function setTop(key, value) {
    if (!ready || disposed) return
    pendingTop[key] = value == null ? null : value
    schedule()
  }

  function dispose() {
    disposed = true
    if (timer) { clearTimeout(timer); timer = null }
    untap()
  }

  return {
    hydrate, setTop, flush, flushNow, dispose, hasPending,
    isReady: () => ready,
    isBusy: () => !!inflight || hasPending(),
    serverLocal: () => cleanServerLocal(Object.fromEntries(synced)),
  }
}

const IDLE = Object.freeze({ status: 'idle', prefs: {}, hydratedKeys: [] })

/**
 * App düzeyi denetleyici — auth erken-return'lerinden ÖNCE çağrılır (hook kuralı); `user` null iken boşta durur.
 * Döner: bağlam değeri (bkz. {@link useUserPrefs}).
 */
export function useUserPrefsController(user, { debounceMs = 1000, canMigrate = null } = {}) {
  const [state, setState] = useState(IDLE)
  const prefsRef = useRef({})
  const engineRef = useRef(null)
  const canMigrateRef = useRef(canMigrate)
  canMigrateRef.current = canMigrate

  useEffect(() => {
    prefsRef.current = {}
    if (!user) { setState(IDLE); return undefined }
    let alive = true
    let lastAttempt = 0
    const engine = createPrefsEngine({
      storage: safeStorage(),
      debounceMs,
      save: (patch, opts) => api.me.savePreferences(patch, opts),
      onSaved: (doc) => {
        if (!alive) return
        prefsRef.current = doc
        setState((s) => ({ ...s, prefs: doc }))
      },
    })
    engineRef.current = engine

    const load = async () => {
      lastAttempt = Date.now()
      setState((s) => (s.status === 'ready' ? s : { ...s, status: 'loading' }))
      let res = null
      try { res = await api.me.getPreferences() } catch { res = null }
      if (!alive) return
      if (!res?.success) { setState((s) => ({ ...s, status: 'failed' })); return }
      const doc = res.prefs && typeof res.prefs === 'object' && !Array.isArray(res.prefs) ? res.prefs : {}
      const migrate = typeof canMigrateRef.current === 'function' ? canMigrateRef.current() !== false : true
      const hydratedKeys = engine.hydrate(doc, { migrate })
      prefsRef.current = doc
      setState({ status: 'ready', prefs: doc, hydratedKeys })
    }
    load()

    const onVisibility = () => {
      if (document.visibilityState === 'hidden') { engine.flush({ keepalive: true }); return }
      if (!engine.isReady() && Date.now() - lastAttempt >= RETRY_GET_MS) load()
    }
    const onPageHide = () => { engine.flush({ keepalive: true }) }
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', onPageHide)
    return () => {
      alive = false
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', onPageHide)
      engine.dispose()
      if (engineRef.current === engine) engineRef.current = null
    }
  }, [user, debounceMs])

  /** Üst düzey açık seçimi iyimser uygular ve PUT kuyruğuna koyar. Hazır değilse hiçbir şey yapmaz (false). */
  const setTop = useCallback((key, value) => {
    const engine = engineRef.current
    if (!engine || !engine.isReady()) return false
    const next = { ...prefsRef.current }
    if (value == null) delete next[key]; else next[key] = value
    prefsRef.current = next
    setState((s) => ({ ...s, prefs: next }))
    engine.setTop(key, value)
    return true
  }, [])

  const ready = state.status === 'ready'
  const favorites = useMemo(() => normalizeFavorites(state.prefs.favorites), [state.prefs.favorites])
  const favSet = useMemo(() => new Set(favorites.map((f) => favKey(f.type, f.id))), [favorites])

  return useMemo(() => ({
    ready,
    status: state.status,
    prefs: state.prefs,
    hydratedKeys: state.hydratedKeys,
    favorites,
    isFavorite: (type, id) => favSet.has(favKey(type, Number(id))),
    /** → { on, full } ya da hazır değilse null. */
    toggleFavorite: (fav) => {
      if (!ready) return null
      const r = toggleFav(prefsRef.current.favorites, fav)
      if (r.full) return r
      setTop('favorites', r.list.length ? r.list : null)
      return r
    },
    landingTab: typeof state.prefs.landingTab === 'string' ? state.prefs.landingTab : null,
    setLandingTab: (v) => setTop('landingTab', v || null),
    viewsFor: (listKey) => viewsOf(state.prefs.savedViews, listKey),
    /** → { full, replaced } ya da null. */
    saveView: (listKey, name, params) => {
      if (!ready) return null
      const r = upsertView(prefsRef.current.savedViews, listKey, name, params)
      if (!r.full) setTop('savedViews', Object.keys(r.savedViews || {}).length ? r.savedViews : null)
      return r
    },
    /** → { conflict } ya da null. */
    renameView: (listKey, oldName, newName) => {
      if (!ready) return null
      const r = renameV(prefsRef.current.savedViews, listKey, oldName, newName)
      if (!r.conflict) setTop('savedViews', Object.keys(r.savedViews || {}).length ? r.savedViews : null)
      return r
    },
    deleteView: (listKey, name) => {
      if (!ready) return null
      const next = deleteV(prefsRef.current.savedViews, listKey, name)
      setTop('savedViews', Object.keys(next).length ? next : null)
      return true
    },
    /** Bekleyen değişiklikleri gönderir (çıkıştan önce) — en çok ~2 sn bekler. */
    /** Bekleyen yazım yoksa null (çağıran beklemez); varsa bitişi ya da ~2 sn zaman aşımı. */
    flush: () => {
      const engine = engineRef.current
      if (!engine || !engine.isBusy()) return null
      let timer = null
      return Promise.race([engine.flushNow({ keepalive: true }), new Promise((r) => { timer = setTimeout(r, FLUSH_TIMEOUT_MS) })])
        .finally(() => clearTimeout(timer))
    },
  }), [ready, state.status, state.prefs, state.hydratedKeys, favorites, favSet, setTop])
}

const NOOP = Object.freeze({
  ready: false, status: 'idle', prefs: {}, hydratedKeys: [], favorites: [],
  isFavorite: () => false, toggleFavorite: () => null, landingTab: null, setLandingTab: () => false,
  viewsFor: () => [], saveView: () => null, renameView: () => null, deleteView: () => null, flush: () => null,
})

/** Ekranların okuma kancası. Sağlayıcı yoksa (giriş öncesi, yalıtılmış testler) "hazır değil" — yeni öğeler çizilmez. */
export function useUserPrefs() {
  return useContext(UserPrefsContext) || NOOP
}
