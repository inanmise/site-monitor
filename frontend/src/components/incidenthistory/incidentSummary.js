/**
 * Olay kaydı özeti (2026-11-26, "Bul ve Raporla") — SAF: tek kaydın paylaşılabilir Markdown özeti ve yazdırma görünümünün
 * alanları. Ayrıntı penceresinin "Özeti indir" / "Yazdır" düğmeleri kaydın TAZE hâlini okur (sınırlı süre) ve buradan kurar;
 * okunamazsa hiçbir şey indirilmez / yazdırılmaz.
 *
 * <p>Kurallar: zamanlar İstanbul yerel (`yyyy-AA-gg SS:dd`), kişiler görünen ad (dizinde yoksa kayıttaki ad), ham kod
 * basılmaz (önem / durum / kategori çevrilir), girilmemiş alan satırı hiç yazılmaz (uydurma "0" / "Hayır" yok — bilinmeyen
 * kesinti cevabı satırı yok). Markdown metinleri olduğu gibi kalır; uygulama içi görsel adresleri (`/api/…`) tam adrese
 * çevrilir (dosya başka yerde açılınca da çalışsın — oturum gerektirir). Doğrusal işlemler; düzenli ifade yok.
 */
import { parseUtc } from '../../utils/incidentMeta.js'
import { csvList, durationOf, formatMinutes, techErrorCountOf, outageAnswerOf, outageIdOf } from './incidentHistoryModel.js'

const ISTANBUL = (() => {
  try {
    return new Intl.DateTimeFormat('sv-SE', {
      timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
    })
  } catch { return null }
})()

/** UTC damga → İstanbul yerel "yyyy-AA-gg SS:dd"; okunamazsa ''. */
export function istanbulStamp(raw) {
  const d = parseUtc(raw)
  if (!d || !ISTANBUL) return ''
  return ISTANBUL.format(d).replace('T', ' ')
}

/** Tablo hücresi: satır sonları boşluk, `|` kaçışlı (Markdown tablosu bozulmasın). */
export function mdCell(v) {
  let out = ''
  for (const ch of String(v ?? '')) {
    if (ch === '|') out += '\\|'
    else if (ch === '\n' || ch === '\r') out += ' '
    else out += ch
  }
  return out.trim()
}

/** Markdown içindeki uygulama içi görsel / bağlantı adreslerini (`](/api/`) tam adrese çevirir — doğrusal. */
export function absolutizeAppLinks(md, origin) {
  const s = String(md ?? '')
  if (!origin) return s
  return s.split('](/api/').join(`](${origin}/api/`)
}

/** Kişi: dizindeki görünen ad, yoksa kayıttaki ad; boş → ''. */
const personOf = (username, nameOf) => {
  if (!username) return ''
  const n = typeof nameOf === 'function' ? nameOf(username) : null
  return n || String(username)
}

/**
 * Özetin olgu satırları — `[etiket, değer]`; yalnız değeri olan satırlar. Markdown özeti ve yazdırma görünümü aynı listeyi
 * kullanır.
 */
