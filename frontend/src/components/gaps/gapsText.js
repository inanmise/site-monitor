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

/** Sayıya göre tekil anahtar: `n === 1` ise `<anahtar>.one` (İngilizcede "1 gap" / "2 gaps"); metinler iki dilde tanımlı. */
export function tn(t, key, n, ...rest) {
  return t(n === 1 ? `${key}.one` : key, n, ...rest)
}
