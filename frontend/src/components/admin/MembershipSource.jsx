import { useT } from '../../i18n/index.jsx'
import ToneBadge from './ToneBadge.jsx'

/**
 * Takım üyeliği KAYNAĞI ve AD eşitleme sonucu rozetleri (2026-09-26, prod hatası: üye olmayan kullanıcı
 * takımda görünüyordu ve "bu üyelik nereden geldi?" ekrandan cevaplanamıyordu).
 * Kaynak kodları sunucudan: LDAP_GROUP · LDAP_COMPANY · MANUAL · TEAM_MOVE · LEGACY (kaynak kaydı yok).
 * Ayrıntı (grup CN'i / company değeri / yazan) yalnız ipucunda KALMAZ — dokunmatikte görünsün diye
 * çağıran `MembershipSourceDetail` ile satırda da yazar. Test kancası: `data-source`, `data-action`.
 */
const SOURCE_TONE = { LDAP_GROUP: 'info', LDAP_COMPANY: 'info', MANUAL: 'neutral', TEAM_MOVE: 'muted', LEGACY: 'warning' }
const ACTION_TONE = { KEEP: 'success', REMOVE: 'danger', KEEP_LOCKED: 'muted', ADD: 'info', NOT_APPLIED_LOCKED: 'muted' }

export function MembershipSourceBadge({ source, detail }) {
  const t = useT()
  const s = SOURCE_TONE[source] ? source : 'LEGACY'
  const label = t(`mship.src.${s}`)
  return (
    <ToneBadge tone={SOURCE_TONE[s]} data-source={s} title={detail ? `${t('mship.srcLegend')}: ${label} — ${detail}` : `${t('mship.srcLegend')}: ${label}`}>
      {label}
    </ToneBadge>
  )
}

/** Kaynağın kanıtı (grup CN'i, company değeri, yazan · zaman) — küçük soluk metin, kırpılır. */
export function MembershipSourceDetail({ detail, updatedBy, updatedAt }) {
  const parts = [detail, [updatedBy, updatedAt].filter(Boolean).join(' · ')].filter(Boolean)
  if (!parts.length) return null
  const text = parts.join(' — ')
  return <span className="min-w-0 truncate text-xs text-muted-foreground" title={text}>{text}</span>
}

/** "Yeniden eşitlemede / Girişte" sonucu rozeti; `label` verilirse önüne yazılır. */
export function LdapActionBadge({ action, label }) {
  const t = useT()
  if (!action) return null
  const tone = ACTION_TONE[action] || 'muted'
  return (
    <ToneBadge tone={tone} data-action={action} className="h-auto max-w-full whitespace-normal text-left">
      {label ? `${label}: ` : ''}{t(`mship.act.${action}`)}
    </ToneBadge>
  )
}

/** AD desteği rozeti: true → destekliyor, false → desteklemiyor, null → bilinmiyor (çizilmez). */
export function AdSupportBadge({ supported }) {
  const t = useT()
  if (supported == null) return null
  return (
    <ToneBadge tone={supported ? 'success' : 'danger'} data-supported={supported ? 'true' : 'false'}>
      {supported ? t('mship.adSupported') : t('mship.adUnsupported')}
    </ToneBadge>
  )
}
