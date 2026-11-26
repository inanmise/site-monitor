import { useSyncExternalStore } from 'react'
import { api } from '../../api/client'
import { sharedFetch } from '../../utils/sharedFetch.js'
import { onSessionReset } from '../../utils/sessionResetBus.js'

/**
 * Nöbet Rutinleri menü rozeti (2026-11-26) — TEK paylaşılan yoklayıcı (`GET /api/oncall-routines/me`).
 *
 * <p>Rozet menüde birden çok yerde yüklü olabilir (masaüstü kenar çubuğu + telefon çekmecesi); her biri kendi zamanlayıcısını
 * kursaydı sunucuya dakikada birden çok istek giderdi. Modül düzeyinde abone sayımı: ilk abone yoklamayı başlatır, son abone
 * ayrılınca durur; hepsi aynı rozeti görür. Sekme gizliyken yoklama durur, geri gelince süre dolduysa bir kez tazeler
 * (useVisibleInterval ile aynı tempo). Kişisel Panom kutusu AYNI getiriciyi ({@link fetchRoutineMe}) kullanır: aynı anda
 * gelen iki okuma `sharedFetch` ile tek uçuşta birleşir ve kutunun okuması da rozeti tazeler.
 *
 * <p>Bilinmeyen asla 0 değildir: okuma başarısızsa (ağ, 5xx, 403, oturum bitti) rozet `null` — menüde rozet çizilmez.
 * Sayfa bir görevi işaretleyince {@link refreshRoutineBadge} çağırır: kısa bir bekleme sonra (art arda işaretler tek istekte
 * birleşir) süren eski okumaya katılmadan taze okur. Oturum sıfırlanınca (çıkış) durum silinir — sonraki kullanıcı öncekinin
 * rozetini görmez.
 */
export const ROUTINE_BADGE_POLL_MS = 60_000
/** İlk abone geldiğinde veri bundan tazeyse yeniden çekme (menü yeniden bağlanması, çekmece açılışı). */
const FRESH_ENOUGH_MS = 5_000
/** İşaretleme sonrası tazeleme beklemesi — art arda gelen işaretler tek okumada birleşir. */
export const ROUTINE_BADGE_KICK_MS = 800

const EMPTY = Object.freeze({ data: null, ok: false, at: 0 })

let state = EMPTY
let badge = null          // kararlı nesne: sayı / seviye değişmedikçe aynı başvuru (menü boşuna yeniden çizilmez)
let reqSeq = 0            // başlatılan isteklerin sırası
let appliedSeq = 0        // yazılan en yeni isteğin sırası — geç gelen ESKİ yanıt yenisini ezmez
let timer = null
let resumeTimer = null
let kickTimer = null
const listeners = new Set()

function emit() {
  for (const fn of [...listeners]) {
    try { fn() } catch { /* dinleyici hatası diğerlerini durdurmaz */ }
  }
}

/** Sunucu yanıtı → `{ count, level }` ya da null (bilinmiyor). Seviye `critical` | `warning` | `none`. */
export function badgeOf(snapshot) {
  if (!snapshot?.ok) return null
  const b = snapshot.data?.badge
  if (!b || typeof b !== 'object') return null
  const count = Number(b.count)
  if (!Number.isFinite(count) || count < 0) return null
  const n = Math.floor(count)
  const level = n === 0 ? 'none' : b.level === 'critical' ? 'critical' : 'warning'
  return { count: n, level }
}

function apply(res) {
  const ok = !!(res && res.success !== false && res.data && typeof res.data === 'object')
  state = ok ? { data: res.data, ok: true, at: Date.now() } : { data: null, ok: false, at: Date.now() }
  const next = badgeOf(state)
  const same = next === badge || (next && badge && next.count === badge.count && next.level === badge.level)
  if (!same) badge = next
  emit()
}

/**
 * `/me` getiricisi — rozet ve Panom kutusu ortak kullanır (sharedFetch anahtarı bu işlevin kendisidir). Her yanıt rozet
 * durumuna da yazılır; yalnız en yeni başlatılan isteğinki (eski yanıt yenisini ezmez). Hata yukarı da iletilir (kutu kendi
 * hata durumunu gösterir).
 */
