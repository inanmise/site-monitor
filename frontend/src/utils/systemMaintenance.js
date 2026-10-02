/**
 * SİSTEM BAKIM MODU — istemci modeli (2026-10-02, kullanıcı kararı: "global yönetici SiteMonitor'ün kendisini bakıma alır;
 * bakımda yalnız global yöneticiler girer, içerideki kullanıcılar önceden uyarılır ve bakım başlayınca çıkış yapar").
 *
 * Sunucu durumu ZAMANDAN türetir; istemci aynı pencereyi (`start_at` / `end_at`, UTC 'Z'li) oturum yoklamasının (~15 sn) ve
 * `/api/me` / giriş yanıtının EK `maintenance` bloğundan alır. Geri sayım İSTEMCİ saatinde DEĞİL sunucu saatinde hesaplanır:
 * yanıttaki `server_now` ile alındığı an arasındaki fark (`offsetMs`) her hesaba eklenir — yanlış ayarlı bir bilgisayar
 * saati pencereyi erken/geç açmaz.
 *
 * Aşamalar (kullanıcı tarafı): `announced` (duyuru şeridi, kapatılabilir) → `warning` (uyarı şeridi + geri sayım,
 * kapatılabilir) → `final` (son 60 sn: kapatılamayan geri sayım penceresi) → `started` (çıkış). Global yönetici
 * pencere/çıkış görmez; bakımda kalıcı admin şeridi görür. Bakım bitince sunucu bildirim süresince (varsayılan 60 dk)
 * `state: 'ended'` döner → herkese kapatılabilir "Planlı bakım tamamlandı" şeridi; `clientPhase` bunu `none` sayar
 * (pencere/sayaç yok).
 *
 * Oturum kesimi sinyali: sunucu bakım başlayınca global yönetici olmayan oturumun HER isteğine 401
 * `{ code: 'MAINTENANCE', maintenance: {...} }` döner (mezar taşı). `api/client.js` bunu bu modüle iletir (yönlendirme
 * yok); uygulama kısa geri sayımlı pencereyi açar, sonra `/?session=maintenance`. Sinyal sayfa ömründe TEK kez yayılır ve
 * `localStorage` ile öteki sekmelere duyurulur (pasif hesap deseni — utils/accountInactive.js).
 */

export const MAINTENANCE = 'MAINTENANCE'
/** Aynı sekme içi olay adı. */
export const MAINTENANCE_EVENT = 'sm:system-maintenance'
/** Sekmeler arası duyuru anahtarı (değer = JSON {at, maintenance}). */
export const MAINTENANCE_STORAGE_KEY = 'sm.maintenance.signal'
/** Son 60 sn: kapatılamayan geri sayım penceresi. */
export const FINAL_COUNTDOWN_SECONDS = 60
/** Oturum sunucuda kesildikten sonra (401) pencerenin kısa geri sayımı (pasif hesapla aynı). */
export const ENDED_COUNTDOWN_SECONDS = 10
/** Çıkıştan sonra gidilen giriş adresi — giriş sayfası bakım kartını gösterir. */
export const MAINTENANCE_REDIRECT = '/?session=maintenance'
/** Son bilinen pencere (giriş sayfası public uç gelene dek kartı doldurur) — yalnız bu sekme. */
export const LAST_WINDOW_KEY = 'sm.maintenance.last'
const DISMISS_PREFIX = 'sm.maint.dismiss.'
const ZONE = 'Europe/Istanbul'

let signaled = false

/** Yanıt gövdesi bakım sinyali mi? (`code` ya da giriş ucunun `error_code` alanı) */
export function isMaintenancePayload(body) {
  return !!body && typeof body === 'object' && (body.code === MAINTENANCE || body.error_code === MAINTENANCE)
}

/** Son bilinen pencereyi bu sekmenin oturum deposuna yazar (giriş sayfasına taşınır). Depolama kapalıysa sessiz. */
export function rememberWindow(block) {
  if (!block || typeof block !== 'object' || !block.start_at) return
  try { sessionStorage.setItem(LAST_WINDOW_KEY, JSON.stringify(block)) } catch { /* depolama yok */ }
}

