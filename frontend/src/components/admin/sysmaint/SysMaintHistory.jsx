import { useCallback, useEffect, useState } from 'react'
import { BellOff, ChevronRight, History, LogIn, Mail, MailCheck, UserX } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useServerPagination } from '../../../hooks/useServerPagination.js'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { dateTimeText, windowText } from '../../../utils/systemMaintenance.js'
import PaginationBar from '../../ui/PaginationBar.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { SettingsSection } from '../SettingsControls.jsx'
import { phaseMeta } from './sysmaintModel.js'

/** Kap genişliği bu değerin altındaysa satırlar karta döner (jsdom: ölçüm yok → tablo). */
const TABLE_MIN_WIDTH = 760

/**
 * Bakım GEÇMİŞİ (2026-10-02, onaylı zenginleştirme c) — kim planladı/başlattı/bitirdi, plan/gerçek zamanlar, kapatılan
 * oturum, engellenen giriş, bildirim susturma seçimi ve susturulan bildirim sayısı. Sunucu sayfalı (`useServerPagination`
 * + PaginationBar — paginationBase kapısı); dar kapta (< 760 px) kartlar, genişte tablo; satıra tıklayınca ayrıntı penceresi.
 *
 * Test kancaları: `data-slot="sysmaint-history"`, `sysmaint-history-row` (`data-id`, `data-phase`), `sysmaint-detail`.
 */
function personOr(t, v) { return v || t('sysmaint.history.system') }

function Counters({ w }) {
  const t = useT()
  return (
    <span className="flex flex-wrap items-center gap-1.5 text-xs">
      <Badge variant="outline" className="gap-1" title={t('sysmaint.history.sessionsEnded')}>
        <UserX aria-hidden="true" className="size-3" />{w.sessions_ended ?? 0}
      </Badge>
      <Badge variant="outline" className="gap-1" title={t('sysmaint.history.loginsBlocked')}>
        <LogIn aria-hidden="true" className="size-3" />{w.logins_blocked ?? 0}
      </Badge>
      {w.mute_notifications && (
        <Badge variant="outline" className="gap-1" title={t('sysmaint.history.suppressed')}>
          <BellOff aria-hidden="true" className="size-3" />{w.notifications_suppressed ?? 0}
        </Badge>
      )}
      {(w.announce_mail_count ?? 0) > 0 && (
        <Badge variant="outline" className="gap-1" title={t('sysmaint.history.mailed')}>
          <Mail aria-hidden="true" className="size-3" />{w.announce_mail_count}
        </Badge>
      )}
      {(w.end_mail_count ?? 0) > 0 && (
        <Badge variant="outline" className="gap-1" title={t('sysmaint.history.endMailed')} data-slot="sysmaint-end-mail-count">
          <MailCheck aria-hidden="true" className="size-3" />{w.end_mail_count}
        </Badge>
      )}
    </span>
  )
}

/**
 * "Bakım tamamlandı" e-postası satırı (2026-10-02): alıcı yoksa "Gönderilmedi", seçenek kapalıysa "Kapalı", gönderildiyse
 * alıcı + durum, bitmemiş bakımda "bitince gönderilecek".
 */
function endMailText(t, d) {
  const chosen = d.email_all_users || (d.email_team_ids || []).length > 0
  if (!chosen) return t('sysmaint.history.noMail')
  if (d.email_on_end === false) return t('sysmaint.history.endMailOff')
  if (d.end_mail_at) return t('sysmaint.history.endMailDetail', d.end_mail_count ?? 0, d.end_mail_status || '—')
  if (d.phase === 'cancelled') return t('sysmaint.history.noMail')
  return t('sysmaint.history.endMailPending')
}

function PhaseBadge({ phase }) {
  const t = useT()
  const m = phaseMeta(phase)
  return <Badge variant={m.tone} data-status={phase}>{t(m.key)}</Badge>
}

