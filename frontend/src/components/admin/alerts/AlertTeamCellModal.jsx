import { useEffect, useState } from 'react'
import { AlertCircle } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import { useServerPagination } from '../../../hooks/useServerPagination.js'
import { LoadingBlock } from '../../ui/Progress.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const levelClass = (lvl) => ({ WARNING: 'warning', HIGH: 'high', CRITICAL: 'critical' })[lvl] ?? 'unknown'
/** Seviye rozeti dolgusu (Alarm Geçmişi ile aynı `--severity-*` jetonları). */
const LEVEL_BG = {
  warning: 'bg-(--severity-warn) text-white', high: 'bg-(--severity-high) text-white',
  critical: 'bg-(--severity-critical) text-white', unknown: 'bg-border text-foreground',
}

/**
 * Takım kırılımı hücresi → o takımın ilgili alarmları, SAYFALI (2026-09-18, kullanıcı isteği).
 *
 * <p>Sunucu tarafı sayfalama: hücredeki sayı yüzlerce olabilir; liste {@code /admin/alerts} ucundan
 * takım + kova parametreleriyle sayfa sayfa çekilir. Kova → sorgu eşlemesi
 * {@code AlertTeamStatsService} ile AYNI kural: açık = tarihten bağımsız; kapandı = son pencere içinde
 * AÇILIP çözülmüş; 7/30 gün = pencerede açılanlar (açık+kapalı).
 *
 * @param {{team_id:number, team_name?:string}} cell.team
 * @param {'open'|'closed'|'last7'|'last30'} cell.bucket
 * @param {number} cell.windowDays  istatistik penceresi (sunucudan)
 */
export function bucketQuery(bucket, windowDays = 30, now = Date.now()) {
  const iso = (d) => new Date(now - d * 86400000).toISOString().slice(0, 19)
  if (bucket === 'open') return { resolved: false }
  if (bucket === 'closed') return { resolved: true, since: iso(windowDays) }
  if (bucket === 'last7') return { since: iso(7) }
  return { since: iso(windowDays) }
}

export default function AlertTeamCellModal({ cell, onClose, onOpenAlert }) {
  const t = useT()
  // Pencere içi sunucu listesi → modal ön ayarı (10 / [10,25,50] + compact; eski [10,25,50,100] kalktı).
  // Başka hücre açılınca sayfa 1 (değer karşılaştırmalı). API 0-tabanlı.
  const sp = useServerPagination({ listKey: 'alert-team-cell', preset: 'modal',
    resetDeps: [cell.team.team_id, cell.bucket, cell.windowDays], apiBase: 0 })
  const { apiPage, pageSize, setTotal } = sp
  const [state, setState] = useState({ loading: true, error: null, rows: [] })

  useEffect(() => {
    let alive = true
    setState((s) => ({ ...s, loading: true, error: null }))
    api.admin.getAlerts({ teamId: cell.team.team_id, page: apiPage, size: pageSize, ...bucketQuery(cell.bucket, cell.windowDays) })
      .then((r) => {
        if (!alive) return
        if (r?.success) { setState({ loading: false, error: null, rows: r.data || [] }); setTotal(Number(r.total) || 0) }
        // Hata yolunda setTotal ÇAĞRILMAZ (useServerPagination kuralı): `setTotal(0)` toplam sayfayı 1'e indirip
        // kullanıcıyı 3. sayfadan 1. sayfaya atıyor, geçici bir hata yüzünden yeni bir istek daha tetikliyordu.
        else setState({ loading: false, error: r?.error || t('mon.loadError'), rows: [] })
      })
      .catch((e) => { if (alive) setState({ loading: false, error: String(e?.message || e), rows: [] }) })
    return () => { alive = false }
  }, [cell, apiPage, pageSize, t, setTotal])

  const title = `${cell.team.team_name || t('alhts.unassigned')} · ${t('alhts.bucket.' + cell.bucket)} (${cell.count})`
  return (
    <ModalShell open onClose={onClose} title={title} icon={AlertCircle} size="lg" scrollBody
      footer={<Button type="button" variant="secondary" onClick={onClose}>{t('app.close')}</Button>}>
      {state.loading && state.rows.length === 0 ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : null}
      {state.error && <AlertBanner tone="danger" title={t('mon.loadError')} role="alert">{state.error}</AlertBanner>}
      {!state.error && !state.loading && state.rows.length === 0 && <div className="pt-2 pb-3 text-[0.86em] text-muted-foreground">{t('alhts.cellEmpty')}</div>}
      {state.rows.length > 0 && (
        <Table className="table-fixed text-[0.85em]">
          <TableHeader><TableRow>
            <TableHead className="w-[78px]">{t('alhts.cellLevel')}</TableHead><TableHead>{t('alhts.cellTarget')}</TableHead>
            <TableHead className="w-[118px]">{t('alhts.cellOpened')}</TableHead><TableHead className="w-[96px]">{t('alhts.cellStatus')}</TableHead>
          </TableRow></TableHeader>
          <TableBody>{state.rows.map((a) => (
            <TableRow key={a.id} data-cell-row="">
              <TableCell className="py-2 align-top">
                <Badge className={cn('rounded-full px-1.5 text-[0.68em] font-bold', LEVEL_BG[levelClass(a.alert_level)])}>{a.alert_level}</Badge>
              </TableCell>
              <TableCell className="overflow-hidden py-2 align-top">
                {onOpenAlert
                  ? <Button type="button" variant="link" size="xs" className="block h-auto max-w-full truncate p-0 text-left text-[1em]" title={a.domain} onClick={() => onOpenAlert(a)}>{a.domain}</Button>
                  : <span className="block truncate" title={a.domain}>{a.domain}</span>}
                <div className="truncate text-[0.85em] text-muted-foreground" title={a.message || ''}>{alertTypeLabel(t, a.alert_type)}{a.message ? ` · ${a.message}` : ''}</div>
              </TableCell>
              <TableCell className="py-2 align-top text-muted-foreground">{formatDate(a.created_at)}</TableCell>
              <TableCell className="py-2 align-top">{a.resolved
                ? <><ToneBadge tone="success">{t('alhts.colClosed')}</ToneBadge>{a.resolved_at && <div className="truncate text-[0.85em] text-muted-foreground">{formatDate(a.resolved_at)}</div>}</>
                : <ToneBadge tone="danger">{t('alhts.colOpen')}</ToneBadge>}</TableCell>
            </TableRow>
          ))}</TableBody>
        </Table>
      )}
      <PaginationBar {...sp.bar} />
    </ModalShell>
  )
}