export function lastWindow() {
  try {
    const v = JSON.parse(sessionStorage.getItem(LAST_WINDOW_KEY) || 'null')
    return v && typeof v === 'object' ? v : null
  } catch { return null }
}

/**
 * Oturum kesildi sinyalini yayar — sayfa ömründe YALNIZ ilk çağrı etkilidir (eşzamanlı 401'ler tek pencere açar).
 * @param {object} [block] sunucunun `maintenance` bloğu (pencere bilgisi)
 * @returns {boolean} ilk sinyal mi
 */
export function signalMaintenance(block) {
  if (signaled) return false
  signaled = true
  rememberWindow(block)
  try { window.dispatchEvent(new CustomEvent(MAINTENANCE_EVENT, { detail: block || null })) } catch { /* olay yok */ }
  try { localStorage.setItem(MAINTENANCE_STORAGE_KEY, JSON.stringify({ at: Date.now(), maintenance: block || null })) } catch { /* depolama kapalı */ }
  return true
}

/**
 * Sinyali dinler: aynı sekmedeki olay + öteki sekmeden gelen duyuru. Öteki sekmenin duyurusu bu sekmede de sinyali
 * "verilmiş" sayar (bu sekme kendi 401'ini aldığında ikinci pencere açılmaz).
 * @returns {() => void} aboneliği kaldırır
 */
export function onMaintenance(handler) {
  const onEvent = (e) => handler('local', e?.detail || null)
  const onStorage = (e) => {
    if (e?.key !== MAINTENANCE_STORAGE_KEY || !e.newValue || signaled) return
    signaled = true
    let block = null
    try { block = JSON.parse(e.newValue)?.maintenance || null } catch { block = null }
    rememberWindow(block)
    handler('remote', block)
  }
  window.addEventListener(MAINTENANCE_EVENT, onEvent)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(MAINTENANCE_EVENT, onEvent)
    window.removeEventListener('storage', onStorage)
  }
}

export function maintenanceSignaled() { return signaled }

/** Test kancası: sayfa ömrü bayrağını sıfırlar. */
export function resetMaintenanceSignal() { signaled = false }

/** Adres bakım bildirimi taşıyor mu (`?session=maintenance`)? */
export function maintenanceFromUrl() {
  try { return new URLSearchParams(window.location.search).get('session') === 'maintenance' } catch { return false }
}

// ── Sunucu saati ───────────────────────────────────────────────────────────────────────────────────

/** `server_now` ile alındığı an arasındaki fark (ms). Geçersizse 0 (istemci saati). */
export function serverOffset(serverNow, receivedAtMs = Date.now()) {
  const t = Date.parse(serverNow || '')
  return Number.isFinite(t) ? t - receivedAtMs : 0
}

function ms(iso) {
  const t = Date.parse(iso || '')
  return Number.isFinite(t) ? t : null
}

/**
 * İstemci aşaması (sunucu saatine göre): `none` · `announced` · `warning` · `final` (son 60 sn) · `active` (başladı).
 * Bitiş geçtiyse `none` (girişler kendiliğinden açılır).
 */
export function clientPhase(block, nowMs) {
  if (!block || !block.state || block.state === 'none') return 'none'
  // Sunucu "bitti" diyorsa saat hesabı YAPILMAZ (2026-10-02): istemci saati gerideyse / sapma henüz ölçülmemişse
  // bitmiş pencere "sürüyor" sanılıp geri sayım penceresi açılır ve kullanıcı boşuna çıkarılırdı. Bitti şeridi
  // `state === 'ended'` ile ayrıca çizilir (SystemMaintenanceLayer).
  if (block.state === 'ended') return 'none'
  const start = ms(block.start_at), end = ms(block.end_at)
  if (start == null || end == null) return 'none'
  if (nowMs >= end) return 'none'
  if (nowMs >= start) return 'active'
  if (start - nowMs <= FINAL_COUNTDOWN_SECONDS * 1000) return 'final'
  const warnMs = Math.max(0, Number(block.warn_minutes) || 0) * 60_000
  if (nowMs >= start - warnMs) return 'warning'
  return block.state === 'announced' || block.state === 'warning' ? 'announced' : 'none'
}

