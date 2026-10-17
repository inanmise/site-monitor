import { useEffect, useMemo, useRef, useState } from 'react'
import { FileWarning, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { unwrap } from '../noc/nocModel.js'
import { SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { isDirty, normalizeIds, teamOptions } from './noc/operatorTeamsModel.js'
import { StatusBadge, periodLabel } from '../techerror/TechErrorParts.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Ayarlar → Platform → Teknik hata raporu (2026-10-17, S6): modülü gören takımlar (boş = herkes; GLOBAL_ONLY — kapsamlı
 * müdür salt okunur) ve kanalların onay akışı özeti (yönetici alıcı sayısı, PO onaylayıcı sayısı, dönem raporunun durumu).
 * Yönetici alıcıları kanalın kendi iletişim kutusundan girilir; burası yalnız gösterir.
 */
export default function TechErrorReportSettings({ readOnly = false }) {
  const t = useT()
  const toast = useToast()
  const [settings, setSettings] = useState(null)
  const [draft, setDraft] = useState([])
  const [directory, setDirectory] = useState([])
  const [loadError, setLoadError] = useState(null)
  const [saving, setSaving] = useState(false)
  const alive = useRef(true)
  const loadSeq = useRef(0)

  async function load() {
    const id = ++loadSeq.current
    setLoadError(null)
    try {
      const [cfg, dir] = await Promise.all([
        api.admin.techErrorSettings(),
        Promise.resolve(api.teams?.directory ? api.teams.directory() : null).catch(() => null),
      ])
      if (!alive.current || id !== loadSeq.current) return
      const r = unwrap(cfg)
      if (!r.ok) { setLoadError(r.error || t('te.set.loadError')); return }
      const data = r.data || {}
      const ids = normalizeIds(data.team_ids)
      setSettings({ ...data, ids, readOnly: readOnly || data.read_only === true })
      setDraft(ids)
      const d = unwrap(dir)
      setDirectory(d.ok && Array.isArray(d.data) ? d.data : [])
    } catch (e) {
      if (alive.current && id === loadSeq.current) setLoadError(e?.message || t('te.set.loadError'))
    }
  }
  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const options = useMemo(() => teamOptions(directory, draft, t('noc.ot.inactiveTeam')), [directory, draft, t])
  const dirty = !!settings && isDirty(settings.ids, draft)
  const locked = !!settings?.readOnly

  async function save() {
    if (!settings || saving || locked) return
    setSaving(true)
    try {
      const r = unwrap(await api.admin.saveTechErrorSettings({ team_ids: normalizeIds(draft) }))
      if (!alive.current) return
      if (!r.ok) { toast.error(r.error || t('te.set.saveError'), { details: r.errorInfo }); return }
      const ids = normalizeIds(r.data?.team_ids ?? draft)
      setSettings((s) => ({ ...s, ids }))
      setDraft(ids)
      toast.success(t('te.set.saved'))
    } catch (e) {
      toast.error(e?.message || t('te.set.saveError'))
    } finally {
      if (alive.current) setSaving(false)
    }
  }

  const channels = Array.isArray(settings?.channels) ? settings.channels : []
  const period = settings?.period

  return (
    <SettingsSection title={t('te.set.title')} description={t('te.set.desc')} contentClassName="flex flex-col gap-5">
      <div data-slot="te-settings" className="flex min-w-0 flex-col gap-5">
        {loadError && (
          <AlertBanner tone="danger" role="alert" title={t('te.set.loadError')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={load}><RefreshCw aria-hidden="true" />{t('te.retry')}</Button>}>
            {String(loadError)}
          </AlertBanner>
        )}
        {!settings && !loadError && <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-4" />}
        {settings && (
          <>
            <Field label={t('te.set.visibility')} hint={t('te.set.visibilityHint')} name="team_ids">
              {({ id }) => (
                <MultiTeamSelect id={id} value={draft} options={options} disabled={locked} ariaLabel={t('te.set.visibility')}
                  placeholder={t('te.set.selectTeams')} onChange={(ids) => setDraft(normalizeIds(ids))} />
              )}
            </Field>
            <p className="-mt-2 text-sm text-muted-foreground" data-slot="te-settings-everyone">
              {draft.length === 0 ? t('te.set.everyone') : null}
              {locked ? ` ${t('te.set.readOnly')}` : null}
            </p>
            {!locked && (
              <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => setDraft(settings.ids)} />
            )}

            <section aria-labelledby="te-set-channels" className="flex flex-col gap-3">
              <div>
                <h4 id="te-set-channels" className="m-0 flex items-center gap-2 text-sm font-semibold">
                  <FileWarning aria-hidden="true" className="size-4 text-muted-foreground" /> {t('te.set.channels')}
                </h4>
                <p className="m-0 mt-1 text-sm text-muted-foreground">{t('te.set.channelsHint')}</p>
              </div>
              {channels.length === 0 ? (
                <p className="m-0 text-sm text-muted-foreground">{t('te.set.noChannels')}</p>
              ) : (
                <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0 md:grid-cols-2" data-slot="te-settings-channels">
                  {channels.map((ch) => (
                    <li key={ch.id} className={cn('flex min-w-0 flex-col gap-2 rounded-xl border bg-card p-3 shadow-xs', !ch.active && 'opacity-80')}
                      data-slot="te-settings-channel" data-flow={ch.executive_count > 0 ? 'approval' : 'team'}>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium [overflow-wrap:anywhere]">{ch.name}</span>
                        <TeamBadge teamId={ch.team_id} teamName={ch.team_name} static wrap />
                        {!ch.active && <Badge variant="outline">{t('te.inactive')}</Badge>}
                        {ch.team_visible === false && <Badge variant="outline" className="rounded-md overflow-visible whitespace-normal">{t('te.set.teamHidden')}</Badge>}
                      </div>
                      <div className="flex flex-wrap gap-2 text-xs">
                        <Badge variant={ch.executive_count > 0 ? 'secondary' : 'outline'} className="rounded-md overflow-visible whitespace-normal">
                          {ch.executive_count > 0 ? t('te.set.col.exec') + ': ' + ch.executive_count : t('te.set.noFlow')}
                        </Badge>
                        <Badge variant={ch.approver_count > 0 ? 'secondary' : 'outline'} className="rounded-md overflow-visible whitespace-normal"
                          data-slot="te-settings-approvers">
                          {ch.approver_count > 0 ? t('te.set.col.approvers') + ': ' + ch.approver_count : t('te.set.noApprover')}
                        </Badge>
                        {ch.period_report ? (
                          <StatusBadge status={ch.period_report.status || null} sendState={ch.period_report.last_send_status}
                            prefix={period ? periodLabel(period, t) : undefined} />
                        ) : (
                          <Badge variant="outline" className="rounded-md overflow-visible whitespace-normal">{t('te.set.noReport')}</Badge>
                        )}
                        {ch.period_report?.approval?.state && ch.period_report.approval.state !== 'none' && (
                          <Badge variant="outline" className="rounded-md overflow-visible whitespace-normal" data-slot="te-settings-approval">
                            {t(`te.appr.state.${ch.period_report.approval.state}`)}
                          </Badge>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </>
        )}
      </div>
    </SettingsSection>
  )
}
