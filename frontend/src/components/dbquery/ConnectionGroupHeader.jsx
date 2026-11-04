import { useEffect, useState } from 'react'
import { DatabaseZap, Unplug } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { countText, dbTypeLabel } from './dbqueryPageModel.js'
import { NO_CONNECTION_KEY, healthIndex } from './listViewModel.js'

/** Bağlantı sağlığı → rozet tonu. Bilinmeyen / okunamayan asla yeşil değil. */
const HEALTH_TONE = { ok: 'success', failing: 'danger', untested: 'muted', unused: 'muted', unknown: 'muted' }
/** Grubun en kötü durumu → başlık ikonunun tonu. */
const WORST_INK = {
  fail: 'text-destructive', breach: 'text-amber-600 dark:text-amber-400', slow: 'text-amber-600 dark:text-amber-400',
  unknown: 'text-muted-foreground', ok: 'text-success', paused: 'text-muted-foreground',
}

/**
 * Bağlantıya göre gruplu listenin BAŞLIĞI (2026-11-05, dbq2 C3): veritabanı türü + bağlantı adı (sarar, kesilmez) +
 * durum özeti (izleme sayısı, hatalı, ihlalde, değerlendirilemeyen, duraklatılmış — yalnız sıfır olmayanlar) + okunabilirse
 * bağlantının kendi sağlığı (takımın bağlantı listesinden; okunamazsa "okunamadı", asla "sağlıklı" değil). Özet TÜM
 * listeden sayılır (grup sayfa sınırında bölünse de); bölünen grubun sonraki sayfadaki başlığı "devam" der.
 *
 * <p>Test kancaları: `data-slot="dbq-conn-group-head"` (+ `data-worst`, `data-continued`), sayaçlar `dbq-conn-group-count`
 * (+ `data-kind`), sağlık `dbq-conn-group-health` (+ `data-state` ok|failing|untested|unused|unknown|loading|unreadable).
 */
export default function ConnectionGroupHeader({ group, continued = false, health = null, headingId }) {
  const t = useT()
  const c = group.counts || {}
  const none = group.key === NO_CONNECTION_KEY
  const Icon = none ? Unplug : DatabaseZap
  const parts = [
    c.fail > 0 && { kind: 'fail', tone: 'danger', text: countText(t, 'dbq.focus.groupFail', c.fail) },
    (c.breach > 0) && { kind: 'breach', tone: 'warning', text: countText(t, 'dbq.focus.groupBreach', c.breach) },
    c.alarmOnly > 0 && { kind: 'alarm', tone: 'warning', text: countText(t, 'dbq.focus.groupAlarm', c.alarmOnly) },
    c.slow > 0 && { kind: 'slow', tone: 'warning', text: countText(t, 'dbq.focus.groupSlow', c.slow) },
    c.unknown > 0 && { kind: 'unknown', tone: 'muted', text: countText(t, 'dbq.focus.groupUnknown', c.unknown) },
    c.paused > 0 && { kind: 'paused', tone: 'muted', text: countText(t, 'dbq.focus.groupPaused', c.paused) },
  ].filter(Boolean)
  const allOk = c.active > 0 && c.ok === c.active && !c.alarm
  return (
    <header data-slot="dbq-conn-group-head" data-worst={group.worst} data-continued={continued ? 'true' : undefined}
      className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-muted/30 px-3 py-2.5 sm:flex-row sm:items-center sm:justify-between sm:gap-3">
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <Icon aria-hidden="true" className={cn('size-4 shrink-0', WORST_INK[group.worst] || 'text-muted-foreground')} />
        {!none && group.dbType && (
          <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10.5px] font-semibold">{dbTypeLabel(group.dbType)}</Badge>
        )}
        <h3 id={headingId} className="m-0 min-w-0 text-sm font-semibold [overflow-wrap:anywhere]">
          {none ? t('dbq.focus.groupNoConnection') : group.name}
          {continued && <span className="ml-1.5 font-normal text-muted-foreground">{t('dbq.focus.groupContinued')}</span>}
        </h3>
      </div>
      <ul className="m-0 flex min-w-0 list-none flex-wrap items-center gap-1.5 p-0" aria-label={t('dbq.focus.groupSummary')}>
        <li><ToneBadge tone="neutral" data-slot="dbq-conn-group-count" data-kind="total" className="h-auto rounded-md whitespace-normal">
          {countText(t, 'dbq.focus.groupTotal', c.total || 0)}
        </ToneBadge></li>
        {parts.map((p) => (
          <li key={p.kind}><ToneBadge tone={p.tone} data-slot="dbq-conn-group-count" data-kind={p.kind} className="h-auto rounded-md whitespace-normal">{p.text}</ToneBadge></li>
        ))}
        {allOk && (
          <li><ToneBadge tone="success" data-slot="dbq-conn-group-count" data-kind="ok" className="h-auto rounded-md whitespace-normal">{t('dbq.focus.groupAllOk')}</ToneBadge></li>
        )}
        {!none && health && <li><HealthBadge health={health} /></li>}
      </ul>
    </header>
  )
}

