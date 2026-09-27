import { useMemo, useState } from 'react'
import { Users, Download, ExternalLink, LogOut, Compass, Unlock, Eye, Mail, Copy } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api, formatDateSec, formatDateOnly } from '../../../api/client'
import { useToast } from '../../ui/Toast.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { usePagination } from '../../../hooks/usePagination.js'
import { navigateTo } from '../../../utils/navigate.js'
import { toCsv, downloadCsv, stampedName } from '../../../utils/csvExport.js'
import { relTime, loginStatus } from './uactModel.js'
import { ToolbarSearch, FilterField } from '../ListToolbar.jsx'
import { SystemRoleBadge, TONE_CLASS } from '../ToneBadge.jsx'
import { TH, TD, MUTED_SM, DataTable, Pill, LinkButton, StatChip, AuthSourceBadge } from '../HealthUi.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Kullanıcı Dizini (2026-09-20, kullanıcı bildirimi): "1 Aktif oturum" kartı yalnız oturumdakileri
 * listeliyordu. Artık SİSTEMDEKİ BÜTÜN kullanıcılar tek listede — çevrimiçi olanlar en başta —
 * e-posta, rol, takım, kimlik kaynağı (LDAP / Yerel), son görülme, son giriş, oluşturulma, tur
 * durumu, hesap durumu ve zengin işlem menüsü ile; süzgeç + sayfalama. "Turu tamamlayan" kartı da
 * aynı dizini tour=completed süzgeciyle açar.
 *
 * Veri: SystemHealth payload'ındaki login_status (tüm kullanıcılar) + active_users (oturumdakiler)
 * kullanıcı adına göre birleştirilir; ek istek yok.
 * Çizim shadcn: Card süzgeç çubuğu, Table, Badge / Button çipleri (ortak parçalar ../HealthUi.jsx).
 */
export const TOUR_STATES = ['completed', 'dismissed', 'snoozed', 'started', 'none']

const ts = (iso) => { if (!iso) return 0; const t = Date.parse(iso.endsWith('Z') ? iso : iso + 'Z'); return Number.isNaN(t) ? 0 : t }

/** login_status ⊕ active_users → dizin satırları; çevrimiçi (boşta süresi kısa) önce, sonra son giriş yeniye göre. */
export function mergeDirectory(loginStatus = [], activeUsers = []) {
  const act = new Map(activeUsers.map((u) => [String(u.username || '').toLowerCase(), u]))
  const seen = new Set()
  const rows = []
  for (const r of loginStatus) {
    const key = String(r.username || '').toLowerCase(); seen.add(key)
    const a = act.get(key)
    rows.push(a
      ? { ...r, online: true, idle_sec: a.idle_sec, expires_in_sec: a.expires_in_sec, login_at: a.login_at, duration_min: a.duration_min, ip: a.ip, city: a.city, country: a.country, last_tab: a.last_tab, last_tab_at: a.last_tab_at, user_agent: a.user_agent, last_seen: a.last_seen || r.last_seen_at }
      : { ...r, online: false, last_seen: r.last_seen_at })
  }
  for (const [key, a] of act) if (!seen.has(key)) rows.push({ ...a, online: true, last_seen: a.last_seen, tour_status: a.tour_status || 'none' })   // savunma: dizinde olmayan oturum
  rows.sort((x, y) => {
    if (x.online !== y.online) return x.online ? -1 : 1
    if (x.online) return (x.idle_sec ?? 1e9) - (y.idle_sec ?? 1e9)
    return ts(y.last_login_at) - ts(x.last_login_at) || String(x.username).localeCompare(String(y.username))
  })
  return rows
}

export function directoryMatches(r, f) {
  if (f.view === 'online' && !r.online) return false
  if (f.view === 'offline' && r.online) return false
  if (f.tour && (r.tour_status || 'none') !== f.tour) return false
  if (f.team && String(r.team_id ?? '') !== String(f.team) && !(r.team_ids || []).some((id) => String(id) === String(f.team))) return false
  if (f.role && r.system_role !== f.role) return false
  if (f.provider === 'LDAP' && r.auth_source !== 'LDAP') return false
  if (f.provider === 'LOCAL' && r.auth_source === 'LDAP') return false
  if (f.account === 'active' && r.active === false) return false
  if (f.account === 'inactive' && r.active !== false) return false
  if (f.account === 'locked' && !r.permanent_lock) return false
  if (f.q) {
    const q = f.q.toLowerCase()
    if (![r.username, r.display_name, r.email, r.employee_id, r.team_name, r.title, r.department].some((v) => v && String(v).toLowerCase().includes(q))) return false
  }
  return true
}

