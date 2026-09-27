import { useCallback, useEffect, useState } from 'react'
import { CalendarDays } from 'lucide-react'
import { api } from '../../api/client'
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
 */
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
                <TableHead className="text-right">{t('wracc.colReports')}</TableHead>
                <TableHead>{t('wracc.colState')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((r) => (
                <TableRow key={r.team_id} data-passive={r.active ? undefined : 'true'}>
                  <TableCell className="whitespace-normal">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <TeamBadge teamId={r.team_id} teamName={r.team_name} />
                      {!r.active && <ToneBadge tone="muted">{t('wracc.passive')}</ToneBadge>}
                    </div>
                  </TableCell>
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
                  <TableCell colSpan={3} className="py-6 text-center text-muted-foreground">{t('wracc.noMatch')}</TableCell>
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