export function summaryFacts(r, t, { nameOf = null, locale = 'tr-TR' } = {}) {
  if (!r) return []
  const rows = []
  const add = (labelKey, value) => {
    const v = value == null ? '' : String(value).trim()
    if (v !== '') rows.push([t(labelKey), v])
  }
  const num = (v) => (v === '' || v == null || !Number.isFinite(Number(v)) ? '' : Number(v).toLocaleString(locale))
  add('inc.colSeverity', r.severity ? t('inc.sev' + r.severity) : '')
  add('inc.colStatus', r.status ? t('inc.st' + r.status) : '')
  add('inc.colCategory', r.category ? t('inc.cat' + r.category) : '')
  add('inc.sum.occurred', istanbulStamp(r.occurred_at))
  add('inc.sum.detected', istanbulStamp(r.detected_at))
  add('inc.sum.resolved', istanbulStamp(r.resolved_at))
  const dur = durationOf(r)
  if (dur.minutes != null) add('inc.dDuration', formatMinutes(dur.minutes, t) + (dur.ongoing ? ` (${t('inc.stillOngoing')})` : ''))
  add('inc.colTeam', r.team_name)
  add('inc.colChannel', csvList(r.channel).join(', '))
  add('inc.f.service', csvList(r.service).join(', '))
  add('inc.f.problemType', csvList(r.problem_types).join(', '))
  add('inc.fErrorCode', r.error_code)
  add('inc.fFunctionCode', r.function_code)
  add('inc.fChannelCode', r.channel_code)
  add('inc.dCustomers', num(r.affected_customers))
  add('inc.dTransactions', num(r.affected_transactions))
  const tech = techErrorCountOf(r)
  if (tech != null) add('inc.sum.techErrCount', tech.toLocaleString(locale))
  const outage = outageAnswerOf(r)
  if (outage === true) add('inc.sum.outage', outageIdOf(r) ? `${t('inc.sum.yes')} (${outageIdOf(r)})` : t('inc.sum.yes'))
  else if (outage === false) add('inc.sum.outage', t('inc.sum.no'))
  if (r.sla_breached === true) add('inc.sum.sla', t('inc.sum.yes'))
  else if (r.sla_breached === false) add('inc.sum.sla', t('inc.sum.no'))
  add('inc.sum.tags', csvList(r.tags).join(', '))
  add('inc.sum.runbook', r.runbook_url)
  if (r.alert_event_id != null && r.alert_event_id !== '') add('inc.sum.alert', `#${r.alert_event_id}`)
  const created = [personOf(r.created_by, nameOf), istanbulStamp(r.created_at)].filter(Boolean).join(' · ')
  add('inc.sum.createdBy', created)
  const updated = [personOf(r.updated_by, nameOf), istanbulStamp(r.updated_at)].filter(Boolean).join(' · ')
  add('inc.sum.updatedBy', updated)
  return rows
}

/** Dört markdown bölümü — `[başlık anahtarı, metin]`, boş olanlar dahil (başlık "girilmedi" der). */
export const SUMMARY_SECTIONS = Object.freeze([
  ['inc.fBusinessImpact', 'business_impact'],
  ['inc.fRca', 'rca_summary'],
  ['inc.fDescription', 'description'],
  ['inc.fResolution', 'resolution_steps'],
])

/** Kaydın Markdown özeti (indirilecek metin). `origin` verilirse uygulama içi görsel adresleri tam adrese çevrilir. */
export function incidentMarkdown(r, t, { nameOf = null, locale = 'tr-TR', origin = '', now = new Date() } = {}) {
  if (!r) return ''
  const lines = []
  lines.push(`# ${mdCell(r.title || t('inc.sum.untitled'))}`)
  lines.push('')
  lines.push(`${t('inc.sum.recordNo', r.id)} · ${t('inc.sum.generated', istanbulStamp(now.toISOString().slice(0, 19)))}`)
  lines.push('')
  const facts = summaryFacts(r, t, { nameOf, locale })
  if (facts.length) {
    lines.push(`| ${t('inc.sum.field')} | ${t('inc.sum.value')} |`)
    lines.push('|---|---|')
    for (const [k, v] of facts) lines.push(`| ${mdCell(k)} | ${mdCell(v)} |`)
    lines.push('')
  }
  for (const [titleKey, field] of SUMMARY_SECTIONS) {
    lines.push(`## ${t(titleKey)}`)
    lines.push('')
    const text = String(r[field] ?? '').trim()
    lines.push(text ? absolutizeAppLinks(text, origin) : `_${t('inc.notEntered')}_`)
    lines.push('')
  }
  return lines.join('\n')
}

/** İndirilecek dosyanın adı: `olay-<id>-<başlık kısaltması>.md` (dosya adına uygun karakterler, ≤ 60). */
export function summaryFileName(r) {
  let slug = ''
  for (const ch of String(r?.title ?? '').toLocaleLowerCase('tr')) {
    if (slug.length >= 60) break
    const map = { ç: 'c', ğ: 'g', ı: 'i', ö: 'o', ş: 's', ü: 'u', â: 'a', î: 'i', û: 'u' }
    const c = map[ch] ?? ch
    if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) slug += c
    else if (slug && !slug.endsWith('-')) slug += '-'
  }
  while (slug.endsWith('-')) slug = slug.slice(0, -1)
  return `olay-${r?.id ?? 'kayit'}${slug ? '-' + slug : ''}.md`
}
