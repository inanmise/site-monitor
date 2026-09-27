import { useMemo, useState } from 'react'
import { LogIn, XCircle, ShieldAlert, Download, Users, Globe, Clock, MapPin } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { formatDateSec } from '../../../api/client'
import ModalShell from '../../ui/ModalShell.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { usePagination } from '../../../hooks/usePagination.js'
import { toCsv, downloadCsv, stampedName } from '../../../utils/csvExport.js'
import { splitFlags, FLAG_KEYS } from './uactModel.js'
import { ToolbarSearch, FilterField } from '../ListToolbar.jsx'
import { TH, TD, MUTED_SM, DataTable, LinkButton, StatChip, AuthSourceBadge, FlagBadge } from '../HealthUi.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Giriş / başarısız giriş / anomali KPI kartlarının drill-down'ı (2026-09-20, kullanıcı bildirimi: "login kartına
 * tıklayınca açılan sayfa çok basic"). Özet şeridi (toplam, tekil kullanıcı, tekil IP, takım, mesai dışı, başarısız),
 * süzgeçler (metin: kullanıcı / IP / kuruluş; takım; sonuç; bayrak), sayfalı tablo (zaman, kullanıcı + rol, takım,
 * kaynak, IP + konum + kuruluş, tarayıcı, sonuç + sebep, bayraklar), CSV. Satırdaki kullanıcı → oturum detayı (onUser).
 * Veri: details.<kind> (backend eventRows — aktör adı/rol/takım/kaynak, olay türü, kuruluş, tarayıcı ile zenginleştirilmiş).
 * Çizim shadcn: Card süzgeç çubuğu, Table, Badge / Button çipleri (ortak parçalar ../HealthUi.jsx).
 */
export function shortUa(ua) {
  if (!ua) return '—'
  if (/Edg\//.test(ua)) return 'Edge'
  if (/OPR\/|Opera/.test(ua)) return 'Opera'
  if (/Chrome\//.test(ua)) return 'Chrome'
  if (/Firefox\//.test(ua)) return 'Firefox'
  if (/Safari\//.test(ua)) return 'Safari'
  if (/curl\//i.test(ua)) return 'curl'
  return ua.split(' ')[0] || '—'
}
export function osOf(ua) {
  if (!ua) return ''
  if (/Windows/.test(ua)) return 'Windows'
  if (/Mac OS|Macintosh/.test(ua)) return 'macOS'
  if (/Android/.test(ua)) return 'Android'
  if (/iPhone|iPad/.test(ua)) return 'iOS'
  if (/Linux/.test(ua)) return 'Linux'
  return ''
}
const loc = (r) => [r?.city, r?.country].filter(Boolean).join(', ')

export function eventMatches(r, f) {
  if (f.team && String(r.team_id ?? '') !== String(f.team)) return false
  if (f.outcome === 'SUCCESS' && r.outcome !== 'SUCCESS') return false
  if (f.outcome === 'FAILURE' && r.outcome === 'SUCCESS') return false
  if (f.flag && !splitFlags(r.flags).includes(f.flag)) return false
  if (f.q) {
    const q = f.q.toLowerCase()
    if (![r.actor, r.display_name, r.ip, r.org, r.city, r.country, r.reason, r.team_name].some((v) => v && String(v).toLowerCase().includes(q))) return false
  }
  return true
}

export function eventsCsv(rows, t) {
  const head = [t('uact.colTime'), t('uact.colUser'), t('uact.detailDisplayName'), t('uact.colRole'), t('uact.colTeam'), t('uact.colAuthSource'), t('uact.colIp'), t('uact.colLocation'), t('uact.detailOrg'), t('uact.colBrowser'), t('uact.colOutcome'), t('uact.colReason'), t('uact.colFlags')]
  return toCsv(head, rows.map((r) => [r.time, r.actor, r.display_name, r.system_role, r.team_name, r.auth_source, r.ip, loc(r), r.org, r.user_agent ? `${shortUa(r.user_agent)} ${osOf(r.user_agent)}`.trim() : '', r.outcome, r.reason, r.flags]))
}

export default function EventListModal({ kind, title, rows: rowsIn, byName = new Map(), winLabel, onClose, onUser }) {
  const t = useT()
  const isAnom = kind === 'anomalies', isFailed = kind === 'failed'
  const [f, setF] = useState({ q: '', team: '', outcome: '', flag: '' })
  const patch = (p) => setF((x) => ({ ...x, ...p }))
  // eski (dar) satırlar için login_status'tan ad/rol/takım tamamlanır
  const all = useMemo(() => (rowsIn || []).map((r) => { const u = byName.get(String(r.actor || '').toLowerCase()) || {}; return { ...u, ...Object.fromEntries(Object.entries(r).filter(([, v]) => v != null)) } }), [rowsIn, byName])
  const rows = useMemo(() => all.filter((r) => eventMatches(r, f)), [all, f])
  // Pencere içi liste → modal ön ayarı (10 / [10,25,50]; compact çubuk ön ayardan gelir)
  const pager = usePagination(rows, { listKey: `uact-events-${kind}`, preset: 'modal', resetDeps: [f] })
  const stats = useMemo(() => ({
    total: all.length,
    users: new Set(all.map((r) => String(r.actor || '').toLowerCase()).filter(Boolean)).size,
    ips: new Set(all.map((r) => r.ip).filter(Boolean)).size,
    teams: new Set(all.map((r) => r.team_id).filter((x) => x != null)).size,
    offHours: all.filter((r) => splitFlags(r.flags).includes('OFF_HOURS')).length,
    failed: all.filter((r) => r.outcome && r.outcome !== 'SUCCESS').length,
    ldap: all.filter((r) => r.auth_source === 'LDAP').length,
  }), [all])
  const opt = (arr) => [{ value: '', label: t('uact.filterAny') }, ...arr]
  const teamOptions = useMemo(() => { const m = new Map(); for (const r of all) if (r.team_id != null && r.team_name) m.set(String(r.team_id), r.team_name); return opt([...m.entries()].sort((a, b) => a[1].localeCompare(b[1])).map(([value, label]) => ({ value, label }))) }, [all]) // eslint-disable-line react-hooks/exhaustive-deps
  const flagOptions = opt(FLAG_KEYS.map((k) => ({ value: k, label: t(`uact.anom_${k}`) })))
  const outcomeOptions = opt([{ value: 'SUCCESS', label: t('uact.success') }, { value: 'FAILURE', label: t('uact.failed') }])
  const Icon = isAnom ? ShieldAlert : isFailed ? XCircle : LogIn
  const chip = (key, val, label, on, onClick, tone) => <StatChip key={key} val={val} label={label} on={on} onClick={onClick || undefined} tone={tone} />

  return (
    <ModalShell open onClose={onClose} title={`${title} · ${rows.length}${rows.length !== all.length ? ' / ' + all.length : ''}`} icon={Icon} size="xl" scrollBody
      footer={<>
        <Button type="button" variant="secondary" onClick={() => downloadCsv(stampedName(kind), eventsCsv(rows, t))}><Download size={14} /> {t('uact.exportEvents')}</Button>
        <Button type="button" onClick={onClose}>{t('app.dismiss')}</Button>
      </>}>
      <p className={cn(MUTED_SM, 'mb-2')}>{winLabel}{all.length >= 500 ? ` · ${t('uact.eventsCapped', 500)}` : ''}</p>
      <div className="mb-2.5 flex flex-wrap gap-1.5" data-testid="evl-stats">
        {chip('all', stats.total, t('uact.dirAll'), !f.outcome && !f.flag, () => patch({ outcome: '', flag: '' }))}
        {chip('users', stats.users, t('uact.uniqueUsers'), false, null)}
        {chip('ips', stats.ips, t('uact.uniqueIps'), false, null)}
        {chip('teams', stats.teams, t('uact.colTeam'), false, null)}
        {chip('ldap', stats.ldap, 'LDAP', false, null)}
        {!isAnom && chip('failed', stats.failed, t('uact.failed'), f.outcome === 'FAILURE', () => patch({ outcome: f.outcome === 'FAILURE' ? '' : 'FAILURE' }), stats.failed > 0 ? 'danger' : undefined)}
        {chip('off', stats.offHours, t('uact.anom_OFF_HOURS'), f.flag === 'OFF_HOURS', () => patch({ flag: f.flag === 'OFF_HOURS' ? '' : 'OFF_HOURS' }), stats.offHours > 0 ? 'warn' : undefined)}
      </div>
      <Card data-testid="evl-filters" className="mb-2.5 flex-row flex-wrap items-end gap-2.5 px-3 py-2.5 shadow-none">
        <FilterField label={t('uact.dirSearch')} className="flex-[1_1_200px]">
          <ToolbarSearch value={f.q} onChange={(v) => patch({ q: v })} placeholder={t('uact.eventsSearchPh')} ariaLabel={t('uact.dirSearch')} clearLabel={t('app.clear')} className="max-w-none flex-auto" />
        </FilterField>
        <FilterField label={t('uact.colTeam')} className="w-full sm:w-auto sm:min-w-[140px]"><SearchableSelect ariaLabel={t('uact.colTeam')} value={f.team} onChange={(v) => patch({ team: v })} options={teamOptions} searchThreshold={6} /></FilterField>
        {!isAnom && <FilterField label={t('uact.colOutcome')} className="w-full sm:w-auto sm:min-w-[140px]"><SearchableSelect ariaLabel={t('uact.colOutcome')} value={f.outcome} onChange={(v) => patch({ outcome: v })} options={outcomeOptions} searchThreshold={99} /></FilterField>}
        <FilterField label={t('uact.colFlags')} className="w-full sm:w-auto sm:min-w-[140px]"><SearchableSelect ariaLabel={t('uact.colFlags')} value={f.flag} onChange={(v) => patch({ flag: v })} options={flagOptions} searchThreshold={99} /></FilterField>
        {(f.q || f.team || f.outcome || f.flag) && <Button type="button" variant="secondary" size="sm" onClick={() => setF({ q: '', team: '', outcome: '', flag: '' })}>{t('uact.filterClear')}</Button>}
      </Card>
      {rows.length === 0 ? <StatusBlock tone="neutral" icon={Users} title={t('uact.noLogins')} /> : (
        <DataTable testId="evl-table">
          <TableHeader><TableRow>
            <TableHead className={TH}>{t('uact.colTime')}</TableHead>
            <TableHead className={TH}>{t('uact.colUser')}</TableHead>
            <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('uact.colTeam')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('uact.colAuthSource')}</TableHead>
            <TableHead className={TH}>{t('uact.colIp')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('uact.colBrowser')}</TableHead>
            <TableHead className={TH}>{t('uact.colOutcome')}</TableHead>
            <TableHead className={TH}>{t('uact.colFlags')}</TableHead>
          </TableRow></TableHeader>
          <TableBody>{pager.pageItems.map((r, i) => {
            const ok = r.outcome === 'SUCCESS'
            const bad = !ok && !!r.outcome
            const cell = cn(TD, 'align-top')
            const sub = 'mt-0.5 block text-[11px] text-muted-foreground'
            return (
              <TableRow key={r.id ?? `${r.time}-${i}`} data-outcome={bad ? 'bad' : undefined} className={cn(bad && 'bg-destructive/5')}>
                <TableCell data-label={t('uact.colTime')} className={cn(cell, 'font-mono text-xs')}><Clock size={11} aria-hidden="true" className="inline" /> {r.time ? formatDateSec(r.time) : '—'}</TableCell>
                <TableCell data-label={t('uact.colUser')} className={cell}>{r.actor ? <LinkButton className="ml-0 no-underline" onClick={() => onUser?.({ username: r.actor, ...r })}><UserBadge username={r.actor} userId={r.user_id} displayName={r.display_name} inline nameOnly size="sm" /></LinkButton> : '—'}{/* yalnız ad soyad (2026-09-21 kullanıcı bildirimi) — rol/olay türü hücreyi kalabalıklaştırıyordu */}</TableCell>
                <TableCell data-label={t('uact.colTeam')} className={cn(cell, 'hidden md:table-cell')}>{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : <span className="text-muted-foreground">—</span>}</TableCell>
                <TableCell data-label={t('uact.colAuthSource')} className={cn(cell, 'hidden lg:table-cell')}>{r.auth_source ? <AuthSourceBadge source={r.auth_source} localLabel={t('usr.authLocal')} /> : '—'}</TableCell>
                <TableCell data-label={t('uact.colIp')} className={cn(cell, 'text-xs')}><span className="font-mono">{r.ip || '—'}</span>{(loc(r) || r.org) && <span className={sub}><MapPin size={10} aria-hidden="true" className="inline" /> {[loc(r), r.org].filter(Boolean).join(' · ')}</span>}</TableCell>
                <TableCell data-label={t('uact.colBrowser')} className={cn(cell, 'hidden text-xs lg:table-cell')} title={r.user_agent || ''}>{r.user_agent ? <><Globe size={11} aria-hidden="true" className="inline" /> {shortUa(r.user_agent)}{osOf(r.user_agent) ? <span className="text-muted-foreground"> · {osOf(r.user_agent)}</span> : null}</> : '—'}</TableCell>
                <TableCell data-label={t('uact.colOutcome')} className={cn(cell, 'text-xs')}>{r.outcome ? <span className={ok ? 'text-success' : 'text-destructive'}>{ok ? t('uact.success') : r.outcome}</span> : '—'}{r.reason ? <span className={sub}>{r.reason}</span> : null}</TableCell>
                <TableCell data-label={t('uact.colFlags')} className={cell}>{splitFlags(r.flags).length ? splitFlags(r.flags).map((k) => <FlagBadge key={k} flag={k} title={t(`uact.flagHelp.${k}`)}>{t(`uact.anom_${k}`)}</FlagBadge>) : <span className="text-muted-foreground">—</span>}</TableCell>
              </TableRow>
            )
          })}</TableBody>
        </DataTable>
      )}
      <PaginationBar {...pager} />
    </ModalShell>
  )
}