export async function fetchRoutineMe() {
  const my = ++reqSeq
  let res
  try {
    res = await api.oncallRoutines.me()
  } catch (e) {
    if (my > appliedSeq) { appliedSeq = my; apply(null) }
    throw e
  }
  if (my > appliedSeq) { appliedSeq = my; apply(res) }
  return res
}

function load(fresh = false) {
  return sharedFetch(fetchRoutineMe, { fresh }).catch(() => null)
}

function startTimer() {
  if (timer != null || typeof document === 'undefined' || document.hidden) return
  timer = setInterval(() => { load() }, ROUTINE_BADGE_POLL_MS)
}

function stopTimers() {
  if (timer != null) { clearInterval(timer); timer = null }
  if (resumeTimer != null) { clearTimeout(resumeTimer); resumeTimer = null }
}

function onVisibility() {
  if (document.hidden) { stopTimers(); return }
  if (timer != null || resumeTimer != null) return
  // Geri gelince: süre dolduysa bir tazeleme + devam; dolmadıysa kalan süre sonra (tempo korunur; saat geri alındıysa hemen)
  const elapsed = Date.now() - state.at
  if (elapsed >= ROUTINE_BADGE_POLL_MS || elapsed < 0) { load(); startTimer(); return }
  resumeTimer = setTimeout(() => { resumeTimer = null; load(); startTimer() }, ROUTINE_BADGE_POLL_MS - elapsed)
}

function subscribe(listener) {
  listeners.add(listener)
  if (listeners.size === 1) {
    document.addEventListener('visibilitychange', onVisibility)
    if (Date.now() - state.at > FRESH_ENOUGH_MS) load()
    startTimer()
  }
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0) {
      stopTimers()
      if (kickTimer != null) { clearTimeout(kickTimer); kickTimer = null }
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }
}

const getBadge = () => badge
const getNull = () => null
const noSubscribe = () => () => {}

/**
 * Menü rozeti: `{ count, level }` ya da null (kapalı / bilinmiyor / okunamadı). `enabled` false iken (modül bu kullanıcıya
 * kapalı ya da okuma izni yok) hiç istek atılmaz.
 */
export function useRoutineBadge(enabled = true) {
  return useSyncExternalStore(enabled ? subscribe : noSubscribe, enabled ? getBadge : getNull, getNull)
}

/**
 * Sayfa bir görevi işaretleyince / günü tamamlayınca çağırır: rozet hemen tazelensin (yoklamanın 60 sn'sini beklemeden).
 * Art arda çağrılar {@link ROUTINE_BADGE_KICK_MS} içinde tek okumada birleşir; süren eski okumaya katılmaz (`fresh`). Rozet
 * hiçbir yerde gösterilmiyorsa (abone yok) istek atılmaz. `immediate` = beklemeden (testler, sayfa kapanırken).
 */
export function refreshRoutineBadge({ immediate = false } = {}) {
  if (listeners.size === 0) return
  if (kickTimer != null) { clearTimeout(kickTimer); kickTimer = null }
  if (immediate) { load(true); return }
  kickTimer = setTimeout(() => { kickTimer = null; if (listeners.size > 0) load(true) }, ROUTINE_BADGE_KICK_MS)
}

function resetState() {
  if (kickTimer != null) { clearTimeout(kickTimer); kickTimer = null }
  appliedSeq = reqSeq   // süren isteklerin yanıtı artık yazılmaz
  state = EMPTY
  badge = null
  emit()
}

// Oturum sıfırlandı (çıkış, hareketsizlik çıkışı): önceki kullanıcının rozeti ve süren istekleri unutulur
onSessionReset(resetState)

/** Yalnız testler: modül durumunu sıfırla (abone / zamanlayıcı dahil). */
export function resetRoutineBadgeForTests() {
  stopTimers()
  if (kickTimer != null) { clearTimeout(kickTimer); kickTimer = null }
  try { document.removeEventListener('visibilitychange', onVisibility) } catch { /* yok */ }
  listeners.clear()
  reqSeq += 1
  appliedSeq = reqSeq
  state = EMPTY
  badge = null
}
