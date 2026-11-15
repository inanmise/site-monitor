/**
 * Kayıtta 7/24 onayı (2026-11-15, kullanıcı isteği: "izleme eklerken veya düzenlerken her kaydettiğinde, 7/24 ekibine
 * alarmlar gitsin denilmemişse pencere çıkarıp istiyor mu diye sor; evet derse 7/24'ü açıp kaydet"; karar: HER kayıtta
 * sorulur + "bu izleme için bir daha sorma").
 *
 * <p>Saf kurallar — React yok, ağ yok. Ne zaman SORULUR: 7/24 bildirimi kapalı ∧ alan kilitli değil (paylaşımla yalnız
 * düzenleyebilen yazarın yönlendirme alanları kilitli — 7/24'ü açamaz) ∧ 7/24 bu tür için GERÇEKTEN kullanılabilir (aktif
 * grup var ve tür yöneticice kapatılmamış — bilinmiyorsa SORULMAZ: kayıt bir okuma hatası yüzünden engellenmez) ∧ kişi bu
 * izleme için "bir daha sorma" dememiş.
 *
 * <p>"Bir daha sorma" KİŞİSEL bir tercihtir: localStorage {@link NOC_NOASK_KEY} = `["PING:12", …]` (en çok
 * {@link NOC_NOASK_MAX}, en yenileri kalır); kullanıcı tercihleri aynası bu anahtarı hesapla eşitler (başka cihazda da
 * sorulmaz). Yeni kayıtta seçilirse kayıttan SONRA, sunucunun verdiği kimlikle yazılır.
 */

/** localStorage anahtarı — `userPrefsModel.LOCAL_PREF_KEYS` ve `UserPreferencesService.LOCAL_KEYS` içinde (eşitlenir). */
export const NOC_NOASK_KEY = 'sm.noc.noAsk'
/** Saklanan izleme sayısı tavanı (değer ≤ 16 KB — sunucunun `MAX_LOCAL_VALUE` sınırının altında kalır). */
export const NOC_NOASK_MAX = 600
/** Kayıt anında 7/24 seçeneklerini beklemenin üst sınırı: gelmezse SORULMAZ, kayıt sürer (asılı bekleme yok). */
export const NOC_OPTIONS_WAIT_MS = 3000

const KEY_RE = /^[A-Z_]{2,20}:\d{1,18}$/

/** `PING` + 12 → `PING:12`; kimlik geçersizse null (yeni kayıt — henüz kimlik yok). */
export function noAskKey(type, id) {
  if (type == null || id == null || id === '') return null
  const k = `${String(type).trim().toUpperCase()}:${String(id).trim()}`
  return KEY_RE.test(k) ? k : null
}

/** Saklı listeyi okur; bozuk / okunamayan değer boş liste (hiçbir şey "sorma" sayılmaz). */
export function readNoAsk(storage) {
  try {
    const raw = storage?.getItem?.(NOC_NOASK_KEY)
    if (!raw) return []
    const v = JSON.parse(raw)
    if (!Array.isArray(v)) return []
    return v.filter((x) => typeof x === 'string' && KEY_RE.test(x)).slice(-NOC_NOASK_MAX)
  } catch {
    return []
  }
}

/** Anahtarı ekler (tekrar etmez, en yeni sona); tavanı aşan en eskiler düşer. Yazılamazsa sessizce geçer. */
export function addNoAsk(storage, key) {
  if (!key || !KEY_RE.test(key)) return false
  const list = readNoAsk(storage).filter((x) => x !== key)
  list.push(key)
  try {
    storage?.setItem?.(NOC_NOASK_KEY, JSON.stringify(list.slice(-NOC_NOASK_MAX)))
    return true
  } catch {
    return false
  }
}

/** Bu izleme için "bir daha sorma" denmiş mi. */
export function isNoAsk(storage, key) {
  return key != null && readNoAsk(storage).includes(key)
}

/**
 * 7/24 bu tür için kullanılabilir mi: true / false / null (bilinmiyor).
 * Tür yöneticice kapalıysa false (açmak bir şey değiştirmez); sunucunun "aktif grup var" hükmü varsa o; yoksa grup
 * listesinden; ikisi de yoksa bilinmiyor.
 */
export function nocAvailable(options, type) {
  if (!options || typeof options !== 'object') return null
  if (Array.isArray(options.disabledTypes) && options.disabledTypes.includes(type)) return false
  if (typeof options.hasActiveGroup === 'boolean') return options.hasActiveGroup
  if (Array.isArray(options.groups)) return options.groups.some((g) => g && g.active === true)
  return null
}

/** Sorulur mu — yalnız kesin "kullanılabilir" ve açılabilir bir alan için. */
export function shouldAskNoc({ nocNotify, locked, available, noAsk }) {
  return !nocNotify && !locked && available === true && !noAsk
}

/** E-postanın gideceği gruplar (seçim boşken): aktif varsayılanlar, yoksa tüm aktifler — sunucu kuralı. Adlar. */
export function defaultGroupNames(groups) {
  const active = (Array.isArray(groups) ? groups : []).filter((g) => g && g.active === true)
  const defaults = active.filter((g) => g.is_default === true)
  return (defaults.length ? defaults : active).map((g) => String(g.name || '')).filter(Boolean)
}

/** Bir sözü en çok `ms` bekler; süre dolarsa `fallback` döner. Zamanlayıcı her durumda temizlenir. */
export function withTimeout(promise, ms, fallback = null) {
  let timer = null
  return Promise.race([
    Promise.resolve(promise).catch(() => fallback),
    new Promise((resolve) => { timer = setTimeout(() => resolve(fallback), ms) }),
  ]).finally(() => clearTimeout(timer))
}
