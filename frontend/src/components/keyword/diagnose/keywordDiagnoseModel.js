/**
 * Keyword uçtan uca tanılama MODELİ — saf fonksiyonlar (2026-10-04). Sonucun genel kısmı (hüküm, yollar, adımlar,
 * zamanlama, döküm) HTTP tanılamasının modelini kullanır (`httpDiagnoseModel.js`, kod çevirisi keyword ad alanlarını da
 * bilir); burada yalnız anahtar kelime çözümlemesine özgü olanlar: alternatif okumaların sırası/etiketleri, eşleşme
 * bağlamlarının metni ve paylaşılabilir raporun keyword bölümü.
 */
import { formatBytes } from '../../http/diagnose/httpDiagnoseModel.js'
import { KW_HINT_CODES } from './keywordDiagCodes.js'

const HINTS = new Set(KW_HINT_CODES)
const isNum = (v) => typeof v === 'number' && Number.isFinite(v)

/** Alternatif okumaların gösterim sırası (sözleşme: data.keyword.alternatives anahtarları). */
export const ALT_ORDER = ['raw', 'case_insensitive', 'normalized', 'charset', 'visible_text']

/** Kural ifadesi ("en az 1 kez") — mevcut `keyword.expect.*` anahtarları. */
export function rulePhrase(kw, t) {
  const op = ['GTE', 'LTE', 'EQ', 'GT', 'LT'].includes(kw?.operator) ? kw.operator : 'GTE'
  const n = isNum(kw?.match_count) ? kw.match_count : 1
  return t(`keyword.expect.${op}`, n)
}

/**
 * Alternatif okumalar → çizime hazır satırlar `{ key, count, better, label }`. `better` = izlemenin kendi sayımından FAZLA
 * buldu (kelimenin neden kaçtığının kanıtı). Karakter kümesi satırı yalnız denenmiş bir küme varsa.
 */
export function alternativeRows(kw, t) {
  const alt = kw?.alternatives || {}
  const raw = isNum(alt.raw) ? alt.raw : (isNum(kw?.occurrences) ? kw.occurrences : 0)
  const rows = []
  for (const key of ALT_ORDER) {
    if (key === 'charset' && !alt.charset_name) continue
    if (!isNum(alt[key])) continue
    rows.push({
      key, count: alt[key], better: key !== 'raw' && alt[key] > raw,
      label: key === 'charset' ? t('kwdx.alt.charset', alt.charset_name) : t(`kwdx.alt.${key}`),
    })
  }
  return rows
}

/** Sunucunun ipucu listesinden bilinen kodlar. */
export function analysisHints(kw) {
  return Array.isArray(kw?.hints) ? kw.hints.filter((h) => HINTS.has(h)) : []
}

/** Çözümleme bloğu → ipucu kartının beklediği "satır" biçimi (kontrol geçmişiyle aynı metin parametreleri). */
export function pseudoCheck(kw) {
  return {
    occurrences: kw?.occurrences, http_status: kw?.http_status, final_url: kw?.final_url,
    redirect_count: kw?.redirect_count, body_bytes: kw?.body_bytes, body_truncated: kw?.body_truncated,
  }
}

/** Raporun keyword bölümü (`buildReport` `extra`). Ekranda ne varsa: kural, sonuç, meta, alternatifler, ipuçları, bağlamlar. */
export function keywordReportExtra({ w, data, t }) {
  const kw = data?.keyword
  if (!kw) return
  w.h2(t('kwdx.analysis.title'))
  w.kv(t('kwdx.report.keyword'), `« ${kw.keyword ?? ''} » — ${rulePhrase(kw, t)}${kw.case_sensitive ? ` · ${t('kwdx.analysis.caseSensitive')}` : ''}`)
  if (kw.analyzed === false) {
    w.p(t('kwdx.analysis.unanalyzed'))
    return
  }
  w.kv(t('kwdx.analysis.found'), `${kw.occurrences ?? '—'} · ${t(kw.condition_met ? 'kwdx.analysis.conditionMet' : 'kwdx.analysis.conditionFailed')}`)
  if (kw.http_status != null) w.kv(t('kwdx.analysis.status'), `HTTP ${kw.http_status}`)
  if (kw.final_url) w.kv(t('kwdx.analysis.finalUrl'), `${kw.final_url}${kw.redirect_count ? ` (${t('kwhist.redirects', kw.redirect_count)})` : ''}`)
  if (kw.content_type) w.kv(t('kwdx.analysis.contentType'), kw.content_type)
  if (kw.charset) w.kv(t('kwdx.analysis.charset'), kw.charset)
  if (isNum(kw.body_bytes)) {
    w.kv(t('kwdx.analysis.size'), `${formatBytes(kw.body_bytes)} / ${formatBytes(kw.checker_cap_bytes)}${kw.body_truncated ? ` · ${t('kwhist.truncated')}` : ''}`)
  }
  for (const r of alternativeRows(kw, t)) w.li(`${r.label}: ${r.count}`)
  const hints = analysisHints(kw)
  if (hints.length) {
    w.h3(t('kwhist.hintsTitle'))
    for (const h of hints) w.li(`${t(`kwhint.${h}.title`)} — ${t(`kwhint.${h}.fix`)}`)
  }
  const ctx = Array.isArray(kw.contexts) ? kw.contexts : []
  if (ctx.length) {
    w.h3(t('kwdx.analysis.contexts'))
    w.code(ctx.map((c) => `${c.before ?? ''}[${c.match ?? ''}]${c.after ?? ''}`), 'text')
  }
}
