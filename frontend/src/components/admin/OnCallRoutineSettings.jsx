import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Archive, CircleAlert, ExternalLink, EyeOff, ListTodo, RefreshCw, Users } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import ToneBadge from './ToneBadge.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { applyTabView } from '../../utils/navigate.js'
import { SettingsSaveBar, SettingsSection, ToggleRow } from './SettingsControls.jsx'
import { normalizeIds, teamOptions } from './noc/operatorTeamsModel.js'
import {
  ORS_RETENTION_POLICY, ORS_SWITCHES, ORS_TABLE_MIN_PX, changedSettings, fieldOf, isUnsupported, normalizeSettings, overviewRows,
} from './onCallRoutineSettingsModel.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { thrownResult } from '../oncallroutine/routineModel.js'

const TOUCH = 'pointer-coarse:min-h-10'
/** Rozet satır kırar (uzun takım / durum adı kırpılmaz). */
const WRAP_BADGE = 'h-auto overflow-visible rounded-md whitespace-normal text-left'

/** Anahtar → açıklama anahtarı (görünen ad `or.adm.sw.<k>`, açıklama `or.adm.sw.<k>Hint`). */
const SW_SLOT = { push_reminders: 'ors-sw-reminders', completion_notice: 'ors-sw-completion', handover_notice: 'ors-sw-handover' }

function Loading({ t }) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-3" data-slot="ors-skeleton">
      <span className="sr-only">{t('settings.loading')}</span>
      <Skeleton className="h-4 w-40 motion-reduce:animate-none" />
      <Skeleton className="h-10 w-full motion-reduce:animate-none" />
      <Skeleton className="h-4 w-3/4 motion-reduce:animate-none" />
    </div>
  )
}

/** Rutin durumu rozeti (kurulmamış / duraklatıldı / açık / bilinmiyor). */
function StateBadge({ state, t }) {
  const tone = state === 'active' ? 'info' : 'muted'
  return <ToneBadge tone={tone} data-slot="ors-state" data-state={state} className={WRAP_BADGE}>{t(`or.adm.state.${state}`)}</ToneBadge>
}

function TodayBadge({ row, t }) {
  return (
    <ToneBadge tone={row.todayTone} data-slot="ors-today" data-status={row.today} className={WRAP_BADGE}>
      {t(`or.adm.status.${row.today}`)}
    </ToneBadge>
  )
}

/** Son 30 gün tamamlanma: yüzde (tonlu) + iş günü sayısı; bilinmiyor "—" (asla %0 değil). */
function Completion({ row, t }) {
  if (row.pct == null) {
    return <span className="text-muted-foreground" data-slot="ors-pct" data-unknown="true">{row.workdays === 0 ? t('or.adm.noWorkdays') : '—'}</span>
  }
  return (
    <span className="flex min-w-0 flex-col" data-slot="ors-pct" data-tone={row.pctTone}>
      <ToneBadge tone={row.pctTone} className={cn(WRAP_BADGE, 'w-fit tabular-nums')}>{t('or.adm.pct', row.pct)}</ToneBadge>
      <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
        {t('or.adm.workdays', row.workdays ?? 0)}{row.missed > 0 ? ` · ${t('or.adm.missed', row.missed)}` : ''}
      </span>
    </span>
  )
}

function TeamName({ row, t }) {
  return (
    <span className="flex min-w-0 flex-col gap-1">
      <span className="font-medium [overflow-wrap:anywhere]" data-slot="ors-team-name">{row.name || t('or.adm.teamUnnamed')}</span>
      {row.moduleVisible === false && (
        <ToneBadge tone="warning" data-slot="ors-hidden" className={cn(WRAP_BADGE, 'w-fit')}>
          <EyeOff aria-hidden="true" className="size-3" /> {t('or.adm.moduleHidden')}
        </ToneBadge>
      )}
    </span>
  )
}

