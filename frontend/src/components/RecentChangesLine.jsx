import { useCallback, useState } from 'react'
import { History } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'

/**
 * "Son 7 günde ne değişti" (2026-09-12, zenginleştirme #7): istatistik şeridindeki 9 sayaç anlık —
 * bu satır hareketi söyler: +yeni alan · silinen · yenilenen sertifika · açılan/çözülen alarm.
 */
export default function RecentChangesLine({ days = 7 }) {
  const t = useT()
  const [d, setD] = useState(null)
  const load = useCallback(async () => {
    try { const r = await api.getRecentChanges(days); if (r?.success && r.data) setD(r.data) } catch { /* satır süs */ }
  }, [days])
  useVisibleInterval(load, 300_000, true)
  if (!d) return null
  const parts = [
    d.added > 0 && t('chg7.added', d.added),
    d.removed > 0 && t('chg7.removed', d.removed),
    d.renewed > 0 && t('chg7.renewed', d.renewed),
    d.alerts_opened > 0 && t('chg7.opened', d.alerts_opened),
    d.alerts_resolved > 0 && t('chg7.resolved', d.alerts_resolved),
  ].filter(Boolean)
  return (
    <p className="recent-changes" aria-live="polite">
      <History size={13} aria-hidden="true" /> <b>{t('chg7.prefix', d.days)}</b> {parts.length ? parts.join(' · ') : t('chg7.none')}
    </p>
  )
}
