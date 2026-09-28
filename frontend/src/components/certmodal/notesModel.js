/**
 * Sertifika notları — SAF model (2026-09-28, Notlar sekmesinin shadcn yeniden tasarımı). Tel biçimi snake_case
 * (`GET /api/admin/notes/{domain}` → CertificateNote: id, domain, team_id, author_username, author_name, note, category,
 * created_at, updated_at, updated_by, deleted_at, deleted_by — zamanlar UTC, `Z`siz "yyyy-MM-ddTHH:mm:ss").
 *
 * <p>İzin kuralları ESKİ NotesTab'dakiyle birebir (sunucu da aynısını uygular — AdminController notes uçları):
 *   düzenle  = salt okunur değil · silinmemiş · YAZARI · oluşturulalı 24 saatten az;
 *   sil      = salt okunur değil · silinmemiş · yazarı YA DA yönetici;
 *   geri al  = salt okunur değil · silinmiş · yönetici.
 */

export const NOTE_CATEGORIES = ['NOTE', 'DEPLOYMENT', 'INCIDENT', 'RENEWAL']
/** Sunucu sınırı (AdminController.NOTE_MAX_LENGTH) — arayüz anında söyler, garanti sunucuda. */
export const NOTE_MAX_LENGTH = 5000
/** Sunucu düzenleme penceresi (AdminController.NOTE_EDIT_WINDOW_HOURS = 24). */
export const NOTE_EDIT_WINDOW_MS = 24 * 60 * 60 * 1000
/** Sayaç bu orandan sonra uyarı tonuna geçer. */
export const NOTE_NEAR_LIMIT = 0.9

/** Sunucu damgası UTC'dir ama `Z` taşımaz → ms (bozuksa NaN). */
export function noteTs(ts) {
  if (!ts) return NaN
  const s = String(ts).trim()
  return Date.parse(/[zZ]$|[+-]\d{2}:?\d{2}$/.test(s) ? s : s + 'Z')
}

export function isWithinEditWindow(createdAt, now = Date.now()) {
  const ts = noteTs(createdAt)
  if (Number.isNaN(ts)) return false
  return now - ts < NOTE_EDIT_WINDOW_MS
}

export function noteCategory(n) {
  return NOTE_CATEGORIES.includes(n?.category) ? n.category : 'NOTE'
}

/** Notun çağıran için izinleri — tek yer (liste, düğmeler ve testler aynı kuralı okur). */
export function notePermissions(n, { currentUser, isAdmin = false, readOnly = false, now = Date.now() } = {}) {
  const isDeleted = !!n?.deleted_at
  const isAuthor = !!currentUser && n?.author_username === currentUser
  return {
    isDeleted,
    isAuthor,
    canEdit: !readOnly && !isDeleted && isAuthor && isWithinEditWindow(n?.created_at, now),
    canDelete: !readOnly && !isDeleted && (isAuthor || isAdmin),
    canRestore: !readOnly && isDeleted && isAdmin,
  }
}

const fold = (s) => String(s ?? '').toLocaleLowerCase('tr')

/** Arama: not metni, yazar adı / kullanıcı adı ve kategori etiketi içinde (Türkçe büyük-küçük harf duyarsız). */
export function matchesQuery(n, query, catLabel = '') {
  const q = fold(query).trim()
  if (!q) return true
  return [n?.note, n?.author_name, n?.author_username, catLabel].some((v) => fold(v).includes(q))
}

/** Kategori başına sayılar (+ ALL) — silinmişler yalnız gösteriliyorsa sayılır; arama süzgeci UYGULANMIŞ hâliyle. */
export function categoryCounts(notes, { showDeleted = true, query = '', labelOf = () => '' } = {}) {
  const counts = { ALL: 0, NOTE: 0, DEPLOYMENT: 0, INCIDENT: 0, RENEWAL: 0 }
  for (const n of notes || []) {
    if (!showDeleted && n.deleted_at) continue
    const c = noteCategory(n)
    if (!matchesQuery(n, query, labelOf(c))) continue
    counts.ALL += 1
    counts[c] += 1
  }
  return counts
}

/** Görünür notlar: kategori + arama + silinmişleri göster anahtarı (sıra sunucununki: en yeni üstte). */
export function visibleNotes(notes, { category = 'ALL', query = '', showDeleted = true, labelOf = () => '' } = {}) {
  return (notes || []).filter((n) => {
    if (!showDeleted && n.deleted_at) return false
    const c = noteCategory(n)
    if (category !== 'ALL' && c !== category) return false
    return matchesQuery(n, query, labelOf(c))
  })
}

/** Silinmemiş not sayısı — sekme sayacıyla aynı tanım (CertificateModal tabCounts.notes). */
export function liveNoteCount(notes) {
  return (notes || []).filter((n) => !n.deleted_at).length
}
