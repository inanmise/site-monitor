/**
 * Kullanıcıya özel (kişisel veri taşıyabilen) tarayıcı kayıtları — 2026-09-27 regresyon B9 (S3 sınıf 4, paylaşılan
 * makine). Bunlar bir SONRAKİ kullanıcıya kalmamalı:
 *   - `sm.palette.recent` — komut paleti son kullanılanları (yöneticinin kullanıcı arama sonuçları: ad + kullanıcı adı /
 *     e-posta, başka takımların alan adları)
 *   - `sm.dexp.recent`    — alan adı teşhis son sorguları
 *   - `wr.draft.<id>`     — haftalık rapor oturum-kesintisi yedekleri
 *
 * Kural: anahtarlar kullanıcıya göre ayrılır ({@link personalKey}: `<taban>:<kullanıcı>`) ve çıkışta (elle ya da
 * boşta kalma) hepsi silinir ({@link clearPersonalStorage}). Sahip `sm.storage.owner`'da tutulur: oturum düşüp
 * (401 → sayfa yenilenir) BAŞKA biri girerse önceki kişinin kayıtları girişte temizlenir; AYNI kişi geri gelirse
 * (oturum-kesintisi yedeği tam da bunun için) korunur ({@link claimPersonalStorage}). Kardeş desen: `inbox-seen:<kullanıcı>`.
 */
export const OWNER_KEY = 'sm.storage.owner'
export const PERSONAL_PREFIXES = ['sm.palette.recent', 'sm.dexp.recent', 'wr.draft.']

function store() {
  try { return globalThis.localStorage || null } catch { return null }
}

/** Kayıtların sahibi (son giriş yapan kullanıcı adı) ya da null. */
export function storageOwner() {
  try { return store()?.getItem(OWNER_KEY) || null } catch { return null }
}

/** `<taban>:<kullanıcı>`; sahip bilinmiyorsa (giriş öncesi) taban anahtar. */
export function personalKey(base, owner = storageOwner()) {
  return owner ? `${base}:${owner}` : base
}

const isPersonal = (k) => PERSONAL_PREFIXES.some((p) => k === p || k.startsWith(p + ':') || (p.endsWith('.') && k.startsWith(p)))

/** Kişisel kayıtların HEPSİNİ (her kullanıcınınkini) ve sahip işaretini siler — çıkışta. */
export function clearPersonalStorage() {
  const s = store()
  if (!s) return
  try {
    const doomed = []
    for (let i = 0; i < s.length; i++) {
      const k = s.key(i)
      if (k && isPersonal(k)) doomed.push(k)
    }
    doomed.forEach((k) => s.removeItem(k))
    s.removeItem(OWNER_KEY)
  } catch { /* depolama kapalı — silinecek bir şey de yok */ }
}

/**
 * Giriş (ve F5 sonrası oturum geri yükleme): sahip DEĞİŞTİYSE — ya da bilinmiyorsa (eski sürümden kalma anahtarsız
 * kayıtlar kime ait belli değil) — önce hepsi silinir, sonra yeni sahip yazılır. Aynı kişide dokunulmaz.
 */
export function claimPersonalStorage(username) {
  const u = String(username || '').trim()
  if (!u) return
  if (storageOwner() !== u) clearPersonalStorage()
  try { store()?.setItem(OWNER_KEY, u) } catch { /* yoksay */ }
}
