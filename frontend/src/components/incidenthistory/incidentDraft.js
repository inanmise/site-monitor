/**
 * Olay kaydı formunun YEREL taslağı ("Sağlam Kayıt", 2026-11-20). Form açıkken yapılan değişiklikler ~1 sn duraklamayla
 * tarayıcıya yazılır; sekme kapanır, oturum düşer ya da çakışmada "son hâli yükle" denirse kaybolmaz. Taslak HİÇBİR ZAMAN
 * kendiliğinden uygulanmaz: form açılınca bir bant "Taslağı geri yükle" / "Sil" sorar.
 *
 * <p>Anahtar `sm.ih.draft.<kapsam>:<kullanıcı>` (`utils/personalStorage` — PERSONAL_PREFIXES'te; çıkışta ve kullanıcı
 * değişince silinir). Kapsam: düzenlemede kayıt kimliği, yeni kayıtta `new`, alarmdan açılan yeni kayıtta `new.alert.<id>`.
 * İçerik `{ v: 1, at, base_version, base_updated_at, changes }` — `changes` yalnız açılıştaki kayda göre DEĞİŞEN ham form
 * değerleri (`formChanges`). 7 günden eski, bozuk ya da 256 KB'tan büyük taslak okunmaz (ve silinir) / yazılmaz.
 * Markdown'daki taslak görsel bağlantıları metnin parçası olarak saklanır; görsel kaydedilince olaya bağlanır (sunucu kuralı
 * değişmedi: yalnız yükleyenin bağlanmamış görseli).
 */
import { personalKey, storageOwner } from '../../utils/personalStorage.js'

export const DRAFT_PREFIX = 'sm.ih.draft.'
export const DRAFT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000
export const DRAFT_MAX_BYTES = 256 * 1024
export const DRAFT_DEBOUNCE_MS = 1000

function store() {
  try { return globalThis.localStorage || null } catch { return null }
}

/**
 * Taslak kapsamı: düzenleme → kayıt kimliği; yeni kayıt → `new` (alarmdan açıldıysa `new.alert.<alarm>`, bir kayıttan
 * çoğaltıldıysa — "Hızlı Giriş", 2026-11-20 — `new.copy.<kaynak kayıt>`; hızlı kayıt düz `new` kapsamını kullanır).
 */
export function draftScope(mode, initial, copyOf = null) {
  if (mode !== 'create' && initial?.id != null) return String(initial.id)
  if (copyOf != null && copyOf !== '' && /^\d{1,18}$/.test(String(copyOf))) return `new.copy.${copyOf}`
  const alert = initial?.alert_event_id
  return alert != null && alert !== '' ? `new.alert.${alert}` : 'new'
}

export const draftKey = (scope) => personalKey(DRAFT_PREFIX + scope)

/**
 * Taslağı okur: yok / bozuk / süresi dolmuş / boş → null (bozuk ve süresi dolmuş kayıt silinir). `now` test içindir.
 */
export function readDraft(scope, now = Date.now()) {
  const s = store()
  if (!s || !storageOwner()) return null
  const key = draftKey(scope)
  let raw = null
  try { raw = s.getItem(key) } catch { return null }
  if (!raw) return null
  let d = null
  try { d = raw.length <= DRAFT_MAX_BYTES ? JSON.parse(raw) : null } catch { d = null }
  const ok = d && typeof d === 'object' && d.v === 1 && Number.isFinite(d.at)
    && d.changes && typeof d.changes === 'object' && !Array.isArray(d.changes)
    && Object.keys(d.changes).length > 0 && now - d.at <= DRAFT_MAX_AGE_MS && d.at <= now + 60_000
  if (!ok) {
    try { s.removeItem(key) } catch { /* yoksay */ }
    return null
  }
  return d
}

/**
 * Taslağı yazar. Değişiklik yoksa SİLER (geri alınmış düzenleme taslak bırakmaz). 256 KB'ı aşan taslak yazılmaz (false).
 * Sahip işareti yoksa HİÇBİR ŞEY yazılmaz: çıkış ve oturum kesilmeleri önce `clearPersonalStorage()` ile işareti siler,
 * ardından gelen kapanış / `pagehide` yazımı olay metnini ortak makinede kullanıcı ekisiz bir anahtarda bırakmasın.
 * Okuma da sahipsiz anahtara bakmaz.
 */
export function writeDraft(scope, { changes, baseVersion = null, baseUpdatedAt = null }, now = Date.now()) {
  const s = store()
  if (!s || !storageOwner()) return false
  if (!changes || Object.keys(changes).length === 0) { removeDraft(scope); return true }
  const doc = { v: 1, at: now, base_version: baseVersion, base_updated_at: baseUpdatedAt, changes }
  let text
  try { text = JSON.stringify(doc) } catch { return false }
  if (text.length > DRAFT_MAX_BYTES) return false
  try { s.setItem(draftKey(scope), text); return true } catch { return false }
}

export function removeDraft(scope) {
  try { store()?.removeItem(draftKey(scope)) } catch { /* yoksay */ }
}

/** Taslağı forma uygular — yalnız taslaktaki (değişmiş) alanlar; form anahtarları dışındaki her şey yok sayılır. */
export function applyDraft(form, changes, allowedKeys) {
  const out = { ...form }
  for (const [k, v] of Object.entries(changes || {})) {
    if (allowedKeys && !allowedKeys.includes(k)) continue
    out[k] = v
  }
  return out
}
