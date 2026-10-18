/**
 * Takım ŞABLON KÜTÜPHANESİ (2026-10-18, kullanıcı kararı D17) — saf yardımcılar. Sunucu satırı:
 * `{ id, team_id, name, is_default, lang, section_count, field_count, slot_count, created_at, created_by_name,
 *    updated_at, updated_by_name, version, can_edit }` (şablon gövdesi yok). Liste sunucuda zaten "önce varsayılan, sonra ad"
 * sırasıyla gelir; burada aynı kural yeniden uygulanır (eski / karışık yanıt da aynı görünsün).
 */

const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/** Kütüphanenin kayıt sınırı (sunucu `TechErrorTeamTemplateService.MAX_PER_TEAM`; yanıt `max` taşır). */
export const LIBRARY_MAX = 20
/** Ad sınırı (sunucu `MAX_NAME`). */
export const LIBRARY_NAME_MAX = 100
/** Kütüphane yönetim penceresi: kap bu genişlikten darsa satırlar kart olur (pencere içi, `useElementWidthState`). */
export const LIBRARY_TABLE_MIN_PX = 640

/** Yanıttan kayıt listesi — dizi değilse (yedek mock `[]`, eski sunucu) boş liste; sıralı. */
export function libraryEntries(data) {
  const list = Array.isArray(data?.templates) ? data.templates.filter((e) => e && e.id != null) : []
  return [...list].sort((a, b) => (Number(!!b.is_default) - Number(!!a.is_default))
    || collator.compare(a.name || '', b.name || '') || (Number(a.id) - Number(b.id)))
}

/** Takım varsayılanı (en çok bir; birden çok gelse de ilki). */
export function defaultEntry(entries) {
  return (entries || []).find((e) => e?.is_default) || null
}

/** Seçici seçenekleri: ad (+ " — takım varsayılanı"); adlar sarar (`wrapLabels`). */
export function libraryOptions(entries, defaultSuffix = '') {
  return (entries || []).map((e) => ({
    value: String(e.id),
    label: e.is_default && defaultSuffix ? `${e.name} ${defaultSuffix}` : (e.name || `#${e.id}`),
    entry: e,
  }))
}

/**
 * Yeni kanal penceresinin İLK şablon kaynağı: takımın varsayılanı varsa `library` (o kayıt seçili), yoksa arayüz dilinin
 * sistem varsayılanı (D20). Kullanıcı bir kaynağı elle seçtiyse (`touched`) çağıran bunu uygulamaz.
 */
export function initialSource(entries, uiLang) {
  const d = defaultEntry(entries)
  if (d) return { source: 'library', libraryId: String(d.id) }
  return { source: uiLang === 'en' ? 'en' : 'tr', libraryId: '' }
}

/** Kaydın kısa künyesi: dil · bölüm · alan · yuva (eksik sayı "—", 0 değil). */
export function entryCounts(e) {
  const n = (v) => (Number.isFinite(Number(v)) && v != null ? Number(v) : '—')
  return { lang: e?.lang === 'en' ? 'en' : e?.lang === 'tr' ? 'tr' : null, sections: n(e?.section_count), fields: n(e?.field_count), slots: n(e?.slot_count) }
}

/** İstemci ad kuralı (sunucuyla aynı: kırpılmış, boş değil, ≤ 100). Hata anahtarı ya da null. */
export function libraryNameError(raw) {
  const n = String(raw ?? '').replace(/\s+/g, ' ').trim()
  if (!n) return 'te.lib.err.nameRequired'
  if (n.length > LIBRARY_NAME_MAX) return 'te.lib.err.nameTooLong'
  return null
}

/** Aynı ad (Türkçe, büyük/küçük harf duyarsız) başka bir kayıtta var mı (`exceptId` hariç). */
export function nameTaken(entries, raw, exceptId = null) {
  const key = String(raw ?? '').replace(/\s+/g, ' ').trim().toLocaleLowerCase('tr')
  if (!key) return false
  return (entries || []).some((e) => String(e.id) !== String(exceptId)
    && String(e.name || '').replace(/\s+/g, ' ').trim().toLocaleLowerCase('tr') === key)
}
