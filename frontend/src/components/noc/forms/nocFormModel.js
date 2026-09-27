/**
 * 7/24 İzleme Ekibi (NOC) — İZLEME FORMU alanının SAF modeli (2026-09-27; sözleşme `.migration/noc/CONTRACT.md`).
 *
 * React yok: dokuz izleme formu + sertifika envanter formu (ortak NocNotifyField), toplu işlem çubuğu (BulkActionBar)
 * ve birim testler aynı kuralları buradan okur.
 *
 * Form durumu: `nocNotify` (boolean, varsayılan KAPALI) + `nocGroupIds` (sayı dizisi). BOŞ dizi = "varsayılan
 * gruplar" (sunucuda `null`): e-posta varsayılan gruplara, hiç varsayılan yoksa TÜM aktif gruplara gider ve yönetici
 * varsayılanı değiştirince izleme de onu izler. Dolu dizi = açık seçim (yalnız o gruplar).
 */

/** Yanıttaki `noc_group_ids` (dizi | virgüllü metin | null) → tekil pozitif tamsayı dizisi. null/boş → []. */
export function nocIdsFrom(raw) {
  if (raw == null || raw === '') return []
  const parts = Array.isArray(raw) ? raw : String(raw).split(',')
  const out = []
  for (const p of parts) {
    const n = Number(typeof p === 'string' ? p.trim() : p)
    if (Number.isInteger(n) && n > 0 && !out.includes(n)) out.push(n)
  }
  return out
}

/** İstek gövdesi değeri: boş seçim → `null` (varsayılan gruplar), aksi hâlde kimlik dizisi. */
export function nocGroupIdsBody(ids) {
  const list = nocIdsFrom(ids)
  return list.length ? list : null
}

/** Açık (izlemeye özel) grup seçimi var mı? */
export const isExplicit = (ids) => nocIdsFrom(ids).length > 0

/** Aktif gruplar — seçici bunları listeler (seçenek ucu pasifleri de döndürür: kayıtlı seçimi göstermek için). */
export function activeGroupsOf(options) {
  return (Array.isArray(options) ? options : []).filter((g) => g && g.active === true)
}

/** Boş seçimde e-postanın gideceği gruplar: aktif varsayılanlar; hiç yoksa tüm aktifler (sözleşme). */
export function fallbackIds(options) {
  const active = activeGroupsOf(options)
  const defaults = active.filter((g) => g.is_default === true)
  return (defaults.length ? defaults : active).map((g) => Number(g.id))
}

/**
 * Kayıtlı açık seçimin GEÇERLİ kısmı — sunucu `NocGroupService.resolveTargets` ile birebir:
 *  - Sunucu seçimi kullanılabilir (aktif) gruplarla keser; kesişim BOŞSA (hepsi pasif/silinmiş) açık seçim YOK sayılır
 *    ve e-posta varsayılanlara (yoksa tüm aktiflere) gider → burada da `[]` (seçici varsayılanları gösterir).
 *  - En az bir aktif seçili grup kalıyorsa açık seçim geçerlidir; seçimde kalmış PASİF gruplar da (seçenek listesinde
 *    varsa) döner ki "Pasif" rozetiyle görünüp kaldırılabilsin — sunucu onlara göndermez, yalnız kayıtta dururlar.
 *  - Seçenek listesinde olmayan (silinmiş) kimlik hiç dönmez.
 * (İstemci grubun e-posta listesini bilmez: sunucunun "aktif VE adresi var" koşulunun yalnız `active` yarısı uygulanır.)
 */
export function effectiveSelection(ids, options) {
  const all = Array.isArray(options) ? options.filter(Boolean) : []
  const byId = new Map(all.map((g) => [Number(g.id), g]))
  const known = nocIdsFrom(ids).filter((id) => byId.has(id))
  return known.some((id) => byId.get(id).active === true) ? known : []
}

/** Seçicide işaretli görünen kimlikler: geçerli açık seçim varsa o, yoksa varsayılan küme (önseçili) — sunucuyla aynı. */
export function shownSelection(ids, options) {
  const list = effectiveSelection(ids, options)
  return list.length ? list : fallbackIds(options)
}

const sameSet = (a, b) => a.length === b.length && a.every((x) => b.includes(x))

/**
 * Seçicide bir grubu işaretle / kaldır → yeni `nocGroupIds`.
 *  - Sonuç varsayılan kümeyle AYNIYSA `[]` döner (izleme varsayılanı izlemeye devam eder).
 *  - Son işaretli grup kaldırılamaz (boş seçim sunucuda "varsayılan gruplar" demektir — kullanıcı "hiçbiri"
 *    sanıp varsayılana giden bir izleme bırakmasın); değer değişmeden döner.
 */
export function toggleGroupId(ids, id, options) {
  const current = shownSelection(ids, options)
  const n = Number(id)
  const next = current.includes(n) ? current.filter((x) => x !== n) : [...current, n]
  if (next.length === 0) return nocIdsFrom(ids)
  return sameSet(next, fallbackIds(options)) ? [] : next
}

