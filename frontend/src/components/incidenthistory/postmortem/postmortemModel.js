/**
 * Postmortem + aksiyon takibi — saf model (2026-10-11). Şablon bölümleri, durum / aksiyon sözlükleri, son tarih
 * sınıflaması, kirli alan karşılaştırması ve Markdown dışa aktarımı. Sıralar sunucuyla aynı
 * (IncidentPostmortemService.SECTIONS / STATUSES / ACTION_STATUSES / KINDS / PRIORITIES — postmortemModel.test.js).
 */

/** Şablon bölümleri — SIRA rapordaki sıra. Etiket `pm.sec.<key>`, ipucu `pm.sec.<key>Hint`. */
export const SECTIONS = Object.freeze(['summary', 'impact', 'root_cause', 'trigger', 'detection', 'resolution', 'went_well', 'went_wrong', 'lucky', 'lessons'])

export const STATUSES = Object.freeze(['DRAFT', 'IN_REVIEW', 'PUBLISHED'])
export const STATUS_TONE = Object.freeze({ DRAFT: 'muted', IN_REVIEW: 'warning', PUBLISHED: 'success' })

export const ACTION_STATUSES = Object.freeze(['OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED'])
export const ACTION_STATUS_TONE = Object.freeze({ OPEN: 'info', IN_PROGRESS: 'warning', DONE: 'success', CANCELLED: 'muted' })
export const KINDS = Object.freeze(['PREVENT', 'DETECT', 'MITIGATE', 'PROCESS', 'OTHER'])
export const PRIORITIES = Object.freeze(['HIGH', 'MEDIUM', 'LOW'])
export const PRIORITY_TONE = Object.freeze({ HIGH: 'danger', MEDIUM: 'warning', LOW: 'muted' })

export const MAX_SECTION_CHARS = 20000
export const MAX_TITLE_CHARS = 255
export const MAX_DESCRIPTION_CHARS = 4000

export const isOpenAction = (a) => a && (a.status === 'OPEN' || a.status === 'IN_PROGRESS')

/** Son tarih durumu: overdue | today | soon (≤ 3 gün) | later | none | closed. `today` = yyyy-MM-dd (İstanbul, sunucudan). */
export function dueState(a, today) {
  if (!a || !isOpenAction(a)) return 'closed'
  if (!a.due_date) return 'none'
  if (!today) return 'later'
  if (a.due_date < today) return 'overdue'
  if (a.due_date === today) return 'today'
  const d = (Date.parse(a.due_date + 'T00:00:00Z') - Date.parse(today + 'T00:00:00Z')) / 86_400_000
  return d <= 3 ? 'soon' : 'later'
}

/** Gecikme gün sayısı (yalnız overdue). */
export function daysLate(a, today) {
  if (dueState(a, today) !== 'overdue') return 0
  return Math.round((Date.parse(today + 'T00:00:00Z') - Date.parse(a.due_date + 'T00:00:00Z')) / 86_400_000)
}

export const DUE_TONE = Object.freeze({ overdue: 'danger', today: 'warning', soon: 'warning', later: 'muted', none: 'muted', closed: 'muted' })

/** yyyy-MM-dd → GG.AA.YYYY (bozuksa olduğu gibi). */
export function formatDay(iso) {
  if (!iso || !/^\d{4}-\d{2}-\d{2}$/.test(iso)) return iso || '—'
  return `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`
}

/** Sunucu raporu → düzenleme formu (yalnız bölümler + durum). */
export function formFromPostmortem(pm) {
  const f = { status: STATUSES.includes(pm?.status) ? pm.status : 'DRAFT' }
  for (const k of SECTIONS) f[k] = typeof pm?.[k] === 'string' ? pm[k] : ''
  return f
}

/** Değişen alanlar (kaydetme gövdesi yalnız bunlar + sürüm). Rapor hiç yokken ön dolu bölümler de "değişmiş" sayılır. */
export function changedFields(form, original, exists) {
  const out = {}
  for (const k of [...SECTIONS, 'status']) {
    const a = form?.[k] ?? ''
    const b = original?.[k] ?? ''
    if (!exists ? (k === 'status' ? a !== 'DRAFT' : a.trim() !== '') : a !== b) out[k] = a
  }
  return out
}

