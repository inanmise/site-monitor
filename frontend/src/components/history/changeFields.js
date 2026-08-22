/**
 * Değişiklik geçmişi alan sözlüğü ve DEĞER insancıllaştırması.
 *
 * <p>Ham diff `{"intervalSeconds":{"from":300,"to":60}}` biçiminde gelir. Ekranda ham anahtar ve
 * ham sayı göstermek teknik olarak doğru ama okunmuyor: "intervalSeconds 300 → 60" yerine
 * "Kontrol sıklığı 5 dk → 1 dk" gerçekten anlaşılıyor. Etiketler i18n'den (`chg.field.<key>`),
 * bu dosya yalnız BİÇİMLENDİRME kurallarını taşır.
 */

/** Maskeli değer — sunucu hassas alanları böyle gönderir (AuditDiff.MASK). */
export const MASK = '***'

/**
 * Süre birimleri de ÇEVRİLİR. Sabit "dk/sn" yazmak İngilizce arayüzde Türkçe birim gösterirdi;
 * `t` verilmediğinde (saf birim testleri) Türkçe kısaltmalara düşer.
 */
const unit = (t, key, tr) => (t ? t(`chg.unit${key}`) : tr)

/** Saniye → "5 dk" / "45 sn" / "2 sa". Sıfır ve null olduğu gibi bırakılır. */
export function humanSeconds(v, t) {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return String(v)
  if (n % 3600 === 0) return `${n / 3600} ${unit(t, 'Hour', 'sa')}`
  if (n % 60 === 0) return `${n / 60} ${unit(t, 'Min', 'dk')}`
  return `${n} ${unit(t, 'Sec', 'sn')}`
}

export function humanMillis(v, t) {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return String(v)
  return n % 1000 === 0 ? `${n / 1000} ${unit(t, 'Sec', 'sn')}` : `${n} ms`
}

/** Saniye/milisaniye biçimlendirmesi uygulanacak alanlar — ad kalıbından türetilir. */
const SECONDS_FIELDS = /Seconds$/
const MILLIS_FIELDS = /(Ms|MillisecondS?)$/i

/**
 * Bir alan değerini ekrana uygun metne çevirir.
 *
 * @param key    alan adı (biçimlendirme kararı buna bakar)
 * @param value  ham değer
 * @param ctx    { teamNames: {id: ad}, t } — takım kimliği ADA çevrilir
 */
export function formatValue(key, value, ctx = {}) {
  const t = ctx.t
  if (value === null || value === undefined || value === '') return '—'
  if (value === MASK) return MASK
  if (typeof value === 'boolean' || value === 'true' || value === 'false') {
    const on = value === true || value === 'true'
    return t ? t(on ? 'chg.valueOn' : 'chg.valueOff') : (on ? 'Açık' : 'Kapalı')
  }
  // Takım kimliği tek başına anlamsız bir sayıdır; adı varsa onu göster.
  if (key === 'teamId' && ctx.teamNames && ctx.teamNames[value]) return ctx.teamNames[value]
  if (SECONDS_FIELDS.test(key)) return humanSeconds(value, t)
  if (MILLIS_FIELDS.test(key)) return humanMillis(value, t)
  const s = String(value)
  // Uzun metin (script gövdesi, env JSON) satırı taşırmasın — tamamı title'da durur.
  return s.length > 120 ? s.slice(0, 120) + '…' : s
}

/** `chg.field.<key>` sözlüğünden etiket; karşılığı yoksa ham anahtar (sessiz boşluk olmaz). */
export function fieldLabel(t, key) {
  const k = `chg.field.${key}`
  const label = t(k)
  return label === k ? key : label
}

/**
 * Ham `changes` JSON'unu satır listesine çevirir.
 *
 * <p>ÇÖKERTMEZ: bozuk/eksik JSON boş liste döner (ResponseTimeChart'ın "malformed kayıt düşür"
 * kuralı). Geçmiş ekranı, tek bozuk satır yüzünden ErrorBoundary'ye düşmemeli.
 */
export function parseChanges(raw) {
  if (!raw) return []
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!obj || typeof obj !== 'object') return []
    return Object.keys(obj).map(key => ({
      key,
      from: obj[key] ? obj[key].from : undefined,
      to: obj[key] ? obj[key].to : undefined,
    }))
  } catch {
    return []
  }
}

/** Snapshot JSON → [{key, value}] (ilk değerler paneli). Bozuksa boş liste. */
export function parseSnapshot(raw) {
  if (!raw) return []
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!obj || typeof obj !== 'object') return []
    return Object.keys(obj).map(key => ({ key, value: obj[key] }))
  } catch {
    return []
  }
}

/** Kullanıcı aracısını kısaltır: "Chrome 126 · Windows". Tamı title'da gösterilir. */
export function shortUserAgent(ua) {
  if (!ua) return null
  const browser = /(Edg|Chrome|Firefox|Safari)\/(\d+)/.exec(ua)
  const os = /(Windows NT [\d.]+|Mac OS X [\d_]+|Android [\d.]+|Linux|iPhone)/.exec(ua)
  const b = browser ? `${browser[1] === 'Edg' ? 'Edge' : browser[1]} ${browser[2]}` : null
  const o = os ? os[1].replace('Windows NT 10.0', 'Windows').replace(/_/g, '.') : null
  if (!b && !o) return ua.length > 40 ? ua.slice(0, 40) + '…' : ua
  return [b, o].filter(Boolean).join(' · ')
}
