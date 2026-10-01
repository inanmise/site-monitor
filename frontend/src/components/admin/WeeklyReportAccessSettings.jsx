import { useCallback, useEffect, useState } from 'react'
import { CalendarDays } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ToneBadge from './ToneBadge.jsx'
import { ToolbarSearch } from './ListToolbar.jsx'
import { SETTINGS_STACK, SettingsHeader, SettingsSection } from './SettingsControls.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Switch } from '@/components/shadcn/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Haftalık Raporlar modülünün TAKIM BAZLI görünürlüğü (2026-09-16, kullanıcı kararı).
 *
 * <p>Varsayılan KAPALI: modül herkese açık bir ekran değildir. Burada açılan takımlar
 * "Haftalık Raporlar" sayfasını görür; kapalı takım sayfayı görmez, uçlar 403 döner,
 * tamamlama panosu/şerit/hatırlatma maili o takımı saymaz ve Genel Bakış'taki haftalık
 * rapor kartı çizilmez.
 *
 * <p>Kapatmak VERİ SİLMEZ — mevcut raporlar durur, yalnız görünmez olur; tekrar açılınca
 * aynen geri gelir. Bu yüzden kapatma onayında rapor sayısı gösterilir.
 *
 * <p><b>Satır bilgisi (2026-09-30, kullanıcı isteği):</b> takımın PO'ları (ad + mailto), müdürü (Takım Yönetimi ile
 * AYNI kural — sunucu `TeamManagerResolver`; kaynak rozeti "elle" / "AD") ve son raporu (ISO hafta + durum +
 * gönderim/onay zamanı; hiç yoksa "Hiç gönderilmedi"). Geniş ekranda (≥768) üç ek sütun; telefonda takım hücresinin
 * altında etiket:değer satırları. Veri `GET /weekly-reports/access/teams` satırından (`po_users`, `manager_*`,
 * `last_report`). Test kancaları: `wracc-po`, `wracc-manager`, `wracc-last`, `wracc-mobile`.
 */

/** Haftalık rapor durumu → ton + i18n (WeeklyThisWeekStrip ile aynı etiket kuralı; onaylanmış + gönderilmiş = "Gönderildi"). */
const STATUS_TONE = { DRAFT: 'muted', PENDING_APPROVAL: 'warning', APPROVED: 'success', REJECTED: 'danger' }
function statusLabel(t, r) {
  const st = String(r?.status || 'DRAFT')
  if (st === 'APPROVED' && r.sent_at) return t('wr.statusSent')
  const key = st === 'PENDING_APPROVAL' ? 'Pending' : st.charAt(0) + st.slice(1).toLowerCase()
  return t('wr.status' + key)
}

/** Kişi adı: e-postası varsa mailto bağlantısı (dokunma hedefi satır yüksekliğinde), yoksa düz metin. */
function PersonLink({ name, email, t }) {
  if (!email) return <span className="break-words">{name}</span>
  return (
    <a href={`mailto:${email}`} title={email} aria-label={t('wracc.mailTo', name)}
      className="inline-flex min-h-6 items-center break-words text-primary underline-offset-4 hover:underline">
      {name}
    </a>
  )
}

/** PO listesi — birden çok PO virgülle değil sarmalanan çiplerle (telefonda satır kırılır). */
function PoList({ pos, t }) {
  if (!pos?.length) return <span className="text-muted-foreground">{t('wracc.noPo')}</span>
  return (
    <span className="flex flex-wrap gap-x-2 gap-y-0.5">
      {pos.map((p) => <PersonLink key={p.user_id ?? p.email ?? p.display_name} name={p.display_name} email={p.email} t={t} />)}
    </span>
  )
}

/** Müdür — ad (+ mailto) ve kaynak rozeti: "elle" (teams.manager_id) ya da "AD" (üyelerin yönetim zinciri). */
function ManagerCell({ r, t }) {
  if (!r.manager_display_name) return <span className="text-muted-foreground">{t('wracc.noManager')}</span>
  const manual = !!r.manager_manual
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <PersonLink name={r.manager_display_name} email={r.manager_email} t={t} />
      <Badge variant="outline" data-slot="wracc-manager-source" data-manual={manual ? 'true' : 'false'}
        title={manual ? t('wracc.mgrManualTitle') : t('wracc.mgrAdTitle')} className="h-5 px-1.5 text-[11px] font-normal text-muted-foreground">
        {manual ? t('wracc.mgrManual') : t('wracc.mgrAd')}
      </Badge>
    </span>
  )
}

/** Son rapor — ISO hafta + durum rozeti + (varsa) onay/gönderim zamanı; hiç yoksa "Hiç gönderilmedi". */
function LastReportCell({ r, t }) {
  const last = r.last_report
  if (!last) return <span className="text-muted-foreground">{t('wracc.never')}</span>
  const when = last.approved_at
    ? t('wracc.lastApproved', formatDate(last.approved_at))
    : last.submitted_at ? t('wracc.lastSubmitted', formatDate(last.submitted_at))
      : last.updated_at ? t('wracc.lastUpdated', formatDate(last.updated_at)) : null
  return (
    <span className="inline-flex flex-col gap-0.5">
      <span className="inline-flex flex-wrap items-center gap-1.5">
        <span className="font-semibold tabular-nums" title={last.week_label || undefined}>{last.iso_week}</span>
        <ToneBadge tone={STATUS_TONE[last.status] || 'muted'} data-status={last.status}>{statusLabel(t, last)}</ToneBadge>
      </span>
      {when && <span className="text-xs text-muted-foreground">{when}</span>}
    </span>
  )
}