/**
 * Seçicide listelenecek gruplar: aktifler (varsayılanlar önce, sonra ada göre) + GEÇERLİ açık seçimde kalmış PASİF
 * gruplar (kullanıcı görüp kaldırabilsin; bkz. effectiveSelection — seçimin hepsi pasifse sunucu varsayılana düşer,
 * pasifler de listelenmez). Seçenek listesinde olmayan (silinmiş) kimlik listelenmez — sunucu zaten düşürür.
 */
export function listedGroups(ids, options) {
  const all = Array.isArray(options) ? options.filter(Boolean) : []
  const chosen = effectiveSelection(ids, options)
  const rows = all.filter((g) => g.active === true || chosen.includes(Number(g.id)))
  return rows.sort((a, b) =>
    (Number(b.active === true) - Number(a.active === true))
    || (Number(b.is_default === true) - Number(a.is_default === true))
    || String(a.name ?? '').localeCompare(String(b.name ?? ''), 'tr'))
}

/** Türün 7/24 bildirimi yönetici tarafından kapatılmış mı? (`disabled_types` bilinmiyorsa false — iddia yok.) */
export function isTypeDisabled(type, disabledTypes) {
  return Array.isArray(disabledTypes) && disabledTypes.includes(type)
}

// ── Toplu işlem (POST /api/noc/monitors/bulk) ──────────────────────────────────────────────────

/** Sunucunun tek istekte kabul ettiği en fazla öğe (NocController.MAX_BULK). */
export const BULK_CHUNK = 500

/** Atlama nedenleri (sunucu sözlüğü); bilinmeyen neden OTHER'a düşer. */
export const SKIP_REASONS = ['UNCHANGED', 'FORBIDDEN', 'NOT_FOUND', 'INVALID', 'OTHER']

/** Toplu yanıtları birleştirir → `{ updated, skipped, byReason: { UNCHANGED: n, … } }`. */
export function mergeBulkResults(results) {
  const out = { updated: 0, skipped: 0, byReason: {} }
  for (const r of results || []) {
    out.updated += Number(r?.updated) || 0
    for (const s of Array.isArray(r?.skipped) ? r.skipped : []) {
      const key = SKIP_REASONS.includes(s?.reason) ? s.reason : 'OTHER'
      out.byReason[key] = (out.byReason[key] || 0) + 1
      out.skipped++
    }
  }
  return out
}

/** Diziyi en fazla `size` öğelik parçalara böler. */
export function chunk(list, size = BULK_CHUNK) {
  const out = []
  for (let i = 0; i < (list || []).length; i += size) out.push(list.slice(i, i + size))
  return out
}

/**
 * Sonuç bildiriminin tonu: bir şey değiştiyse `success`; hiçbir şey değişmediyse ve atlamaların HEPSİ "zaten öyleydi"
 * ise `info`; aksi hâlde (yetki yok / bulunamadı) `error`.
 */
export function bulkTone({ updated, skipped, byReason }) {
  if (updated > 0) return 'success'
  if (skipped > 0 && (byReason.UNCHANGED || 0) === skipped) return 'info'
  return 'error'
}

/**
 * Toplu işlem bildirimi `{ tone, text }` — `t` çağıranın çevirmeni. Metin: güncellenen sayı (ya da "zaten öyleydi" /
 * "hiçbiri güncellenmedi"), atlanan varsa NEDENLERİYLE ("2 atlandı: 1 zaten açıktı, 1 için yetkiniz yok"), bir parça
 * istek düştüyse hata eki. Yalnız "zaten öyleydi" atlamaları varsa ek satır yazılmaz (başlık zaten söylüyor).
 */
export function bulkToast(sum, enabled, t, failed = false) {
  const onlyUnchanged = sum.skipped > 0 && (sum.byReason.UNCHANGED || 0) === sum.skipped
  const doneKey = enabled ? (sum.updated === 1 ? 'nocf.bulkOnDone1' : 'nocf.bulkOnDone') : (sum.updated === 1 ? 'nocf.bulkOffDone1' : 'nocf.bulkOffDone')
  const head = sum.updated > 0 ? t(doneKey, sum.updated)
    : onlyUnchanged ? t(enabled ? 'nocf.bulkNothingOn' : 'nocf.bulkNothingOff')
      : t('nocf.bulkNothing')
  const reasons = SKIP_REASONS.filter((r) => sum.byReason[r])
    .map((r) => t(r === 'UNCHANGED' ? (enabled ? 'nocf.skip.UNCHANGED_ON' : 'nocf.skip.UNCHANGED_OFF') : `nocf.skip.${r}`, sum.byReason[r]))
  const parts = [head]
  if (sum.skipped > 0 && !(sum.updated === 0 && onlyUnchanged)) parts.push(t('nocf.bulkSkipped', sum.skipped, reasons.join(', ')))
  if (failed) parts.push(t('nocf.bulkError'))
  return { tone: failed ? 'error' : bulkTone(sum), text: parts.join(' · ') }
}