/**
 * Kayıt yanıtını forma uygular (UIA-4, 2026-10-22): kayıt sürerken yazılan alanlar KORUNUR — yanıt yalnız gönderildiğinden
 * beri değişmemiş alanlara yazılır. Eskiden yanıt formun tamamını değiştiriyor, kayıt sırasında yazılan metin uyarısız
 * kayboluyordu. Korunan alan sunucu kopyasından farklı kalır → form kirli, sonraki Kaydet onları gönderir.
 */
export function mergeAfterSave(current, sent, server) {
  const out = { ...server }
  for (const k of [...SECTIONS, 'status']) {
    if ((current?.[k] ?? '') !== (sent?.[k] ?? '')) out[k] = current?.[k] ?? ''
  }
  return out
}

export const isDirty = (form, original) => [...SECTIONS, 'status'].some((k) => (form?.[k] ?? '') !== (original?.[k] ?? ''))

/** Bölüm uzunluk hataları — { key: true } (mesajı çağıran t ile yazar). */
export function sectionErrors(form) {
  const out = {}
  for (const k of SECTIONS) if ((form?.[k] || '').length > MAX_SECTION_CHARS) out[k] = true
  return out
}

/** Aksiyon formu boş hâli ve sunucuya gövde. */
export const EMPTY_ACTION = Object.freeze({ title: '', description: '', kind: 'PREVENT', priority: 'MEDIUM', owner_user_id: '', due_date: '', status: 'OPEN' })

export function actionForm(a) {
  if (!a) return { ...EMPTY_ACTION }
  return {
    title: a.title || '', description: a.description || '', kind: a.kind || 'PREVENT', priority: a.priority || 'MEDIUM',
    owner_user_id: a.owner_user_id == null ? '' : String(a.owner_user_id), due_date: a.due_date || '', status: a.status || 'OPEN',
  }
}

/**
 * Form → gövde. Yeni aksiyonda tam gövde; DÜZENLEMEDE (`initial` = kayıtlı aksiyon) yalnız DEĞİŞEN alanlar ve asla
 * `status` (formda durum alanı yok) — 2026-10-12: tam gövde, form açıkken sahibin "Tamamlandı" yaptığı aksiyonu yeniden
 * Açık'a çeviriyor; sonradan pasifleşen değişmemiş sahip de her düzenlemeyi 400 ile kilitliyordu.
 */
export function actionPayload(f, initial = null) {
  const full = {
    title: (f.title || '').trim(),
    description: (f.description || '').trim() || null,
    kind: f.kind, priority: f.priority, status: f.status,
    owner_user_id: f.owner_user_id === '' ? null : f.owner_user_id,
    due_date: f.due_date || null,
  }
  if (!initial) return full
  const base = actionPayload(actionForm(initial))
  const out = {}
  for (const k of Object.keys(full)) {
    if (k !== 'status' && full[k] !== base[k]) out[k] = full[k]
  }
  return out
}

/** Aksiyon sayıları (rozet ve sekme başlığı). */
export function actionCounts(actions, today) {
  const list = Array.isArray(actions) ? actions : []
  let open = 0, overdue = 0, done = 0
  for (const a of list) {
    if (isOpenAction(a)) { open++; if (dueState(a, today) === 'overdue') overdue++ }
    else if (a.status === 'DONE') done++
  }
  return { total: list.length, open, overdue, done }
}

/**
 * Markdown dışa aktarım — paylaşılabilir rapor: başlık + künye, bölümler (boşsa "—"), aksiyon tablosu, zaman çizelgesi.
 * `t` sözlük, `fmt` damga biçimleyici (formatDate). Hücrelerdeki `|` kaçışlanır, satır sonları boşluğa döner.
 */