/** Başlangıca kalan saniye (sunucu saatine göre, yukarı yuvarlanmış; geçmişse 0). */
export function secondsUntil(iso, nowMs) {
  const t = ms(iso)
  if (t == null) return 0
  return Math.max(0, Math.ceil((t - nowMs) / 1000))
}

// ── Biçimlendirme (Europe/Istanbul) ───────────────────────────────────────────────────────────────

function parts(iso) {
  const t = ms(iso)
  if (t == null) return null
  const f = new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  })
  const p = Object.fromEntries(f.formatToParts(new Date(t)).map((x) => [x.type, x.value]))
  return { day: `${p.day}.${p.month}.${p.year}`, time: `${p.hour}:${p.minute}` }
}

/** "02.10.2026 22:00 – 23:00" (aynı gün) / "02.10.2026 22:00 – 03.10.2026 01:00" — İstanbul saati. */
export function windowText(block) {
  const s = parts(block?.start_at), e = parts(block?.end_at)
  if (!s || !e) return ''
  return `${s.day} ${s.time} – ${s.day === e.day ? e.time : `${e.day} ${e.time}`}`
}

/** "HH:mm" (İstanbul). */
export function timeText(iso) {
  return parts(iso)?.time || ''
}

/**
 * Başlangıç / bitiş metinleri + toplam süre (ms) — şeritler ve pencere bakımın NE ZAMAN biteceğini ve NE KADAR
 * süreceğini söyler (2026-10-02 kullanıcı isteği). Aynı gün: yalnız "HH:mm"; gece yarısını aşan pencere: ikisi de
 * "dd.MM.yyyy HH:mm" (yalnız saat "bitiş 01:00" belirsiz kalırdı). Zaman yoksa null.
 */
export function windowEnds(block) {
  const s = parts(block?.start_at), e = parts(block?.end_at)
  if (!s || !e) return null
  const sameDay = s.day === e.day
  const durationMs = Math.max(0, ms(block.end_at) - ms(block.start_at))
  return {
    start: sameDay ? s.time : `${s.day} ${s.time}`,
    end: sameDay ? e.time : `${e.day} ${e.time}`,
    durationMs,
  }
}

/** "02.10.2026 22:00" (İstanbul) — boşsa ''. */
export function dateTimeText(iso) {
  const p = parts(iso)
  return p ? `${p.day} ${p.time}` : ''
}

/** Saniye → {h, m, s} (geri sayım). */
export function splitDuration(totalSeconds) {
  const s = Math.max(0, Math.floor(totalSeconds || 0))
  return { h: Math.floor(s / 3600), m: Math.floor((s % 3600) / 60), s: s % 60 }
}

/** "1:05:09" / "4:09" / "0:42" — geri sayım rakamı (tabular). */
export function clockText(totalSeconds) {
  const { h, m, s } = splitDuration(totalSeconds)
  const pad = (n) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

/** Yöneticinin mesajı (dil) — boşsa null (çağıran varsayılan metni çizer). */
export function messageFor(block, lang) {
  if (!block) return null
  const v = lang === 'en' ? block.message_en : block.message_tr
  return v && String(v).trim() ? String(v).trim() : null
}

// ── Kapatma (duyuru / uyarı / "bakım tamamlandı" şeridi) ────────────────────────────────────────────
// Tür: `announce` · `warning` · `ended` (2026-10-02 — bakım bittikten sonraki kapatılabilir "tamamlandı" şeridi).

function dismissKey(kind, block) {
  return `${DISMISS_PREFIX}${kind}.${block?.id ?? 'x'}.${block?.revision ?? 1}`
}

/** Şerit bu pencere + sürüm için kapatıldı mı? Saat değişirse (sürüm artar) yeniden görünür. */
export function isDismissed(kind, block) {
  try { return localStorage.getItem(dismissKey(kind, block)) === '1' } catch { return false }
}

export function dismiss(kind, block) {
  try { localStorage.setItem(dismissKey(kind, block), '1') } catch { /* depolama yok: bu çizimde kapalı kalır */ }
}

// Sert yönlendirme: pasif hesapla AYNI tek nokta kullanılır (utils/accountInactive.js `assignLocation`) — testler tek
// dışa aktarımı taklit eder.
