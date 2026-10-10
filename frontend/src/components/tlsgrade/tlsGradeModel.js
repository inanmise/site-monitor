/**
 * TLS yapılandırma notu — saf model (2026-10-10). NOT SUNUCUDA HESAPLANIR (`TlsGradeRules`); burada yalnız sunum:
 * not sırası, ton, "notu belirleyen" nedenler (tavanı nota eşit), süzgeç seçenekleri, protokol satırları.
 */
import { TLS_GRADE_REASONS, TLS_GRADES } from './tlsGradeCodes.js'

/** Kod → tavan (null = bilgi notu). */
export const REASON_CAP = Object.fromEntries(TLS_GRADE_REASONS)

/** Süzgeç değerleri: notlar + notsuz. Sunucu `filter_grade` ile aynı. */
export const GRADE_FILTERS = [...TLS_GRADES, 'none']

export function isGrade(g) { return TLS_GRADES.includes(g) }

/** A+ → 6 … F → 1; tanınmayan 0 (büyük = iyi). */
export function gradeRank(g) {
  const i = TLS_GRADES.indexOf(g)
  return i < 0 ? 0 : TLS_GRADES.length - i
}

/** i18n anahtarı ("A+" → `tlsg.grade.Aplus`). */
export function gradeLabelKey(g) { return `tlsg.grade.${String(g).replace('+', 'plus')}` }

/**
 * Not tonu → sınıf. Renk tek sinyal değil (harf görünür); jetonlu tonlar açık/koyu temada çalışır, turuncu yalnız
 * Tailwind paleti (açık/koyu karşılığı yazılı).
 */
export const GRADE_TONE = {
  'A+': 'border-success/50 bg-success/20 text-success dark:bg-success/25',
  A: 'border-success/30 bg-success/10 text-success dark:bg-success/20',
  B: 'border-warning/40 bg-warning/10 text-warning dark:bg-warning/20',
  C: 'border-orange-500/40 bg-orange-500/15 text-orange-700 dark:bg-orange-500/20 dark:text-orange-300',
  D: 'border-destructive/40 bg-destructive/10 text-destructive dark:bg-destructive/20',
  F: 'border-destructive/70 bg-destructive/20 text-destructive dark:bg-destructive/30',
}

/** Notu TAM OLARAK bu değere indiren kodlar ("Neden B?"). */
export function decisiveCodes(grade, codes) {
  if (!isGrade(grade) || !Array.isArray(codes)) return []
  return codes.filter((c) => REASON_CAP[c] === grade)
}

/** Belirleyici dışındaki sınırlayıcı kodlar (kötüden iyiye, sunucu sırası korunur). */
export function otherCodes(grade, codes) {
  if (!Array.isArray(codes)) return []
  const decisive = new Set(decisiveCodes(grade, codes))
  return codes.filter((c) => !decisive.has(c) && REASON_CAP[c] !== undefined && REASON_CAP[c] !== null)
}

/** Satırın not özeti — not yoksa null (rozet çizilmez). */
export function gradeOfRow(cert) {
  const grade = cert?.tls_grade
  if (!isGrade(grade)) return null
  const codes = Array.isArray(cert?.tls_grade_reasons) ? cert.tls_grade_reasons : []
  return { grade, decisive: decisiveCodes(grade, codes), others: otherCodes(grade, codes), drop: dropOfRow(cert) }
}

/** Etkin not düşüşü {from, to, at}; geçersiz/eksikse null. */
export function dropOfRow(cert) {
  const d = cert?.tls_grade_drop
  if (!d || !isGrade(d.from) || !isGrade(d.to)) return null
  return gradeRank(d.to) < gradeRank(d.from) ? d : null
}

/** Süzgeç seçenekleri (facet sayılı). */
export function gradeFilterOptions(t, facets) {
  const n = (k) => (facets?.grades?.[k] != null ? ` (${facets.grades[k]})` : '')
  return GRADE_FILTERS.map((k) => ({ value: k, label: `${k === 'none' ? t('tlsg.filterNone') : k}${n(k)}` }))
}

/**
 * Neden metinleri — başlık parametresizdir (rozet satırında da okunur); "neden" ve "çözüm" sunucunun parametrelerini
 * ({0}, {1}: anahtar boyu, takım adı, gün…) taşır.
 */
export function reasonTexts(t, finding) {
  const code = typeof finding === 'string' ? finding : finding?.code
  const params = (finding && typeof finding === 'object' && Array.isArray(finding.params)) ? finding.params : []
  return {
    code,
    cap: REASON_CAP[code] ?? null,
    title: t(`tlsg.reason.${code}.title`),
    why: t(`tlsg.reason.${code}.why`, ...params),
    fix: t(`tlsg.reason.${code}.fix`, ...params),
  }
}

/**
 * Protokol satırları: TLS 1.3 / 1.2 açık OLMALI, 1.1 / 1.0 KAPALI olmalı. `tone`: ok | bad | unknown.
 * Değer YES / NO / UNKNOWN (sunucu); bilinmeyen gri kalır (tahmin yok).
 */
export function protocolRows(profile) {
  const p = profile?.protocols || {}
  const row = (key, label, wantOn) => {
    const v = p[key]
    const known = v === 'YES' || v === 'NO'
    const tone = !known ? 'unknown' : ((v === 'YES') === wantOn ? 'ok' : 'bad')
    return { key, label, value: known ? v : 'UNKNOWN', tone, wantOn }
  }
  return [row('tls13', 'TLS 1.3', true), row('tls12', 'TLS 1.2', true), row('tls11', 'TLS 1.1', false), row('tls10', 'TLS 1.0', false)]
}

/** Zımbalama / zayıf takım satırı tonu: istenen değer `want`. */
export function triTone(value, want) {
  if (value !== 'YES' && value !== 'NO') return 'unknown'
  return value === want ? 'ok' : 'bad'
}

/** Dağılım (facet `grades`) → [{grade, count}] — yalnız notlar (none hariç), sıfırlar dahil. */
export function distribution(facets) {
  const g = facets?.grades || {}
  return TLS_GRADES.map((grade) => ({ grade, count: Number(g[grade]) || 0 }))
}