export function directoryCsv(rows, t) {
  const head = [t('uact.colUser'), t('uact.detailDisplayName'), t('uact.detailEmail'), t('uact.colRole'), t('uact.detailOrgRole'), t('uact.colTeam'), t('uact.colAuthSource'), t('uact.dirOnline'), t('uact.detailLastSeen'), t('uact.colLastLogin'), t('uact.colCreated'), t('uact.colTour'), t('uact.colAccount')]
  return toCsv(head, rows.map((r) => [r.username, r.display_name, r.email, r.system_role, r.org_role, r.team_name, r.auth_source, r.online ? 1 : 0, r.last_seen, r.last_login_at, r.created_at, r.tour_status, r.active === false ? 'inactive' : r.permanent_lock ? 'locked' : 'active']))
}

export default function UserDirectoryModal({ data, initial = {}, isAdmin, globalAdmin, username, onClose, onUser, onTerminate, onRefresh }) {
  const t = useT()
  const toast = useToast()
  const [f, setFRaw] = useState({ view: initial.view || 'all', tour: initial.tour || '', team: '', role: '', provider: '', account: '', q: '' })
  const setF = (p) => setFRaw((x) => ({ ...x, ...p }))
  const [busy, setBusy] = useState(null)
  const all = useMemo(() => mergeDirectory(data?.login_status || [], data?.active_users || []), [data])
  const rows = useMemo(() => all.filter((r) => directoryMatches(r, f)), [all, f])
  // Pencere içi liste → modal ön ayarı (10 / [10,25,50]; compact çubuk ön ayardan gelir)
  const pager = usePagination(rows, { listKey: 'uact-directory', preset: 'modal', resetDeps: [f] })
  const activeSet = useMemo(() => new Set(all.filter((r) => r.online).map((r) => String(r.username).toLowerCase())), [all])
  const rel = (iso) => { const r = relTime(iso); return r ? t(`uact.rel.${r.unit}`, r.n) : '—' }

  const stats = useMemo(() => ({
    total: all.length, online: all.filter((r) => r.online).length,
    ldap: all.filter((r) => r.auth_source === 'LDAP').length, local: all.filter((r) => r.auth_source !== 'LDAP').length,
    tour: all.filter((r) => r.tour_status === 'completed').length, inactive: all.filter((r) => r.active === false).length,
    locked: all.filter((r) => r.permanent_lock).length,
  }), [all])
  const opt = (arr) => [{ value: '', label: t('uact.filterAny') }, ...arr]
  const teamOptions = useMemo(() => {
    const m = new Map()
    for (const r of all) if (r.team_id != null && r.team_name) m.set(String(r.team_id), r.team_name)
    return opt([...m.entries()].sort((a, b) => a[1].localeCompare(b[1])).map(([value, label]) => ({ value, label })))
  }, [all]) // eslint-disable-line react-hooks/exhaustive-deps
  const roleOptions = useMemo(() => opt([...new Set(all.map((r) => r.system_role).filter(Boolean))].sort().map((r) => ({ value: r, label: r }))), [all]) // eslint-disable-line react-hooks/exhaustive-deps
  const tourOptions = opt(TOUR_STATES.map((s) => ({ value: s, label: t(`uact.tour.${s}`) })))
  const providerOptions = opt([{ value: 'LDAP', label: 'LDAP' }, { value: 'LOCAL', label: t('usr.authLocal') }])
  const accountOptions = [{ value: '', label: t('uact.colStatus') }, { value: 'active', label: t('usr.active') }, { value: 'inactive', label: t('usr.inactive') }, { value: 'locked', label: t('usr.permLocked') }]   // başlıkta 'Durum' okunur

  async function run(row, label, fn) {
    setBusy(row.username)
    try {
      const r = await fn()
      if (r?.success === false) toast.error(r?.error || t('uact.loadError')); else { toast.success(label); onRefresh?.() }
    } catch (e) { toast.error(e?.message || t('uact.loadError')) } finally { setBusy(null) }
  }
  const menu = (r) => {
    const self = username && String(r.username).toLowerCase() === String(username).toLowerCase()
    return [
      { label: t('uact.openDetail'), icon: <Eye size={14} />, onClick: () => onUser?.(r) },
      { label: t('uact.actOpenAdmin'), icon: <ExternalLink size={14} />, hidden: !isAdmin, onClick: () => { onClose?.(); navigateTo('admin', { g_tab: 'users', g_q: r.username }) } },
      { label: t('uact.actMail'), icon: <Mail size={14} />, hidden: !r.email, onClick: () => { try { window.open(`mailto:${r.email}`, '_self') } catch { /* jsdom */ } } },
      { label: t('uact.actCopyUser'), icon: <Copy size={14} />, onClick: () => { try { navigator.clipboard.writeText(r.username); toast.success(t('uact.copiedUser')) } catch { toast.error(t('uact.copyFailed')) } } },
      { label: t('uact.terminate'), icon: <LogOut size={14} />, danger: true, hidden: !(isAdmin && r.online && !self), onClick: () => onTerminate?.(r.username) },
      { label: t('usr.tourReset'), icon: <Compass size={14} />, hidden: !(isAdmin && r.user_id != null && (r.tour_status || 'none') !== 'none'), onClick: () => run(r, t('usr.tourResetDone'), () => api.admin.resetUserTour(r.user_id)) },
      { label: t('uact.actUnlock'), icon: <Unlock size={14} />, hidden: !(globalAdmin && r.permanent_lock && r.user_id != null), onClick: () => run(r, t('uact.unlocked'), () => api.admin.unlockUser(r.user_id)) },
    ]
  }
  const chip = (key, val, label, tone) => (
    <StatChip key={key} val={val} label={label} tone={tone}
      on={f.view === key || f.tour === key || f.account === key || f.provider === key}
      onClick={() => {
        if (key === 'online' || key === 'all') setF({ view: key })
        else if (key === 'completed') setF({ tour: f.tour === 'completed' ? '' : 'completed' })
        else if (key === 'LDAP' || key === 'LOCAL') setF({ provider: f.provider === key ? '' : key })
        else if (key === 'inactive' || key === 'locked') setF({ account: f.account === key ? '' : key })
      }} />
  )
  /** Satırdaki hesap rozeti = o duruma süzen düğme (toggle). */
  const accountBtn = (key, tone, label) => (
    <Button type="button" variant="outline" size="xs" aria-pressed={f.account === key} title={t('uact.dirFilterBy')}
      onClick={() => setF({ account: f.account === key ? '' : key })}
      className={cn('h-auto rounded-md px-[7px] py-0.5 text-[11px] font-semibold', TONE_CLASS[tone], 'hover:border-current aria-pressed:border-current')}>
      {label}
    </Button>
  )
  const title = f.tour === 'completed' && f.view === 'all' ? t('uact.tourKpi') : t('uact.dirTitle')

  return (
    <ModalShell open onClose={onClose} title={`${title} · ${rows.length}${rows.length !== all.length ? ' / ' + all.length : ''}`} icon={Users} size="xl" scrollBody
      footer={<>
        <Button type="button" variant="secondary" onClick={() => downloadCsv(stampedName('users'), directoryCsv(rows, t))}><Download size={14} /> {t('uact.exportDirectory')}</Button>
        <Button type="button" onClick={onClose}>{t('app.dismiss')}</Button>
      </>}>
      <div className="mb-2.5 flex flex-wrap gap-1.5" data-testid="udir-stats">
        {chip('all', stats.total, t('uact.dirAll'))}
        {chip('online', stats.online, t('uact.dirOnline'), 'ok')}
        {chip('LDAP', stats.ldap, 'LDAP')}
        {chip('LOCAL', stats.local, t('usr.authLocal'))}
        {chip('completed', stats.tour, t('uact.tourKpi'))}
        {chip('inactive', stats.inactive, t('usr.inactive'), stats.inactive > 0 ? 'warn' : undefined)}
        {chip('locked', stats.locked, t('usr.permLocked'), stats.locked > 0 ? 'danger' : undefined)}
      </div>
      <Card data-testid="udir-filters" className="mb-2.5 flex-row flex-wrap items-end gap-2.5 px-3 py-2.5 shadow-none">
        <SegmentedControl ariaLabel={t('uact.dirView')} value={f.view} onChange={(v) => setF({ view: v })}
          options={[{ value: 'all', label: t('uact.dirAll') }, { value: 'online', label: t('uact.dirOnline') }, { value: 'offline', label: t('uact.dirOffline') }]} />
        <FilterField label={t('uact.dirSearch')} className="flex-[1_1_200px]">
          <ToolbarSearch value={f.q} onChange={(v) => setF({ q: v })} placeholder={t('uact.dirSearchPh')} ariaLabel={t('uact.dirSearch')} clearLabel={t('app.clear')} className="max-w-none flex-auto" />
        </FilterField>
        <FilterField label={t('uact.colTeam')} className="w-full sm:w-auto sm:min-w-[140px]"><SearchableSelect ariaLabel={t('uact.colTeam')} value={f.team} onChange={(v) => setF({ team: v })} options={teamOptions} searchThreshold={6} /></FilterField>
        <FilterField label={t('uact.colRole')} className="w-full sm:w-auto sm:min-w-[140px]"><SearchableSelect ariaLabel={t('uact.colRole')} value={f.role} onChange={(v) => setF({ role: v })} options={roleOptions} searchThreshold={99} /></FilterField>
        <FilterField label={t('uact.colAuthSource')} className="w-full sm:w-auto sm:min-w-[140px]"><SearchableSelect ariaLabel={t('uact.colAuthSource')} value={f.provider} onChange={(v) => setF({ provider: v })} options={providerOptions} searchThreshold={99} /></FilterField>
        <FilterField label={t('uact.colTour')} className="w-full sm:w-auto sm:min-w-[140px]"><SearchableSelect ariaLabel={t('uact.colTour')} value={f.tour} onChange={(v) => setF({ tour: v })} options={tourOptions} searchThreshold={99} /></FilterField>
        {(f.q || f.team || f.role || f.provider || f.tour || f.account || f.view !== 'all') && <Button type="button" variant="secondary" size="sm" onClick={() => setFRaw({ view: 'all', tour: '', team: '', role: '', provider: '', account: '', q: '' })}>{t('uact.filterClear')}</Button>}
      </Card>
      {rows.length === 0 ? <StatusBlock tone="neutral" icon={Users} title={t('uact.noRows')} /> : (
        // Sabit yerleşim (yatay kaydırma yok): sütun genişlikleri başlık hücrelerinde (eski colgroup `.udir-c-*`)
        <DataTable testId="udir-table" fixed>
          <TableHeader><TableRow>
            <TableHead className={cn(TH, 'w-[26%]')}>{t('uact.colUser')}</TableHead>
            <TableHead className={cn(TH, 'hidden w-[10%] md:table-cell')}>{t('uact.colRole')}</TableHead>
            <TableHead className={cn(TH, 'hidden w-[14%] md:table-cell')}>{t('uact.colTeam')}</TableHead>
            <TableHead className={cn(TH, 'hidden w-[8%] lg:table-cell')}>{t('uact.colSource')}</TableHead>
            <TableHead className={cn(TH, 'hidden w-[17%] md:table-cell')}>{t('uact.colActivity')}</TableHead>
            <TableHead className={cn(TH, 'hidden w-[9%] lg:table-cell')}>{t('uact.colCreated')}</TableHead>
            <TableHead data-testid="udir-th-filter" className={cn(TH, 'w-[12%] py-1 font-medium tracking-normal normal-case')}>
              {/* Hesap süzgeci sütun üstünde (2026-09-20 kullanıcı bildirimi): panelden kaldırıldı */}
              <SearchableSelect ariaLabel={t('uact.colAccount')} value={f.account} onChange={(v) => setF({ account: v })} options={accountOptions} searchThreshold={99} placeholder={t('uact.colStatus')} />
            </TableHead>
            <TableHead className={cn(TH, 'w-12 md:w-[4%]')}>{t('uact.colAction')}</TableHead>
          </TableRow></TableHeader>
          <TableBody>{pager.pageItems.map((r) => {
            const st = loginStatus(r, activeSet)
            const self = username && String(r.username).toLowerCase() === String(username).toLowerCase()
            const extra = (r.team_ids || []).filter((id) => String(id) !== String(r.team_id ?? ''))
            const dept = r.display_name && /\([^)]*\)\s*$/.test(r.display_name) ? r.display_name.match(/\(([^)]*)\)\s*$/)[1] : (r.department || null)
            const cell = cn(TD, 'overflow-hidden align-top')
            const meta = 'mt-0.5 block truncate text-[11px] text-muted-foreground'
            return (
              <TableRow key={r.username} data-online={r.online ? 'true' : undefined} data-self={self ? 'true' : undefined}
                className={cn(r.online && 'bg-green-500/5')}>
                <TableCell data-label={t('uact.colUser')} className={cn(cell, 'relative pl-[22px]')}>
                  {/* 2026-09-20 (kullanıcı bildirimi "karışık"): tek düzen — nokta + avatar + ad; altında kullanıcı adı · departman; altında e-posta */}
                  <span className={cn('absolute top-3.5 left-2.5 size-2 rounded-full', r.online ? 'bg-green-500 shadow-[0_0_0_3px_rgba(34,197,94,.18)]' : 'bg-border')}
                    title={r.online ? t('uact.dirOnline') : t('uact.dirOffline')} aria-hidden="true" />
                  <div className="flex min-w-0 items-center gap-1.5">
                    <LinkButton data-part="name" className="ml-0 max-w-full min-w-0 justify-start text-left no-underline" title={r.display_name || r.username} onClick={() => onUser?.(r)}><UserBadge username={r.username} userId={r.user_id} displayName={r.display_name} nameOnly inline /></LinkButton>
                    {self && <Pill>{t('uact.selfSession')}</Pill>}
                  </div>
                  <div data-part="meta" className={cn(meta, 'pl-[33px]')} title={r.display_name || ''}><span className="font-mono">{r.username}</span>{dept && <> · {dept}</>}</div>
                  <div data-part="email" className={cn(meta, 'pl-[33px] font-mono')} title={r.email || ''}>{r.email || '—'}</div>
                </TableCell>
                <TableCell data-label={t('uact.colRole')} className={cn(cell, 'hidden md:table-cell')}>{r.system_role ? <SystemRoleBadge role={r.system_role} /> : '—'}{r.org_role && <div className={meta}>{t('usr.orgRoleVal.' + r.org_role)}</div>}</TableCell>
                <TableCell data-label={t('uact.colTeam')} className={cn(cell, 'hidden md:table-cell')}>{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : <span className="text-muted-foreground">—</span>}{extra.length > 0 && <div className={cn(meta, 'inline-flex flex-wrap gap-1')} title={t('uact.dirExtraTeams', extra.length)}>{extra.slice(0, 2).map((id) => <TeamBadge key={id} teamId={id} size={11} />)}{extra.length > 2 ? ` +${extra.length - 2}` : ''}</div>}</TableCell>
                <TableCell data-label={t('uact.colAuthSource')} className={cn(cell, 'hidden lg:table-cell')}><AuthSourceBadge source={r.auth_source} localLabel={t('usr.authLocal')} /></TableCell>
                <TableCell data-label={t('uact.colActivity')} className={cn(cell, 'hidden pt-2 text-xs md:table-cell')}>
                  <div className="flex items-baseline gap-1.5 truncate whitespace-nowrap"><span className="min-w-[62px] flex-none text-[10.5px] text-muted-foreground">{t('uact.detailLastSeen')}</span>{r.online ? <Pill tone="active" status="active">{t('uact.st.active')}{r.idle_sec > 0 ? ` · ${rel(r.last_seen)}` : ''}</Pill> : (r.last_seen ? <span title={formatDateSec(r.last_seen)}>{rel(r.last_seen)}</span> : '—')}</div>
                  <div className="mt-[3px] flex items-baseline gap-1.5 truncate whitespace-nowrap"><span className="min-w-[62px] flex-none text-[10.5px] text-muted-foreground">{t('uact.colLastLogin')}</span>{r.last_login_at ? <span title={formatDateSec(r.last_login_at)}>{rel(r.last_login_at)}{r.last_login_method ? <span className="text-muted-foreground"> · {r.last_login_method}</span> : null}</span> : <Pill tone={st} status={st}>{t(`uact.st.${st}`)}</Pill>}</div>
                </TableCell>
                <TableCell data-label={t('uact.colCreated')} className={cn(cell, 'hidden text-xs lg:table-cell')}>{r.created_at ? <span title={formatDateSec(r.created_at)}>{formatDateOnly(r.created_at)}</span> : '—'}</TableCell>
                <TableCell data-label={t('uact.colStatus')} className={cell}>
                  <div className="flex flex-wrap gap-1">
                    {r.active === false && accountBtn('inactive', 'danger', t('usr.inactive'))}
                    {r.permanent_lock && accountBtn('locked', 'danger', t('usr.permLocked'))}
                    {r.active !== false && !r.permanent_lock && accountBtn('active', 'success', t('usr.active'))}
                    <Pill tone={r.tour_status || 'none'} status={`tour-${r.tour_status || 'none'}`} title={`${t('uact.colTour')}${r.tour_at ? ' · ' + formatDateSec(r.tour_at) : ''}`}>{t('uact.colTour')}: {t(`uact.tour.${r.tour_status || 'none'}`)}</Pill>
                  </div>
                </TableCell>
                <TableCell data-label={t('uact.colAction')} className={cell}>{busy === r.username ? <span className={MUTED_SM}>…</span> : <KebabMenu items={menu(r)} label={t('uact.colAction')} rowLabel={r.username} />}</TableCell>
              </TableRow>
            )
          })}</TableBody>
        </DataTable>
      )}
      <PaginationBar {...pager} />
    </ModalShell>
  )
}