function HealthBadge({ health }) {
  const t = useT()
  if (health === 'loading') {
    return <span data-slot="dbq-conn-group-health" data-state="loading" className="text-xs text-muted-foreground">{t('dbq.focus.healthLoading')}</span>
  }
  if (health === 'unreadable') {
    return <span data-slot="dbq-conn-group-health" data-state="unreadable" className="text-xs text-muted-foreground">{t('dbq.focus.healthUnreadable')}</span>
  }
  const state = HEALTH_TONE[health] ? health : 'unknown'
  return (
    <ToneBadge tone={HEALTH_TONE[state]} data-slot="dbq-conn-group-health" data-state={state} className="h-auto rounded-md whitespace-normal">
      {t('dbq.focus.healthPrefix', t(`dbq.detail.health_${state}`))}
    </ToneBadge>
  )
}

/**
 * Gruplu görünümde bağlantı sağlıkları: görünen grupların TAKIMLARI için takımın bağlantı listesi (mevcut uç — takım
 * belgesi; paylaşım / 7/24 açmaz → okunamaz) bir kez okunur. Sonuç: profil kimliği → `ok|failing|untested|unused|unknown`,
 * okunuyorsa `loading`, takımın listesi okunamadıysa `unreadable`. Takım kümesi değişince eski yanıt yazılmaz;
 * kapalıyken (`enabled` false) istek yok.
 */
export function useGroupHealth(teamIds, enabled) {
  const [byTeam, setByTeam] = useState({})
  const key = enabled ? (teamIds || []).join(',') : ''
  useEffect(() => {
    let alive = true
    if (!key || typeof api.monitoring?.dbConnections !== 'function') { setByTeam({}); return undefined }
    const ids = key.split(',')
    setByTeam(Object.fromEntries(ids.map((id) => [id, { state: 'loading' }])))
    for (const id of ids) {
      Promise.resolve(api.monitoring.dbConnections(id))
        .then((res) => {
          if (!alive) return
          const ok = res?.success && Array.isArray(res.data)
          setByTeam((s) => ({ ...s, [id]: ok ? { state: 'ok', index: healthIndex(res.data) } : { state: 'unreadable' } }))
        })
        .catch(() => { if (alive) setByTeam((s) => ({ ...s, [id]: { state: 'unreadable' } })) })
    }
    // Takım kümesi değişince / kapatılınca eski yanıtlar yazılmaz
    return () => { alive = false }
  }, [key])
  return (group) => {
    if (!group || group.key === NO_CONNECTION_KEY || group.teamId == null) return null
    const entry = byTeam[String(group.teamId)]
    if (!entry) return null
    if (entry.state !== 'ok') return entry.state
    return entry.index.get(String(group.profileId)) || 'unreadable'
  }
}
