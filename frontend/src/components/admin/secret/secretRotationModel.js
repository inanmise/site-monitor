/**
 * Gizli anahtar döndürme — saf model (2026-10-11). Sunucu `/api/admin/secret-tools/rotation` durumundan ekranın
 * aşamasını, KPI kartlarını ve konum listesini çıkarır. Hesap yok: sayılar sunucudan, burada yalnız sınıflama.
 */

/** Konum süzgeci (KPI kartına dokununca). */
export const ROTATION_FILTERS = ['all', 'current', 'previous', 'unreadable']

/**
 * Ekran aşaması:
 *  - `dev_key`     güncel anahtar ayarlı değil (gömülü geliştirme anahtarı) — önce gerçek anahtar.
 *  - `pending`     önceki anahtar ayarlı ve ona bağlı değer var — yeniden şifrele.
 *  - `done`        önceki anahtar ayarlı, ona bağlı değer kalmadı — önceki anahtarı kaldır.
 *  - `incomplete`  önceki anahtar ayarlı ama sayım eksik (bir konum okunamadı ya da sayım tavanına ulaştı) — kaldırma önerilmez.
 *  - `done_unreadable` önceki anahtar ayarlı, ona bağlı değer kalmadı (kaldırılabilir) ama bazı değerler İKİ anahtarla da
 *                  çözülemiyor — başka bir anahtarla şifrelenmiş; kayıtlarda yeniden girilmeli.
 *  - `unreadable`  önceki anahtar YOK ve hiçbir anahtarla çözülemeyen değer var — eski anahtarı önceki olarak ver ya da
 *                  değerleri yeniden gir.
 *  - `steady`      döndürme yok, her şey güncel anahtarda.
 */
export function rotationPhase(status) {
  if (!status) return null
  const key = status.key || {}
  const totals = status.totals || {}
  if (!key.current_configured) return 'dev_key'
  if (key.previous_configured) {
    if ((totals.previous || 0) > 0) return 'pending'
    // Sayım eksikse (okunamayan konum / tavan) önce o söylenir — kaldırma önerilmez
    if (!status.previous_removable) return 'incomplete'
    // Önceki anahtara bağlı değer kalmadı ama iki anahtarla da çözülemeyen değer var: "tüm değerler güncel anahtarda"
    // denmez, "önceki anahtarı ver" de denmez (zaten ayarlı) — 2026-10-12
    if ((totals.unreadable || 0) > 0) return 'done_unreadable'
    return 'done'
  }
  if ((totals.unreadable || 0) > 0) return 'unreadable'
  return 'steady'
}

/** Aşama → AlertBanner tonu. */
export const PHASE_TONE = {
  dev_key: 'warning',
  pending: 'info',
  done: 'success',
  incomplete: 'warning',
  done_unreadable: 'warning',
  unreadable: 'danger',
  steady: 'success',
}

/** Konumun toplam şifreli değer sayısı. */
export function locationTotal(l) {
  return (l?.current || 0) + (l?.previous || 0) + (l?.unreadable || 0)
}

/**
 * Listede gösterilecek konumlar + değersiz konumların adları. `all`: değeri olan (ya da okuma hatası taşıyan) her
 * konum; diğer süzgeçler o yuvada değeri olanlar. Değeri olmayan konumlar ayrı bir satırda adlarıyla anılır.
 */
export function splitLocations(locations, filter = 'all') {
  const list = Array.isArray(locations) ? locations : []
  const withValues = list.filter((l) => locationTotal(l) > 0 || l.error)
  const empty = list.filter((l) => locationTotal(l) === 0 && !l.error).map((l) => l.label)
  const shown = filter === 'all' ? withValues : withValues.filter((l) => (l[filter] || 0) > 0)
  return { shown, empty }
}

/** URL/durumdan gelen süzgeç değerini doğrular. */
export function normFilter(v) {
  return ROTATION_FILTERS.includes(v) ? v : 'all'
}
