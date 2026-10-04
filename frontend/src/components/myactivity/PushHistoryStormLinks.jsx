import { CloudLightning, Layers } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { formatIncidentTime } from '../../utils/incidentMeta.js'
import { pushReasonLabel } from '../../utils/pushPrefs.js'
import { TONE_CLASS } from '../admin/ToneBadge.jsx'
import { statusLabel } from '../admin/alerts/alertHistoryModel.js'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/**
 * "Push bildirimlerim" ↔ fırtına push'u bağı (2026-10-04, kullanıcı isteği).
 *
 * - `StormPushNote`: takımın alarmı fırtınaya devredildi ({@code SKIPPED_STORM} karar satırı) → o alarmı kapsayan fırtına
 *   push'unu SİZİN aldığınız an ve durum ("Fırtına #12 push'u size iletildi · 14:05") ya da neden almadığınız ("Size fırtına
 *   push'u iletilmedi: …"), fırtına sürüyorsa "henüz gitmedi". Başka kişinin satırı sunucudan hiç gelmez.
 * - `StormAlarmsNote`: sizin fırtına push'unuz hangi alarmları kapsadı ("site-a, site-b +3"); görüş kapsamınız dışındaki
 *   alarmlar yalnız sayı ("2 başka takımın alarmı").
 * Test kancaları: `data-slot="ph-storm-push"` (+ `data-state`), `ph-storm-push-item`, `ph-storm-alarms`.
 */

const TRIGGER_KEY = { INITIAL: 'alh.sp.trigger.INITIAL', DAILY_REALERT: 'alh.sp.trigger.DAILY_REALERT', RESOLVE: 'alh.sp.trigger.RESOLVE' }

/** Kişi gözünden neden: sunucu kodu → okunur metin (kendi kodlarımız + push karar kodları). */
export function viewerReasonLabel(code, t) {
  if (!code) return ''
  if (code === 'NOT_RECIPIENT' || code === 'NO_STORM_PUSH') return t(`mypush.hist.sp.reason.${code}`)
  return pushReasonLabel(code, t)
}

export function StormPushNote({ sp }) {
  const t = useT()
  const locale = useDateLocale()
  if (!sp) return null
  const pushes = Array.isArray(sp.pushes) ? sp.pushes : []
  const firstSent = pushes.find((p) => p.status === 'SENT')
  const stormId = (firstSent || pushes[0])?.storm_id ?? (sp.awaiting_storm_ids || [])[0] ?? '?'
  const when = (iso) => (iso ? formatIncidentTime(iso, locale) : '—')
  let head
  switch (sp.state) {
    case 'received': head = t('mypush.hist.sp.received', stormId, when(firstSent?.at)); break
    case 'queued': head = t('mypush.hist.sp.queued', stormId); break
    case 'waiting': head = t('mypush.hist.sp.waiting', stormId); break
    default: head = t('mypush.hist.sp.notReceived', viewerReasonLabel(sp.reason, t) || '—')
  }
  return (
    <div data-slot="ph-storm-push" data-state={sp.state} className="flex min-w-0 flex-col gap-1 rounded-md border border-dashed px-2 py-1.5">
      <span className={cn('flex min-w-0 items-start gap-1.5 text-xs leading-snug font-semibold', sp.state === 'not_received' && 'text-destructive')}>
        <CloudLightning aria-hidden="true" className="mt-px size-3.5 shrink-0 text-violet-600 dark:text-violet-300" />
        <span className="min-w-0 [overflow-wrap:anywhere]">{head}</span>
      </span>
      {pushes.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-0.5 p-0 pl-5">
          {pushes.map((p) => (
            <li key={`${p.push_key}|${p.storm_id}`} data-slot="ph-storm-push-item" data-status={p.status || ''}
              className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs text-muted-foreground">
              <span>{t(TRIGGER_KEY[p.trigger] || TRIGGER_KEY.INITIAL)}</span>
              <span className="tabular-nums">{when(p.at)}</span>
              {p.status
                ? <Badge variant="outline" className={cn('rounded-[5px] px-1.5 text-[0.7rem] font-semibold', TONE_CLASS[p.status === 'SENT' ? 'success' : p.status === 'PENDING' ? 'info' : 'danger'])}>
                    {statusLabel(t, p.status)}
                  </Badge>
                : <span className="[overflow-wrap:anywhere]">{viewerReasonLabel(p.reason, t)}</span>}
              {p.inferred && <Badge variant="outline" className="border-dashed px-1.5 text-[0.7rem] font-normal">{t('alh.sp.inferred')}</Badge>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export function StormAlarmsNote({ sa }) {
  const t = useT()
  if (!sa) return null
  const alarms = Array.isArray(sa.alarms) ? sa.alarms : []
  const shown = alarms.slice(0, 2).map((a) => a.target).filter(Boolean)
  const more = Math.max(0, Number(sa.visible_count || 0) - shown.length)
  const list = shown.join(', ') + (more > 0 ? ` ${t('mypush.hist.sa.more', more)}` : '')
  return (
    <span data-slot="ph-storm-alarms" title={alarms.map((a) => a.target).join(', ') || undefined}
      className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs leading-snug text-muted-foreground">
      <Layers aria-hidden="true" className="size-3.5 shrink-0" />
      {Number(sa.total || 0) === 0
        ? <span>{t('mypush.hist.sa.none')}</span>
        : <>
            {shown.length > 0 && <span className="min-w-0 [overflow-wrap:anywhere]">{t('mypush.hist.sa.covered', list)}</span>}
            {Number(sa.hidden || 0) > 0 && <span data-slot="ph-storm-alarms-hidden">{t('mypush.hist.sa.hidden', sa.hidden)}</span>}
          </>}
      {sa.inferred && <Badge variant="outline" className="border-dashed px-1.5 text-[0.7rem] font-normal">{t('alh.sp.inferred')}</Badge>}
    </span>
  )
}
