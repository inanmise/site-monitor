import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Building2, Users, ChevronRight, ChevronLeft, Search, TriangleAlert, CircleAlert, Inbox } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import ExecOrgSettings from './ExecOrgSettings.jsx'
import ExecTeamSettings from './ExecTeamSettings.jsx'
import { HistoryBadge } from './ExecHistory.jsx'
import { SEL_ORG, filterTeams, monthLabel } from './executiveModel.js'

/** Takım sayısı bu eşiği geçince listede arama kutusu görünür. */
const SEARCH_THRESHOLD = 8

/** Liste satırı — seçili olan `aria-current`; telefonda dokunma hedefi ≥ 48 px. */
function ListRow({ active, onClick, icon: Icon, title, meta, badge, warn = false, slot, dataKey }) {
  return (
    <Button type="button" variant="ghost" onClick={onClick} aria-current={active ? 'true' : undefined}
      data-slot={slot} data-key={dataKey}
      className={cn('h-auto min-h-12 w-full justify-start gap-3 rounded-lg px-3 py-2.5 text-left font-normal whitespace-normal',
        active && 'bg-primary/10 hover:bg-primary/15')}>
      <span aria-hidden="true" className={cn('inline-flex size-9 shrink-0 items-center justify-center rounded-lg',
        active ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground')}>
        <Icon className="size-4" />
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="flex min-w-0 items-center gap-1.5 text-sm font-medium">
          <span className="min-w-0 truncate">{title}</span>
          {warn && <TriangleAlert aria-hidden="true" className="size-3.5 shrink-0 text-warning" />}
        </span>
        {meta && <span className="min-w-0 truncate text-xs text-muted-foreground">{meta}</span>}
      </span>
      {badge}
      <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground lg:hidden" />
    </Button>
  )
}

/** Açık / kapalı rozeti. */
function OnOff({ on }) {
  const t = useT()
  return (
    <Badge variant={on ? 'outline' : 'secondary'} className={cn('shrink-0 gap-1', on && 'border-success/40 text-success')}>
      <span aria-hidden="true" className={cn('size-1.5 rounded-full', on ? 'bg-success' : 'bg-muted-foreground/60')} />
      {on ? t('exec.team.on') : t('exec.team.off')}
    </Badge>
  )
}

/**
 * ALICILAR VE GÖNDERİM görünümü (2026-10-10, kullanıcı isteği: "takım bazlı yönetici ayarlaması") — ana-ayrıntı düzeni:
 * solda kurum geneli rapor (global yönetici) + yapılandırılabilen takımlar (global yönetici hepsi, takım müdürü yönettikleri;
 * açık/kapalı, alıcı sayısı, son ayın gönderim durumu, uyarı işareti), sağda seçilen kaydın ayarları. Telefonda/tablette
 * (< 1024 px) liste ve ayrıntı tek sütun: satıra dokununca ayrıntı açılır, "Listeye dön" geri getirir. Kaydedilmemiş
 * değişiklikle başka kayda geçmek onay ister. Seçim URL'de (`ex_sel`).
 */
export default function ExecDeliveryView({ month, canConfigureOrg = false, selected = null, onSelect }) {
  const t = useT()
  const { lang } = useLanguage()
  const { showConfirm } = useDialog()
  const searchId = useId()
  const listTitleId = useId()
  const [teams, setTeams] = useState({ loading: true, error: null, rows: [], reportMonth: null, forbidden: false })
  const [q, setQ] = useState('')
  const [pane, setPane] = useState(selected ? 'detail' : 'list')
  const dirtyRef = useRef(false)
  const detailRef = useRef(null)

  const loadTeams = useCallback(async () => {
    try {
      const res = await api.executiveSummary.teams()
      if (res?.success && res.data) {
        setTeams({ loading: false, error: null, rows: res.data.teams || [], reportMonth: res.data.report_month || null, forbidden: false })
      } else {
        setTeams({ loading: false, error: res?.status === 403 ? null : (res?.error || t('exec.team.listError')), rows: [],
          reportMonth: null, forbidden: res?.status === 403 })
      }
    } catch (e) {
      setTeams({ loading: false, error: e?.message || t('exec.team.listError'), rows: [], reportMonth: null, forbidden: false })
    }
  }, [t])
  useEffect(() => { loadTeams() }, [loadTeams])

  const rows = teams.rows
  const shown = useMemo(() => filterTeams(rows, q), [rows, q])
  const validSel = selected === SEL_ORG ? (canConfigureOrg ? SEL_ORG : null)
    : (selected && rows.some((r) => String(r.team_id) === String(selected)) ? String(selected) : null)
  const current = validSel || (canConfigureOrg ? SEL_ORG : (rows[0] ? String(rows[0].team_id) : null))
  const enabledCount = rows.filter((r) => r.enabled).length
  const onDirty = useCallback((d) => { dirtyRef.current = d }, [])

  async function choose(key) {
    if (key === current) { setPane('detail'); return }
    if (dirtyRef.current) {
      const ok = await showConfirm({
        title: t('exec.delivery.leaveTitle'), message: t('exec.delivery.leaveMessage'), variant: 'warning',
        confirmText: t('exec.delivery.leaveConfirm'), cancelText: t('exec.cfg.cancel'),
      })
      if (!ok) return
    }
    dirtyRef.current = false
    onSelect?.(key)
    setPane('detail')
    const reveal = () => detailRef.current?.scrollIntoView?.({ block: 'start', behavior: 'smooth' })
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(reveal); else reveal()
  }

  if (teams.loading) return <LoadingBlock label={t('exec.team.listLoading')} />
  if (!canConfigureOrg && rows.length === 0) {
    return (
      <StatusBlock tone="neutral" icon={teams.error ? CircleAlert : Inbox} title={teams.error ? t('exec.team.listError') : t('exec.team.noneTitle')}
        description={teams.error || t('exec.team.noneDesc')} className="rounded-xl border py-10" />
    )
  }
  return (
    <div data-slot="ex-delivery" className="grid min-w-0 gap-4 lg:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)] lg:items-start">
      <aside aria-labelledby={listTitleId} data-slot="ex-delivery-list"
        className={cn('min-w-0 lg:sticky lg:top-4', pane === 'detail' && 'max-lg:hidden')}>
        <Card className="gap-3 py-3 shadow-xs">
          <CardContent className="flex min-w-0 flex-col gap-3 px-2 sm:px-3">
            <div className="flex min-w-0 flex-col gap-0.5 px-1">
              <h3 id={listTitleId} className="m-0 text-sm font-semibold">{t('exec.delivery.listTitle')}</h3>
              <p className="m-0 text-xs text-muted-foreground">
                {t('exec.delivery.listDesc', enabledCount, rows.length, monthLabel(teams.reportMonth, lang))}
              </p>
            </div>
            {canConfigureOrg && (
              <ListRow active={current === SEL_ORG} onClick={() => choose(SEL_ORG)} icon={Building2} slot="ex-delivery-item"
                dataKey={SEL_ORG} title={t('exec.org.title')} meta={t('exec.delivery.orgMeta')} />
            )}
            {rows.length > 0 && (
              <div className="flex min-w-0 flex-col gap-2">
                <p className="m-0 px-1 pt-1 text-xs font-semibold text-muted-foreground">{t('exec.scope.teams')}</p>
                {rows.length > SEARCH_THRESHOLD && (
                  <InputGroup className="min-w-0">
                    <InputGroupInput id={searchId} value={q} onChange={(e) => setQ(e.target.value)}
                      aria-label={t('exec.delivery.search')} placeholder={t('exec.delivery.search')} />
                    <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                  </InputGroup>
                )}
                <ul className="m-0 flex list-none flex-col gap-0.5 p-0" aria-label={t('exec.scope.teams')}>
                  {shown.length === 0 && <li className="px-2 py-2 text-sm text-muted-foreground">{t('exec.team.noMatch')}</li>}
                  {shown.map((r) => (
                    <li key={r.team_id}>
                      <ListRow active={current === String(r.team_id)} onClick={() => choose(String(r.team_id))} icon={Users}
                        slot="ex-delivery-item" dataKey={String(r.team_id)} title={r.team_name}
                        warn={(r.notes || []).length > 0 || (r.enabled && r.recipient_count === 0)}
                        meta={(
                          <span className="inline-flex min-w-0 items-center gap-1.5">
                            <span className="tabular-nums">{t('exec.delivery.recipients', r.recipient_count ?? 0)}</span>
                            {r.last_status && <HistoryBadge status={r.last_status} className="px-1.5 py-0 text-[10px]" />}
                          </span>
                        )}
                        badge={<OnOff on={r.enabled} />} />
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </CardContent>
        </Card>
      </aside>

      <section ref={detailRef} aria-label={t('exec.delivery.detail')} data-slot="ex-delivery-detail"
        className={cn('min-w-0 scroll-mt-4', pane === 'list' && 'max-lg:hidden')}>
        <Button type="button" variant="ghost" size="sm" onClick={() => setPane('list')}
          className="mb-3 h-10 gap-1 px-2 lg:hidden" data-slot="ex-delivery-back">
          <ChevronLeft aria-hidden="true" />{t('exec.delivery.back')}
        </Button>
        {current === SEL_ORG && canConfigureOrg && (
          <ExecOrgSettings key="org" month={month} onDirtyChange={onDirty} />
        )}
        {current && current !== SEL_ORG && (
          <ExecTeamSettings key={current} teamId={current} month={month} onDirtyChange={onDirty} onSaved={loadTeams} />
        )}
      </section>
    </div>
  )
}