export default function WeeklyReportAccessSettings() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [rows, setRows] = useState(null)
  const [q, setQ] = useState('')
  const [busyId, setBusyId] = useState(null)

  const load = useCallback(async () => {
    const r = await api.weeklyReports.accessTeams()
    if (r?.success && r.data) setRows(r.data.teams || [])
    else setRows([])
  }, [])
  useEffect(() => { load() }, [load])

  async function toggle(row) {
    const next = !row.enabled
    if (!next && row.report_count > 0) {
      const ok = await showConfirm({
        title: t('wracc.closeTitle', row.team_name),
        message: t('wracc.closeMsg', row.report_count),
        confirmText: t('wracc.closeConfirm'),
        cancelText: t('wracc.cancel'),
        variant: 'danger',
      })
      if (!ok) return
    }
    setBusyId(row.team_id)
    try {
      const res = await api.weeklyReports.setAccess(row.team_id, next)
      if (res?.success) {
        setRows((prev) => (prev || []).map((x) => (x.team_id === row.team_id ? { ...x, enabled: next } : x)))
        toast.success(next ? t('wracc.opened', row.team_name) : t('wracc.closed', row.team_name))
      } else {
        toast.error(res?.error || t('wracc.saveError'))
      }
    } catch {
      toast.error(t('wracc.saveError'))
    } finally {
      setBusyId(null)
    }
  }

  const list = (rows || []).filter((r) => !q.trim() || String(r.team_name || '').toLocaleLowerCase('tr').includes(q.trim().toLocaleLowerCase('tr')))
  const enabledCount = (rows || []).filter((r) => r.enabled).length

  return (
    <div className={SETTINGS_STACK} data-testid="wracc-settings">
    <SettingsHeader icon={CalendarDays} title={t('wracc.title')} description={t('wracc.desc')}
      meta={<Badge variant="outline" className="text-muted-foreground">{t('wracc.enabledCount', enabledCount)}</Badge>}>
      {rows && enabledCount === 0 && (
        <AlertBanner tone="info" title={t('wracc.noneTitle')} role="status" className="mb-0">{t('wracc.noneBody')}</AlertBanner>
      )}
    </SettingsHeader>
    <SettingsSection contentClassName="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <ToolbarSearch value={q} onChange={setQ} placeholder={t('wracc.searchPh')} ariaLabel={t('wracc.searchPh')}
          clearLabel={t('app.clear')} className="h-9 w-full sm:w-auto" />
      </div>
      {rows == null ? (
        <LoadingBlock label={t('wracc.loading')} className="justify-start px-0 py-4" />
      ) : (
        <div className="overflow-hidden rounded-lg border">
          <Table data-testid="wracc-table">
            <TableHeader className="bg-muted/50">
              <TableRow>
                <TableHead>{t('wracc.colTeam')}</TableHead>
                <TableHead className="hidden md:table-cell">{t('wracc.colPo')}</TableHead>
                <TableHead className="hidden md:table-cell">{t('wracc.colManager')}</TableHead>
                <TableHead className="hidden md:table-cell">{t('wracc.colLast')}</TableHead>
                <TableHead className="text-right">{t('wracc.colReports')}</TableHead>
                <TableHead>{t('wracc.colState')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((r) => (
                <TableRow key={r.team_id} data-passive={r.active ? undefined : 'true'} className="align-top">
                  <TableCell className="whitespace-normal">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <TeamBadge teamId={r.team_id} teamName={r.team_name} />
                      {!r.active && <ToneBadge tone="muted">{t('wracc.passive')}</ToneBadge>}
                    </div>
                    {/* Telefon/tablet-altı: ek bilgiler etiket:değer satırları olarak takımın altında (sütunlar ≥768'de). */}
                    <dl data-slot="wracc-mobile" className="mt-1.5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-2 gap-y-1 text-sm md:hidden">
                      <dt className="text-xs font-medium text-muted-foreground">{t('wracc.colPo')}</dt>
                      <dd className="m-0 min-w-0"><PoList pos={r.po_users} t={t} /></dd>
                      <dt className="text-xs font-medium text-muted-foreground">{t('wracc.colManager')}</dt>
                      <dd className="m-0 min-w-0"><ManagerCell r={r} t={t} /></dd>
                      <dt className="text-xs font-medium text-muted-foreground">{t('wracc.colLast')}</dt>
                      <dd className="m-0 min-w-0"><LastReportCell r={r} t={t} /></dd>
                    </dl>
                  </TableCell>
                  <TableCell data-slot="wracc-po" className="hidden whitespace-normal md:table-cell"><PoList pos={r.po_users} t={t} /></TableCell>
                  <TableCell data-slot="wracc-manager" className="hidden whitespace-normal md:table-cell"><ManagerCell r={r} t={t} /></TableCell>
                  <TableCell data-slot="wracc-last" className="hidden whitespace-normal md:table-cell"><LastReportCell r={r} t={t} /></TableCell>
                  <TableCell className="text-right tabular-nums">{r.report_count || 0}</TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Switch checked={!!r.enabled} disabled={busyId === r.team_id} onCheckedChange={() => toggle(r)}
                        aria-label={r.enabled ? t('wracc.switchOn', r.team_name) : t('wracc.switchOff', r.team_name)} />
                      <span className={cn('text-sm font-semibold', r.enabled ? 'text-success' : 'text-muted-foreground')}>
                        {r.enabled ? t('wracc.on') : t('wracc.off')}
                      </span>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {list.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="py-6 text-center text-muted-foreground">{t('wracc.noMatch')}</TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      )}
      <p className="text-xs text-muted-foreground">{t('wracc.hint')}</p>
    </SettingsSection>
    </div>
  )
}