function Row({ label, children }) {
  return (
    <div className="grid min-w-0 grid-cols-1 gap-0.5 border-b py-2 last:border-b-0 sm:grid-cols-[13rem_minmax(0,1fr)] sm:gap-3">
      <dt className="text-xs font-semibold text-muted-foreground">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

/** Ayrıntı penceresi — plan/gerçek zamanlar, kim, sayaçlar, e-posta ve telafi dökümü. */
export function SysMaintDetail({ id, onClose }) {
  const t = useT()
  const [d, setD] = useState(null)
  const [err, setErr] = useState(null)
  useEffect(() => {
    if (id == null) return
    let alive = true
    setD(null); setErr(null)
    api.systemMaintenance.detail(id).then((r) => {
      if (!alive) return
      if (r?.success) setD(r.data)
      else setErr(r?.error || t('sysmaint.err.generic'))
    })
    return () => { alive = false }
  }, [id, t])
  const outcomes = d?.suppression_outcomes || {}
  return (
    <ModalShell open={id != null} onClose={onClose} size="md" scrollBody icon={History} title={t('sysmaint.history.detailTitle', id ?? '')}>
      <div data-slot="sysmaint-detail" className="min-w-0">
        {!d && !err && <LoadingBlock label={t('app.loading')} />}
        {err && <StatusBlock tone="danger" title={err} />}
        {d && (
          <dl className="m-0 min-w-0">
            <Row label={t('sysmaint.history.status')}><PhaseBadge phase={d.phase} /></Row>
            <Row label={t('sysmaint.history.planned')}>{windowText({ start_at: d.planned_start_at, end_at: d.planned_end_at }) || '—'}</Row>
            <Row label={t('sysmaint.history.actualStart')}>{dateTimeText(d.actual_start_at) || '—'}</Row>
            <Row label={t('sysmaint.history.actualEnd')}>{dateTimeText(d.actual_end_at) || '—'}</Row>
            <Row label={t('sysmaint.history.plannedBy')}>{d.created_by || '—'} · {dateTimeText(d.created_at)}</Row>
            <Row label={t('sysmaint.history.startedBy')}>{d.immediate ? personOr(t, d.started_by) : t('sysmaint.history.onSchedule')}</Row>
            <Row label={t('sysmaint.history.endedBy')}>
              {d.cancelled_at ? t('sysmaint.history.cancelledBy', personOr(t, d.cancelled_by), dateTimeText(d.cancelled_at))
                : d.ended_by ? t('sysmaint.history.endedEarly', d.ended_by) : d.actual_end_at ? t('sysmaint.history.onSchedule') : '—'}
            </Row>
            {(d.extended_count ?? 0) > 0 && <Row label={t('sysmaint.history.extended')}>{t('sysmaint.history.extendedN', d.extended_count, d.extended_by || '—')}</Row>}
            <Row label={t('sysmaint.history.sessionsEnded')}>{d.sessions_ended ?? 0}</Row>
            <Row label={t('sysmaint.history.loginsBlocked')}>{d.logins_blocked ?? 0}</Row>
            <Row label={t('sysmaint.history.muteChoice')}>{d.mute_notifications ? t('sysmaint.history.muteYes') : t('sysmaint.history.muteNo')}</Row>
            {d.mute_notifications && (
              <Row label={t('sysmaint.history.suppressed')}>
                {t('sysmaint.history.suppressedDetail', d.notifications_suppressed ?? 0, d.suppressed_alerts ?? 0, d.caught_up_count ?? 0,
                  outcomes.RESOLVED ?? 0)}
              </Row>
            )}
            <Row label={t('sysmaint.history.mail')}>
              {/* "Hemen bakıma al"da duyuru e-postası yok (alıcılar yalnız bitiş e-postası için — 2026-10-02) */}
              {(d.email_all_users || (d.email_team_ids || []).length) && !d.immediate
                ? t('sysmaint.history.mailDetail', d.announce_mail_count ?? 0, d.correction_mail_count ?? 0, d.announce_mail_status || '—')
                : t('sysmaint.history.noMail')}
            </Row>
            <Row label={t('sysmaint.history.endMail')}>
              <span data-slot="sysmaint-detail-end-mail">{endMailText(t, d)}</span>
            </Row>
          </dl>
        )}
      </div>
    </ModalShell>
  )
}

export default function SysMaintHistory({ refreshKey = 0 }) {
  const t = useT()
  const sp = useServerPagination({ listKey: 'sysmaint-history', preset: 'panel', apiBase: 1 })
  const [rows, setRows] = useState(null)
  const [error, setError] = useState(null)
  const [openId, setOpenId] = useState(null)
  const [measureRef, listWidth] = useElementWidth()
  const tableMode = listWidth === 0 || listWidth >= TABLE_MIN_WIDTH
  const { bind } = sp

  const load = useCallback(async () => {
    const r = await api.systemMaintenance.history(sp.apiPage, sp.pageSize)
    if (r?.success) {
      bind(r)
      setRows(Array.isArray(r.data?.items) ? r.data.items : [])
      setError(null)
    } else {
      setError(r?.error || t('sysmaint.err.generic'))
    }
  }, [sp.apiPage, sp.pageSize, bind, t])
  useEffect(() => { load() }, [load, refreshKey])

  return (
    <SettingsSection title={<span className="inline-flex items-center gap-2"><History aria-hidden="true" className="size-4" />{t('sysmaint.history.title')}</span>}
      description={t('sysmaint.history.desc')} contentClassName="flex min-w-0 flex-col gap-3">
      <div data-slot="sysmaint-history" ref={measureRef} className="min-w-0">
        {rows == null && !error && <LoadingBlock label={t('app.loading')} />}
        {error && <StatusBlock tone="danger" title={t('sysmaint.err.generic')} description={error} />}
        {rows && rows.length === 0 && <StatusBlock tone="neutral" icon={History} title={t('sysmaint.history.empty')} className="rounded-lg border py-8" />}
        {rows && rows.length > 0 && (tableMode ? (
          <Table className="table-fixed">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[30%]">{t('sysmaint.history.window')}</TableHead>
                <TableHead className="w-[14%]">{t('sysmaint.history.status')}</TableHead>
                <TableHead className="w-[18%]">{t('sysmaint.history.who')}</TableHead>
                <TableHead className="w-[28%]">{t('sysmaint.history.effects')}</TableHead>
                <TableHead className="w-[10%] text-right"><span className="sr-only">{t('sysmaint.history.open')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((w) => (
                <TableRow key={w.id} data-slot="sysmaint-history-row" data-id={w.id} data-phase={w.phase}>
                  <TableCell className="truncate" title={windowText(w)}>{windowText(w)}</TableCell>
                  <TableCell><PhaseBadge phase={w.phase} /></TableCell>
                  <TableCell className="truncate" title={w.created_by || ''}>{w.created_by || '—'}</TableCell>
                  <TableCell><Counters w={w} /></TableCell>
                  <TableCell className="text-right">
                    <Button type="button" variant="ghost" size="sm" onClick={() => setOpenId(w.id)}
                      aria-label={t('sysmaint.history.openRow', windowText(w))} className="min-h-9">
                      <ChevronRight aria-hidden="true" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {rows.map((w) => (
              <li key={w.id} data-slot="sysmaint-history-row" data-id={w.id} data-phase={w.phase}
                className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card p-3">
                <div className="flex min-w-0 items-start justify-between gap-2">
                  <span className="min-w-0 text-sm font-semibold [overflow-wrap:anywhere]">{windowText(w)}</span>
                  <PhaseBadge phase={w.phase} />
                </div>
                <span className="text-xs text-muted-foreground">{t('sysmaint.history.plannedByShort', w.created_by || '—')}</span>
                <Counters w={w} />
                <Button type="button" variant="outline" size="sm" onClick={() => setOpenId(w.id)} className="min-h-10 self-start">
                  {t('sysmaint.history.open')}<ChevronRight aria-hidden="true" />
                </Button>
              </li>
            ))}
          </ul>
        ))}
        {rows && rows.length > 0 && <PaginationBar {...sp.bar} />}
      </div>
      <SysMaintDetail id={openId} onClose={() => setOpenId(null)} />
    </SettingsSection>
  )
}