/**
 * Ayarlar → Platform → Nöbet rutinleri (2026-11-26). Kurum geneli ayarlar (GLOBAL_ONLY — kapsamlı müdür SALT OKUNUR görür):
 * modülü gören takımlar (boş = herkes), üç bildirim anahtarı (nöbetçiye hatırlatma push'u, "rutin tamamlandı" bildirimi,
 * nöbet devri özeti), saklama süresi notu (Veri Saklama'da aç) ve takımların rutin özeti (kurulu mu, aktif görev, bugün, son
 * 30 gün tamamlanma). Kayıt YALNIZ değişen anahtarları gönderir. Okuma başarısızsa uydurulmaz — "okunamadı" + yeniden dene;
 * eski pod (uç yok) "bu sürümde yok" der.
 */
export default function OnCallRoutineSettings({ readOnly = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const notifyTitleId = useId()
  const [saved, setSaved] = useState(null)        // normalizeSettings çıktısı
  const [draft, setDraft] = useState(null)        // { team_ids, push_reminders, … }
  const [loadError, setLoadError] = useState(null)
  const [unsupported, setUnsupported] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [fieldError, setFieldError] = useState(null)   // { field, message }
  const [overview, setOverview] = useState({ status: 'loading', rows: null, error: null })
  const [listRef, listWidth] = useElementWidth()
  const alive = useRef(true)
  const loadSeq = useRef(0)
  const ovSeq = useRef(0)
  // Yenile kaydedilmemiş taslağı silmesin: okuma yanıtı geldiğinde taslak kirliyse korunur
  const savedRef = useRef(null)
  savedRef.current = saved
  const draftRef = useRef(null)
  draftRef.current = draft

  const loadSettings = useCallback(async () => {
    const id = ++loadSeq.current
    setLoadError(null)
    setLoading(true)
    try {
      const res = await api.admin.oncallRoutines.settings()
      if (!alive.current || id !== loadSeq.current) return
      if (isUnsupported(res)) { setUnsupported(true); return }
      if (!res || res.success === false) { setLoadError(res?.error || t('or.adm.loadError')); return }
      const next = normalizeSettings(res.data, readOnly)
      const prev = savedRef.current
      const cur = draftRef.current
      const keepDraft = prev && cur && Object.keys(changedSettings(prev.values, cur)).length > 0
      setUnsupported(false)
      setSaved(next)
      if (!keepDraft) setDraft(next.values)
    } catch (e) {
      if (alive.current && id === loadSeq.current) setLoadError(e?.message || t('or.adm.loadError'))
    } finally {
      if (alive.current && id === loadSeq.current) setLoading(false)
    }
  }, [readOnly, t])

  const loadOverview = useCallback(async () => {
    const id = ++ovSeq.current
    setOverview((o) => ({ ...o, status: o.rows ? 'refreshing' : 'loading', error: null }))
    try {
      const res = await api.admin.oncallRoutines.overview()
      if (!alive.current || id !== ovSeq.current) return
      if (isUnsupported(res)) { setOverview({ status: 'unsupported', rows: null, error: null }); return }
      if (!res || res.success === false) {
        setOverview((o) => ({ status: 'failed', rows: o.rows, error: res?.error || t('or.adm.overviewLoadError') }))
        return
      }
      const rows = overviewRows(res.data)
      if (!rows) { setOverview((o) => ({ status: 'failed', rows: o.rows, error: t('or.adm.overviewLoadError') })); return }
      setOverview({ status: 'ok', rows, error: null })
    } catch (e) {
      if (alive.current && id === ovSeq.current) {
        setOverview((o) => ({ status: 'failed', rows: o.rows, error: thrownResult(e).error || t('or.adm.overviewLoadError') }))
      }
    }
  }, [t])

  useEffect(() => {
    alive.current = true
    loadSettings()
    loadOverview()
    return () => { alive.current = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const locked = !!saved?.readOnly
  const body = useMemo(() => (saved && draft ? changedSettings(saved.values, draft) : {}), [saved, draft])
  const dirty = Object.keys(body).length > 0

  // Seçici seçenekleri sunucunun takım listesinden; listede olmayan SEÇİLİ kimlik: küresel yönetici için silinmiş takım,
  // salt okunur (kapsamlı) görüntüleyici için bilinmeyen (kapsamı dışında olabilir) — asla çıplak "#12"
  const missingLabel = useCallback((id, ordinal, count) => {
    const base = locked ? 'or.adm.teamUnknown' : 'or.adm.teamMissing'
    return count > 1 ? t(`${base}N`, ordinal) : t(base)
  }, [locked, t])
  const options = useMemo(() => teamOptions(saved?.teams || [], draft?.team_ids || [], '', missingLabel),
    [saved, draft, missingLabel])

  const setField = (k, v) => {
    setDraft((d) => ({ ...d, [k]: v }))
    if (fieldError?.field === k) setFieldError(null)
  }

  async function save() {
    if (!saved || !draft || saving || locked || !dirty) return
    setSaving(true)
    setFieldError(null)
    // Kayıttan ÖNCE başlamış bir okuma kayıttan SONRA gelirse eski değerleri "kayıtlı" göstermesin
    loadSeq.current += 1
    setLoading(false)
    const sent = body
    try {
      const res = await api.admin.oncallRoutines.saveSettings(sent)
      if (!alive.current) return
      if (!res || res.success === false) {
        if (isUnsupported(res)) { toast.error(t('or.adm.saveUnsupported')); return }
        const f = fieldOf(res)
        if (f) { setFieldError({ field: f, message: res.error || t('or.adm.saveError') }); return }
        toast.error(res?.error || t('or.adm.saveError'), { details: res?.errorInfo })
        return
      }
      // Sunucu güncel değerleri döndürdüyse onlar, yoksa gönderilen değişiklikler kayıtlıya işlenir
      const back = res.data && typeof res.data === 'object' && res.data.values ? normalizeSettings(res.data, readOnly) : null
      const nextValues = back ? back.values : { ...saved.values, ...sent, team_ids: normalizeIds(sent.team_ids ?? saved.values.team_ids) }
      setSaved((s) => ({ ...(back || s), values: nextValues, teams: back?.teams?.length ? back.teams : s.teams }))
      setDraft(nextValues)
      toast.success(t('or.adm.saved'))
      // Görünür takımlar değiştiyse özetteki "modül kapalı" rozetleri tazelensin
      if (sent.team_ids) loadOverview()
    } catch (e) {
      if (alive.current) toast.error(e?.message || t('or.adm.saveError'))
    } finally {
      if (alive.current) setSaving(false)
    }
  }

  async function openRetention() {
    if (dirty && !(await showConfirm({
      title: t('or.adm.leaveTitle'), message: t('or.adm.leaveBody'), variant: 'warning',
      confirmText: t('or.adm.leaveConfirm'), cancelText: t('or.adm.cancel'),
    }))) return
    applyTabView('settings', { sec: 'retention', ret_focus: ORS_RETENTION_POLICY })
  }

  const rows = overview.rows
  const asCards = listWidth > 0 && listWidth < ORS_TABLE_MIN_PX

  return (
    <div data-slot="ors-root" data-testid="oncallroutine-settings" className="flex min-w-0 w-full flex-col gap-5">
      <SettingsSection title={t('or.adm.title')} description={t('or.adm.desc')} contentClassName="flex flex-col gap-4">
        {unsupported && (
          <div data-slot="ors-unsupported">
            <StatusBlock tone="neutral" icon={ListTodo} title={t('or.adm.unsupportedTitle')} description={t('or.adm.unsupported')} className="py-8" />
          </div>
        )}
        {!unsupported && loadError && !saved && (
          <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('or.adm.unreadableTitle')} description={String(loadError)}
            className="py-8"
            actions={(
              <Button type="button" variant="outline" onClick={loadSettings} disabled={loading || saving} className={TOUCH} data-slot="ors-retry">
                <RefreshCw aria-hidden="true" /> {t('or.adm.retry')}
              </Button>
            )} />
        )}
        {!unsupported && !saved && !loadError && <Loading t={t} />}
        {!unsupported && saved && draft && (
          <>
            {loadError && (
              <AlertBanner tone="danger" title={t('or.adm.refreshFailed')} className="mb-0"
                actions={(
                  <Button type="button" variant="outline" size="sm" className={cn('h-9 sm:h-8', TOUCH)} onClick={loadSettings}
                    disabled={loading || saving} data-slot="ors-refresh-retry"><RefreshCw aria-hidden="true" />{t('or.adm.retry')}</Button>
                )}>
                {String(loadError)}
              </AlertBanner>
            )}
            {locked && (
              <div data-slot="ors-readonly">
                <AlertBanner tone="info" role="status" className="mb-0">{t('or.adm.readOnly')}</AlertBanner>
              </div>
            )}
            <div data-touch-picker="" className="min-w-0" data-slot="ors-teams-field">
              <Field label={t('or.adm.visibility')} hint={t('or.adm.visibilityHint')} name="team_ids" className="mb-0"
                error={fieldError?.field === 'team_ids' ? fieldError.message : undefined}>
                {({ id }) => (
                  <MultiTeamSelect id={id} wrap value={draft.team_ids} options={options} disabled={locked || saving}
                    ariaLabel={t('or.adm.visibility')} placeholder={t('or.adm.selectTeams')}
                    onChange={(ids) => setField('team_ids', normalizeIds(ids))} />
                )}
              </Field>
            </div>
            {draft.team_ids.length === 0 && (
              <p className="m-0 flex items-start gap-2 text-sm text-muted-foreground" data-slot="ors-everyone">
                <Users aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                <span>{t('or.adm.everyone')}</span>
              </p>
            )}
            <section className="flex min-w-0 flex-col gap-3" aria-labelledby={notifyTitleId} data-slot="ors-switches">
              <h5 id={notifyTitleId} className="m-0 text-sm font-semibold">{t('or.adm.notifyTitle')}</h5>
              {ORS_SWITCHES.map((k) => {
                const unknown = draft[k] == null
                return (
                  <div key={k} className="flex min-w-0 flex-col gap-1" data-slot={SW_SLOT[k]} data-on={draft[k] === true ? 'true' : 'false'}
                    data-unknown={unknown ? 'true' : undefined}>
                    <ToggleRow checked={draft[k] === true} onChange={(v) => setField(k, !!v)} disabled={locked || saving || unknown}
                      label={t(`or.adm.sw.${k}`)} touch className="min-w-0 max-w-full" />
                    <p className="m-0 pl-10 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                      {unknown ? t('or.adm.swUnknown') : t(`or.adm.sw.${k}Hint`)}
                    </p>
                    {fieldError?.field === k && (
                      <p className="m-0 pl-10 text-xs text-destructive [overflow-wrap:anywhere]" role="alert">{fieldError.message}</p>
                    )}
                  </div>
                )
              })}
            </section>
            <div className="flex min-w-0 flex-wrap items-start gap-2 text-sm text-muted-foreground" data-slot="ors-retention">
              <Archive aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">
                {saved.retentionDays != null ? t('or.adm.retention', saved.retentionDays) : t('or.adm.retentionUnknown')}
              </span>
              {!locked && (
                <Button type="button" variant="link" className={cn('h-auto p-0', TOUCH)} onClick={openRetention} data-slot="ors-retention-link">
                  {t('or.adm.retentionOpen')} <ExternalLink aria-hidden="true" className="size-3.5" />
                </Button>
              )}
            </div>
            {!locked && (
              <SettingsSaveBar dirty={dirty} saving={saving} onSave={save}
                onDiscard={() => { setDraft(saved.values); setFieldError(null) }} />
            )}
          </>
        )}
      </SettingsSection>

      <SettingsSection title={t('or.adm.teamsTitle')} description={t('or.adm.teamsDesc')} contentClassName="flex flex-col gap-3">
        <div ref={listRef} className="min-w-0" data-slot="ors-teams" data-layout={asCards ? 'cards' : 'table'} data-status={overview.status}>
          {overview.status === 'unsupported' && (
            <StatusBlock tone="neutral" icon={ListTodo} title={t('or.adm.unsupportedTitle')} description={t('or.adm.overviewUnsupported')} className="py-8" />
          )}
          {overview.status === 'loading' && <Loading t={t} />}
          {overview.status === 'failed' && !rows && (
            <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('or.adm.overviewUnreadable')} description={String(overview.error || '')}
              className="py-8"
              actions={(
                <Button type="button" variant="outline" onClick={loadOverview} className={TOUCH} data-slot="ors-teams-retry">
                  <RefreshCw aria-hidden="true" /> {t('or.adm.retry')}
                </Button>
              )} />
          )}
          {rows && overview.status === 'failed' && (
            <AlertBanner tone="danger" title={t('or.adm.refreshFailed')} className="mb-3"
              actions={(
                <Button type="button" variant="outline" size="sm" className={cn('h-9 sm:h-8', TOUCH)} onClick={loadOverview}
                  data-slot="ors-teams-refresh-retry"><RefreshCw aria-hidden="true" />{t('or.adm.retry')}</Button>
              )}>
              {String(overview.error || '')}
            </AlertBanner>
          )}
          {rows && rows.length === 0 && (
            <div data-slot="ors-teams-empty">
              <StatusBlock tone="neutral" icon={ListTodo} title={t('or.adm.noTeams')} className="py-8" />
            </div>
          )}
          {rows && rows.length > 0 && !asCards && (
            <Table className="table-fixed" data-slot="ors-teams-table">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[30%] whitespace-normal">{t('or.adm.col.team')}</TableHead>
                  <TableHead className="w-[16%] whitespace-normal">{t('or.adm.col.routine')}</TableHead>
                  <TableHead className="w-[14%] whitespace-normal">{t('or.adm.col.tasks')}</TableHead>
                  <TableHead className="w-[18%] whitespace-normal">{t('or.adm.col.today')}</TableHead>
                  <TableHead className="w-[22%] whitespace-normal">{t('or.adm.col.last30')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={r.id} data-slot="ors-team-row" data-team={r.id}>
                    <TableCell className="align-top whitespace-normal"><TeamName row={r} t={t} /></TableCell>
                    <TableCell className="align-top whitespace-normal"><StateBadge state={r.state} t={t} /></TableCell>
                    <TableCell className="align-top whitespace-normal tabular-nums" data-slot="ors-tasks">{r.tasks ?? '—'}</TableCell>
                    <TableCell className="align-top whitespace-normal">{r.state === 'none' ? '—' : <TodayBadge row={r} t={t} />}</TableCell>
                    <TableCell className="align-top whitespace-normal">{r.state === 'none' ? '—' : <Completion row={r} t={t} />}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {rows && rows.length > 0 && asCards && (
            <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="ors-teams-cards">
              {rows.map((r) => (
                <li key={r.id} data-slot="ors-team-row" data-team={r.id}>
                  <Card className="gap-0 py-0">
                    <CardContent className="@container flex min-w-0 flex-col gap-2 p-3">
                      <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
                        <TeamName row={r} t={t} />
                        <StateBadge state={r.state} t={t} />
                      </div>
                      {r.state !== 'none' && (
                        <dl className="m-0 grid grid-cols-1 gap-2 text-sm @md:grid-cols-3">
                          <div className="flex min-w-0 flex-col gap-0.5">
                            <dt className="text-xs text-muted-foreground">{t('or.adm.col.tasks')}</dt>
                            <dd className="m-0 tabular-nums" data-slot="ors-tasks">{r.tasks ?? '—'}</dd>
                          </div>
                          <div className="flex min-w-0 flex-col gap-0.5">
                            <dt className="text-xs text-muted-foreground">{t('or.adm.col.today')}</dt>
                            <dd className="m-0"><TodayBadge row={r} t={t} /></dd>
                          </div>
                          <div className="flex min-w-0 flex-col gap-0.5">
                            <dt className="text-xs text-muted-foreground">{t('or.adm.col.last30')}</dt>
                            <dd className="m-0"><Completion row={r} t={t} /></dd>
                          </div>
                        </dl>
                      )}
                    </CardContent>
                  </Card>
                </li>
              ))}
            </ul>
          )}
        </div>
      </SettingsSection>
    </div>
  )
}
