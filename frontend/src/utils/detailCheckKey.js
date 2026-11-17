/**
 * Ayrıntı penceresi sekme verisinin "hangi ölçüm için okundu" anahtarı (performans 2026-11-17).
 *
 * Sayfa Hızı (`/resources`) ve Sayfa Bütünlüğü (`/issues`) pencereleri eskiden 30 sn'de bir sekme açık olsun olmasın ve
 * yeni ölçüm olsun olmasın bu uçları yeniden okuyordu. Artık yalnız o sekme GÖRÜNÜRKEN ve izlemenin son ölçüm zamanı
 * değiştiyse (ya da son sessiz okuma başarısız olduysa) okunur; aksi hâlde eldeki veri kalır.
 *
 * Anahtar = izleme kimliği + son ölçüm zamanı (`last_check`, yoksa `checked_at`). Zaman bilinmiyorsa boş — bir sonraki
 * gerçek değerde bir kez fazla okuma olur (güvenli yön: hiç kaçırılmaz).
 *
 * @param {object|null|undefined} row izleme satırı
 * @param {number|string|null} [id] satır yoksa kullanılacak kimlik
 * @returns {string|null}
 */
export function detailCheckKey(row, id = null) {
  const key = row?.id ?? id
  if (key == null) return null
  return `${key}|${row?.last_check ?? row?.checked_at ?? ''}`
}

/**
 * Yeni ölçüm iki adımda yazılır: önce ölçüm satırı (liste `last_check`'i hemen gösterir), sonra AYRI işlemde kaynak / sorun
 * satırları. Ölçümden bu kadar ms geçmeden yapılan sekme okuması eksik / eski olabilir (inceleme 2026-11-17): anahtar
 * SAKLANMAZ, sekme görünürken sonraki tazelemede bir kez daha okunur. Tarayıcı saati gerideyse fazladan okuma olur
 * (güvenli yön).
 */
export const DETAIL_SETTLE_MS = 15_000

/** Sunucu zamanı (zone'suz UTC `yyyy-MM-ddTHH:mm:ss[.SSS]` ya da bölgeli ISO) → ms; okunamazsa null. */
export function serverTimeMs(s) {
  if (!s || typeof s !== 'string') return null
  const iso = /[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : `${s}Z`
  const ms = Date.parse(iso)
  return Number.isNaN(ms) ? null : ms
}

/**
 * Okumadan SONRA saklanacak anahtar: ölçüm {@link DETAIL_SETTLE_MS} içindeyse null (yeniden okunsun), değilse
 * {@link detailCheckKey}. Ölçüm zamanı bilinmiyorsa eski kural (zamansız anahtar).
 */
export function settledDetailCheckKey(row, id = null, now = Date.now()) {
  const key = detailCheckKey(row, id)
  if (key == null) return null
  const ms = serverTimeMs(row?.last_check ?? row?.checked_at)
  if (ms != null && now - ms < DETAIL_SETTLE_MS) return null
  return key
}

/**
 * Sayfa Hızı: okunan kaynak satırlarının ölçüm zamanı izlemenin son ölçümünden eskiyse okuma eski ölçüme aittir (yeni
 * ölçümün satırları henüz yazılmamış) → true. Satır yoksa / zaman okunamıyorsa karar verilmez (false).
 */
export function resourcesOlderThanRow(rows, row) {
  const last = serverTimeMs(row?.last_check ?? row?.checked_at)
  if (last == null || !Array.isArray(rows) || rows.length === 0) return false
  let newest = null
  for (const r of rows) {
    const ms = serverTimeMs(r?.checked_at)
    if (ms != null && (newest == null || ms > newest)) newest = ms
  }
  return newest != null && newest + 1000 < last
}
