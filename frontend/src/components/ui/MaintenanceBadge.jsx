import { useState, useEffect } from 'react'
import { Wrench } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

// Modül-seviyesi cache: tüm badge örnekleri tek /active isteğini paylaşır (30sn TTL) — N monitör kartı = 1 istek.
let _cache = null, _at = 0, _inflight = null
async function getActive() {
  if (_cache && Date.now() - _at < 30000) return _cache
  if (_inflight) return _inflight
  const call = api?.monitoring?.maintenance?.active
  if (typeof call !== 'function') return { all: false, targets: [] }   // API yoksa (ör. test mock'u) sessizce no-op
  _inflight = call()
    .then(res => { _inflight = null; if (res?.success) { _cache = res.data || { all: false, targets: [] }; _at = Date.now() } return _cache || { all: false, targets: [] } })
    .catch(() => { _inflight = null; return _cache || { all: false, targets: [] } })
  return _inflight
}

/** Verilen alarm-anahtarı (host/url/domain) bakım penceresindeyse "Under maintenance" rozeti gösterir; değilse hiçbir şey. */
export default function MaintenanceBadge({ target, className = '' }) {
  const t = useT()
  const [under, setUnder] = useState(false)
  useEffect(() => {
    let alive = true
    getActive().then(info => {
      if (!alive) return
      setUnder(!!info && (info.all === true || (target != null && (info.targets || []).includes(target))))
    })
    return () => { alive = false }
  }, [target])
  if (!under) return null
  // shadcn Badge (proje varyantı `warning`, amber): eski .mw-badge'in büyük harf + sıkı aralık dili.
  return (
    <Badge variant="warning" data-slot="maintenance-badge" title={t('mw.underMaintenanceHint')}
      className={cn('px-1.5 text-[.68em] font-bold tracking-[.03em] uppercase', className)}>
      <Wrench size={11} aria-hidden="true" />{t('mw.underMaintenance')}
    </Badge>
  )
}
