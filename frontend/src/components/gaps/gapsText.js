// Eksikler penceresi — eksik ve kalem METİNLERİ (pencere ve dışa aktarma aynı kuralı kullanır), 2026-11-15. SAF (t verilir).

import { factText } from '../dataquality/dataQualityModel.js'

/** Eksiğin başlık / neden / ne yapmalı anahtarları: veri kalitesi kuralı `dq.rule.*`, profil ve ekler `gaps.*`. */
export function gapTextKeys(g) {
  if (g?.category === 'profile') return { title: `gaps.profile.${g.code}.title`, why: `gaps.profile.${g.code}.why`, fix: null }
  if (g?.dq) return { title: `dq.rule.${g.code}.title`, why: `dq.rule.${g.code}.why`, fix: `dq.rule.${g.code}.fix` }
  return { title: `gaps.code.${g?.code}.title`, why: `gaps.code.${g?.code}.why`, fix: `gaps.code.${g?.code}.fix` }
}

/** Kalem metni: ad (yoksa "Adsız kayıt"); veri kalitesinde kısa neden, ek eksiklerde durum, işlerde takım / son tarih / olay. */
export function gapItemLine(g, it, t) {
  if (!it) return { name: t('gaps.unnamed'), sub: null }
  if (g?.dq) {
    const ft = factText(g.code, it.facts)
    return { name: it.name || it.target || t('gaps.unnamed'), sub: ft ? t(ft.key, ...ft.args) : null }
  }
  const name = it.name || (it.kind === 'person' ? t('gaps.state.missingPerson') : t('gaps.unnamed'))
  const parts = []
  if (it.state) parts.push(t(`gaps.state.${it.state}`))
  if (it.teamName) parts.push(it.teamName)
  if (it.due) parts.push(t('gaps.due', it.due))
  if (it.incident) parts.push(it.incident)
  return { name, sub: parts.length ? parts.join(' · ') : null }
}

/** Okunamayan denetimin etiketi: "Nöbet çizelgesi (Takım A)" ya da "Alarm iletimi". */
export function checkLabel(f, t) {
  const name = t(`gaps.check.${f?.check}`)
  return f?.teamId != null ? t('gaps.checkOnTeam', name, f.teamName || t('gaps.teamUnnamed')) : name
}

/**
 * Dışa aktarmanın "Denetlenemeyenler" satırları (2026-11-17): okunamayan takımlar / denetimler, bu sürümde yapılamayanlar,
 * profil, yetki, denetlenmeyen takımlar, bekleyen işler. Boş liste = her şey okundu.
 */
export function uncheckedLines(gaps, t, { dq = 'yes', moreTeams = 0 } = {}) {
  if (!gaps) return [t('gaps.export.unchecked.all')]
  const out = []
  const seen = new Set()
  const add = (line) => { if (!seen.has(line)) { seen.add(line); out.push(line) } }
  if (gaps.profileFailed) add(t('gaps.export.unchecked.profile'))
  else if (!gaps.profileKnown) add(t('gaps.export.unchecked.profileUnknown'))
  for (const f of Array.isArray(gaps.failedTeams) ? gaps.failedTeams : []) {
    add(t('gaps.export.unchecked.team', f?.name || t('gaps.teamUnnamed')))
  }
  for (const f of Array.isArray(gaps.failedChecks) ? gaps.failedChecks : []) add(t('gaps.export.unchecked.check', checkLabel(f, t)))
  for (const c of Array.isArray(gaps.unsupportedChecks) ? gaps.unsupportedChecks : []) {
    add(t('gaps.export.unchecked.unsupported', t(`gaps.check.${c}`)))
  }
  if (dq === 'unknown') add(t('gaps.export.unchecked.permUnknown'))
  else if (dq === 'no') add(t('gaps.export.unchecked.noPermission'))
  if (Number(moreTeams) > 0) add(tn(t, 'gaps.export.unchecked.moreTeams', Number(moreTeams)))
  // Bekleyen işler kaynağı okunmadı ama yukarıda nedeni yazılmadıysa (ör. istenmedi) ayrıca söylenir
  const taskSources = [...(gaps.failedChecks || []).map((f) => f?.check), ...(gaps.unsupportedChecks || [])]
  const tasksExplained = taskSources.includes('channels') || taskSources.includes('actions') || dq === 'unknown'
  if (gaps.tasksUnchecked && !tasksExplained) add(t('gaps.export.unchecked.tasks'))
  return out
}

/** Sayıya göre tekil anahtar: `n === 1` ise `<anahtar>.one` (İngilizcede "1 gap" / "2 gaps"); metinler iki dilde tanımlı. */
export function tn(t, key, n, ...rest) {
  return t(n === 1 ? `${key}.one` : key, n, ...rest)
}
