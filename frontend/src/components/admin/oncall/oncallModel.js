/**
 * Nöbet çizelgesi — saf model (2026-10-11). Sunucu görünümü (`/api/oncall/teams/{id}`) ↔ form; kaydetme gövdesi;
 * istemci doğrulaması (sunucu aynı kuralları uygular); İstanbul yerel devir anı ↔ UTC ISO (DateTimeField UTC tutar).
 * İstanbul'da yaz saati yok: UTC+3 sabit.
 */

const TR_OFFSET_MS = 3 * 3600 * 1000
const pad = (n) => String(n).padStart(2, '0')

/** "yyyy-MM-ddTHH:mm" (İstanbul) → "yyyy-MM-ddTHH:mm:00" (UTC) — DateTimeField değeri. */
export function anchorToUtc(anchor) {
  if (!anchor || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(anchor)) return ''
  const ms = Date.parse(anchor.slice(0, 16) + ':00Z') - TR_OFFSET_MS
  if (!Number.isFinite(ms)) return ''
  return new Date(ms).toISOString().slice(0, 19)
}

/** UTC ISO → İstanbul yerel "yyyy-MM-ddTHH:mm" (sunucu `anchor` biçimi). */
export function utcToAnchor(utc) {
  if (!utc) return ''
  const ms = Date.parse(String(utc).slice(0, 19) + 'Z') + TR_OFFSET_MS
  if (!Number.isFinite(ms)) return ''
  const d = new Date(ms)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`
}

/** Varsayılan ilk devir: önümüzdeki pazartesi 09:00 İstanbul. */
export function defaultAnchor(now = Date.now()) {
  const local = new Date(now + TR_OFFSET_MS)
  const dow = local.getUTCDay()   // 0 = pazar
  const add = ((8 - dow) % 7) || 7
  const d = new Date(Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate() + add, 9, 0))
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T09:00`
}

/** Sunucu görünümü → form. Üye kimlikleri olduğu gibi (opak ya da sayısal) — istemci sayıya çevirmez. */
export function toForm(view) {
  return {
    enabled: !!view?.enabled,
    rotation: view?.rotation === 'DAILY' ? 'DAILY' : 'WEEKLY',
    anchor: view?.anchor || defaultAnchor(),
    notify: !!view?.notify_oncall,
    members: Array.isArray(view?.members) ? view.members.map((m) => ({ id: m.user_id, name: m.name || m.username || '—', active: m.active !== false, missing: !!m.missing })) : [],
  }
}

/**
 * Kaydetme gövdesi (PUT). SİLİNMİŞ hesaplar (`missing`) GİTMEZ (20.166, UIA-A5 / K11): silinen bir kişi hiç nöbetçi
 * olamaz; eski sunucu bilinmeyen kimliği reddediyordu ("listedeki bir kişi bulunamadı — sayfayı yenileyin", yenilemek de
 * bir şey değiştirmiyordu), yenisi sessizce düşürür. Kapsamlı görüntüleyicide silinmiş kişinin kimliği zaten null'dır.
 */
export function buildPayload(form) {
  return {
    enabled: !!form.enabled,
    rotation: form.rotation === 'DAILY' ? 'DAILY' : 'WEEKLY',
    anchor: form.anchor,
    notify_oncall: !!form.notify,
    members: form.members.filter((m) => !m.missing && m.id != null).map((m) => m.id),
  }
}

/** Form değişti mi (kaydet düğmesi). Silinmiş satırı listeden çıkarmak da değişikliktir (kaydedince sunucudan düşer). */
export function isDirty(form, base) {
  const shape = (f) => ({ ...buildPayload(f), members: f.members.map((m) => (m.missing ? '∅' : String(m.id))) })
  return JSON.stringify(shape(form)) !== JSON.stringify(shape(base))
}

/** Listede silinmiş hesap var mı (pencerede uyarı bandı). */
export function hasMissingMembers(form) {
  return !!form?.members?.some((m) => m.missing)
}

export const MAX_MEMBERS = 50

/** İstemci doğrulaması → { alan: i18n anahtarı } (boş = geçerli). */
export function validate(form) {
  const errs = {}
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(form.anchor || '')) errs.anchor = 'oncall.err.anchor'
  if (form.enabled && form.members.length === 0) errs.members = 'oncall.err.noMembers'
  if (form.members.length > MAX_MEMBERS) errs.members = 'oncall.err.tooMany'
  return errs
}

/** Sıradaki üyeyi bir yukarı / aşağı taşı (yeni dizi). */
export function move(list, index, delta) {
  const j = index + delta
  if (index < 0 || index >= list.length || j < 0 || j >= list.length) return list
  const out = list.slice()
  const [x] = out.splice(index, 1)
  out.splice(j, 0, x)
  return out
}

/** Aynı kişiyi iki kez ekleme (kimlik karşılaştırması string — opak / sayısal fark etmez). */
export function addMember(list, person) {
  if (!person || list.some((m) => String(m.id) === String(person.id))) return list
  return [...list, person]
}

/** Yer değiştirme formu doğrulaması. */
export function validateOverride(o) {
  const errs = {}
  if (o.userId == null || o.userId === '') errs.user = 'oncall.err.ovrUser'
  if (!o.start) errs.start = 'oncall.err.ovrStart'
  if (!o.end) errs.end = 'oncall.err.ovrEnd'
  else if (o.start && Date.parse(o.end + 'Z') <= Date.parse(o.start + 'Z')) errs.end = 'oncall.err.ovrOrder'
  return errs
}
