import { useCallback, useState } from 'react'
import { History } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'

/**
 * "Son 7 günde ne değişti" (2026-09-12, zenginleştirme #7): istatistik şeridindeki 9 sayaç anlık —
 * bu satır hareketi söyler: +yeni alan · silinen · yenilenen sertifika · açılan/çözülen alarm.
 *
 * Hata taraması 2026-11-13: alarm sayıları yalnız `alerts.read` iznine (`alerts_visible === false` → o parçalar hiç
 * çizilmez); okunamayan sayı `null` gelir — "değişiklik yok" DENMEZ, "bazı sayılar okunamadı" notu (`data-slot="chg7-unknown"`).
 */
export default function RecentChangesLine({ days = 7 }) {
  const t = useT()
  const [d, setD] = useState(null)
  const load = useCallback(async () => {
    try { const r = await api.getRecentChanges(days); if (r?.success && r.data) setD(r.data) } catch { /* satır süs */ }
  }, [days])
  useVisibleInterval(load, 300_000, true)
  if (!d) return null
  const alertsShown = d.alerts_visible !== false
  const keys = ['added', 'removed', 'renewed', ...(alertsShown ? ['alerts_opened', 'alerts_resolved'] : [])]
  const unknown = keys.some((k) => d[k] == null)
  const parts = [
    d.added > 0 && t('chg7.added', d.added),
    d.removed > 0 && t('chg7.removed', d.removed),
    d.renewed > 0 && t('chg7.renewed', d.renewed),
    alertsShown && d.alerts_opened > 0 && t('chg7.opened', d.alerts_opened),
    alertsShown && d.alerts_resolved > 0 && t('chg7.resolved', d.alerts_resolved),
  ].filter(Boolean)
  return (
    <p className="recent-changes" aria-live="polite">
      <History size={13} aria-hidden="true" /> <b>{t('chg7.prefix', d.days)}</b>{' '}
      {parts.length ? parts.join(' · ') : (unknown ? null : t('chg7.none'))}
      {unknown && <span data-slot="chg7-unknown" className="text-muted-foreground">{parts.length ? ' · ' : ''}{t('chg7.unknown')}</span>}
    </p>
  )
}
