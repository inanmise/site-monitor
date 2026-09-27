import { useEffect, useMemo, useState } from 'react'
import { ArrowRight } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import ModalShell from './ui/ModalShell.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import { usePagination } from '../hooks/usePagination.js'
import { MonitorRowBody, NotificationRowBody, HealthRowBody, QuietRowBody, monitorRowKey } from './todayMonitorRows.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import StatusBlock from './ui/StatusBlock.jsx'

/**
 * "Sizin için — bugün" → "Tümünü gör" pop-up'ı (2026-09-18, kullanıcı isteği): kart yalnız ilk 5 satırı
 * gösterir; bu modal {@code /api/me/today?full=true} ile listenin TAMAMINI çeker ve 10'luk sayfalarla sunar.
 * Alt köşedeki "Sayfaya git" eski davranışı (ilgili sayfaya süzülmüş geçiş) korur.
 *
 * @param {'certs'|'alerts'|'weekly'|'flapping'|'slow'|'stale'|'domains'|'notifications'|'health'|'quiet'} section
 * @param {(item:object)=>void} onOpen  satıra tıklama (kartla aynı davranış)
 * @param {()=>void} [onGo]           "Sayfaya git"
 */
const MONITOR_SECTIONS = new Set(['flapping', 'slow', 'stale', 'domains'])

export default function TodayListModal({ section, title, icon, onClose, onOpen, onGo }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [q, setQ] = useState('')

  useEffect(() => {
    let alive = true
    api.me.today({ full: true })
      .then((r) => { if (!alive) return; if (r?.success && r.data) setData(r.data); else setError(r?.error || t('mon.loadError')) })
      .catch((e) => { if (alive) setError(String(e?.message || e)) })
    return () => { alive = false }
  }, [t])

  const all = useMemo(() => (data?.[section]?.items) || [], [data, section])
  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    if (!s) return all
    return all.filter((x) => [x.domain, x.type, x.team_name, x.level, x.name, x.target, x.registrar, x.channel, x.error, x.monitor_name, x.reason].some((v) => (v || '').toLowerCase().includes(s)))
  }, [all, q])
  // Pencere içi liste → modal ön ayarı (10 / [10,25,50] + compact çubuk; paginationPresets.js)
  const pager = usePagination(rows, { listKey: 'today-' + section, preset: 'modal', resetDeps: [q, all] })
  const count = data?.[section]?.count ?? data?.[section]?.missing ?? all.length

  const row = (x) => {
    if (section === 'alerts') return (
      <li key={x.id} className="today-modal-row">
        <span className={`today-level today-level--${(x.level || '').toLowerCase()}`}>{x.level}</span>
        <Button type="button" variant="link" className="h-auto min-w-0 max-w-full justify-start p-0 font-semibold" onClick={() => onOpen?.(x)}><span className="truncate">{x.domain || x.type}</span></Button>
        <span className="today-muted">{x.type}{x.acknowledged ? ` · ${t('today.acked')}` : ''}</span>
      </li>)
    if (section === 'notifications') return (
      <li key={monitorRowKey(x)} className="today-modal-row"><NotificationRowBody item={x} t={t} onOpen={onOpen} /></li>)
    if (section === 'health') return (
      <li key={monitorRowKey(x)} className="today-modal-row"><HealthRowBody item={x} t={t} onOpen={onOpen} /></li>)
    if (section === 'quiet') return (
      <li key={monitorRowKey(x)} className="today-modal-row"><QuietRowBody item={x} t={t} onOpen={onOpen} /></li>)
    if (MONITOR_SECTIONS.has(section)) return (
      <li key={monitorRowKey(x)} className="today-modal-row"><MonitorRowBody section={section} item={x} t={t} onOpen={onOpen} /></li>)
    if (section === 'weekly') return (
      <li key={x.team_id} className="today-modal-row">
        {x.team_name ? <TeamBadge teamId={x.team_id} teamName={x.team_name} /> : <span>#{x.team_id}</span>}
        <span className={`today-status today-status--${(x.status || '').toLowerCase()}`}>{t(`today.wr.${x.status}`)}</span>
      </li>)
    return (
      <li key={x.domain} className="today-modal-row">
        <Button type="button" variant="link" className="h-auto min-w-0 max-w-full justify-start p-0 font-semibold" onClick={() => onOpen?.(x)}><span className="truncate">{x.domain}</span></Button>
        <span className={`today-days${x.days < 0 ? ' is-bad' : x.days <= 7 ? ' is-warn' : ''}`}>{x.days < 0 ? t('today.daysPast', -x.days) : t('today.daysLeft', x.days)}</span>
        {x.team_name && <TeamBadge teamId={x.team_id} teamName={x.team_name} />}
      </li>)
  }

  return (
    <ModalShell open onClose={onClose} title={`${title} (${count})`} icon={icon} size="md" scrollBody
      footer={<>
        {onGo && <Button type="button" onClick={() => { onClose(); onGo() }}>{t('today.goPage')} <ArrowRight size={12} aria-hidden="true" /></Button>}
        <Button type="button" variant="secondary" onClick={onClose}>{t('app.close')}</Button>
      </>}>
      {!data && !error && <LoadingBlock label={t('tbl.loading')} fullWidth />}
      {error && <StatusBlock tone="danger" title={error} role="alert" className="py-6 md:py-6" />}
      {data && all.length > 5 && (
        <Input type="text" className="mb-2.5" placeholder={t('today.search')} aria-label={t('today.search')} value={q} onChange={(e) => setQ(e.target.value)} />
      )}
      {data && <ul className="today-list today-modal-list">{pager.pageItems.map(row)}</ul>}
      {data && rows.length === 0 && <StatusBlock tone="neutral" title={t('empty.hintFilter')} className="py-6 md:py-6" />}
      {data && rows.length > 0 && <PaginationBar {...pager} />}
    </ModalShell>
  )
}