export function buildMarkdown({ incident, postmortem, actions, timeline, t, fmt = (s) => s }) {
  const cell = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ').trim() || '—'
  const lines = []
  lines.push(`# ${t('pm.mdTitle')}: ${incident?.title || '#' + (incident?.id ?? '')}`)
  lines.push('')
  lines.push(`- ${t('pm.mdIncident')}: #${incident?.id ?? '—'}`)
  lines.push(`- ${t('pm.status')}: ${t('pm.status.' + (postmortem?.status || 'DRAFT'))}`)
  if (incident?.severity) lines.push(`- ${t('pm.mdSeverity')}: ${incident.severity}`)
  if (incident?.occurred_at) lines.push(`- ${t('pm.mdOccurred')}: ${fmt(incident.occurred_at)}`)
  if (incident?.resolved_at) lines.push(`- ${t('pm.mdResolved')}: ${fmt(incident.resolved_at)}`)
  if (postmortem?.published_at) lines.push(`- ${t('pm.mdPublished')}: ${fmt(postmortem.published_at)}`)
  lines.push('')
  lines.push(`> ${t('pm.blameless')}`)
  for (const k of SECTIONS) {
    lines.push('')
    lines.push(`## ${t('pm.sec.' + k)}`)
    lines.push('')
    const v = (postmortem?.[k] || '').trim()
    lines.push(v || '—')
  }
  lines.push('')
  lines.push(`## ${t('pm.actionsTitle')}`)
  lines.push('')
  const list = Array.isArray(actions) ? actions : []
  if (!list.length) lines.push(t('pm.noActions'))
  else {
    lines.push(`| ${t('pm.aTitle')} | ${t('pm.aOwner')} | ${t('pm.aDue')} | ${t('pm.aPriority')} | ${t('pm.aStatus')} |`)
    lines.push('| --- | --- | --- | --- | --- |')
    for (const a of list) {
      lines.push(`| ${cell(a.title)} | ${cell(a.owner_name)} | ${cell(a.due_date ? formatDay(a.due_date) : '')} | ${cell(t('pm.prio.' + a.priority))} | ${cell(t('pm.astatus.' + a.status))} |`)
    }
  }
  const tl = Array.isArray(timeline) ? timeline : []
  if (tl.length) {
    lines.push('')
    lines.push(`## ${t('pm.timelineTitle')}`)
    lines.push('')
    for (const e of tl) lines.push(`- ${fmt(e.at)} — ${timelineText(e, t)}`)
  }
  lines.push('')
  return lines.join('\n')
}

/** Zaman çizelgesi olayı → tek satırlık metin (sözlük `pm.tl.<kind>`). */
export function timelineText(e, t) {
  const actor = e.actor || '—'
  switch (e.kind) {
    case 'alert_opened': return t('pm.tl.alert_opened', e.domain || '—', e.alert_level || '—')
    case 'alert_notified': return t('pm.tl.alert_notified', e.count ?? 1)
    case 'alert_acknowledged': return t('pm.tl.alert_acknowledged', actor)
    case 'alert_resolved': return e.actor ? t('pm.tl.alert_resolvedBy', actor) : t('pm.tl.alert_resolved')
    case 'alert_comment': return t('pm.tl.alert_comment', actor, e.text || '')
    case 'action_added': return t('pm.tl.action_added', e.title || '—')
    case 'action_done': return t('pm.tl.action_done', e.title || '—')
    case 'action_cancelled': return t('pm.tl.action_cancelled', e.title || '—')
    case 'postmortem_published': return t('pm.tl.postmortem_published', actor)
    case 'recorded': return t('pm.tl.recorded', actor)
    case 'occurred': case 'detected': case 'resolved': return t('pm.tl.' + e.kind)
    default: return e.kind
  }
}

/** Dosya adı: postmortem-<id>-<başlık>.md (yalnız güvenli karakterler, ≤ 60). */
export function markdownFileName(incident) {
  // Önce kısalt (≤ 120): sondaki `-+$` taraması uzun metinde geriye doğru taramaya dönmesin
  const slug = String(incident?.title || '').slice(0, 120)
    .toLocaleLowerCase('tr')
    .replace(/[çğıöşü]/g, (c) => ({ ç: 'c', ğ: 'g', ı: 'i', ö: 'o', ş: 's', ü: 'u' }[c]))
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60)
  return `postmortem-${incident?.id ?? 'olay'}${slug ? '-' + slug : ''}.md`
}
