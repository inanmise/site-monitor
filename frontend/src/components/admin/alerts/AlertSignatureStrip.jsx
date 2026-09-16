import { History, Clock3, ArrowRightCircle } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'

/**
 * Alarm kartı imza şeridi (2026-09-16, kullanıcı isteği): "bu alarm hangi takımın, daha önce kaç kez
 * açıldı, önceki oluşum ne zamandı, en son ne zaman görüldü / ne zamandır sessiz" + aynı imzanın
 * geçmişine tek tıkla geçiş.
 *
 * <p>İmza = alan adı + alarm tipi (sunucudaki `RepeatKey` ile aynı tanım). Sayılar liste ucundan
 * TEK toplu sorguyla gelir (history_*), kart başına istek YOKTUR.
 */
const DAY = 86_400_000

/** ISO (zone'suz = UTC) damgayı ms'e çevirir; bozuksa null. */
function ms(iso) {
  if (!iso) return null
  const d = new Date(String(iso).endsWith('Z') ? iso : iso + 'Z')
  return isNaN(d) ? null : d.getTime()
}

/** Gün farkı (tam gün, aşağı yuvarlı); geçersiz damgada null. */
export function daysSince(iso, now = Date.now()) {
  const t = ms(iso)
  return t == null ? null : Math.max(0, Math.floor((now - t) / DAY))
}

export default function AlertSignatureStrip({ alert: a, teamName, onShowHistory }) {
  const t = useT()
  const count = Number(a?.history_count || 0)
  const prev = a?.history_prev_at || null
  const lastResolved = a?.history_last_resolved_at || null
  const isOpen = !a?.resolved
  // "Ne zamandır sessiz": KAPALI alarmda kendi kapanışından bu yana geçen süre; açık alarmda
  // sessizlik yoktur (alarm sürüyor), onun yerine önceki oluşumla arasındaki boşluk anlamlıdır.
  const quietDays = !isOpen ? daysSince(a?.resolved_at || lastResolved) : null
  const gapDays = prev ? daysSince(prev, ms(a?.created_at) ?? Date.now()) : null
  const hasTeam = a?.team_id != null && !a?.sy_team_name

  if (count <= 1 && !hasTeam && !prev && quietDays == null) return null

  return (
    <div className="alh-sig">
      {hasTeam && (
        <span className="alh-sig-chip alh-sig-chip--team">
          {t('alh.sig.team')}: <TeamBadge teamId={a.team_id} teamName={teamName} size={11} />
        </span>
      )}
      {count > 1 && (
        <span className="alh-sig-chip" title={t('alh.sig.countTip')}>
          <History size={11} aria-hidden="true" /> {t('alh.sig.count', count)}
        </span>
      )}
      {prev ? (
        <span className="alh-sig-chip" title={t('alh.sig.prevTip')}>
          <Clock3 size={11} aria-hidden="true" /> {t('alh.sig.prev')}: <strong>{formatDate(prev)}</strong>
          {gapDays != null && <span className="alh-sig-dim"> · {gapDays === 0 ? t('alh.sig.gapSameDay') : t('alh.sig.gap', gapDays)}</span>}
        </span>
      ) : count <= 1 ? (
        <span className="alh-sig-chip alh-sig-chip--first">{t('alh.sig.first')}</span>
      ) : null}
      {quietDays != null && (
        <span className="alh-sig-chip alh-sig-chip--quiet" title={t('alh.sig.quietTip')}>
          {t('alh.sig.quiet', quietDays)}
        </span>
      )}
      {count > 1 && (
        <button type="button" className="alh-sig-link" onClick={() => onShowHistory?.(a)}>
          <ArrowRightCircle size={12} aria-hidden="true" /> {t('alh.sig.showHistory')}
        </button>
      )}
    </div>
  )
}
