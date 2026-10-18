import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  CheckCircle2, ChevronDown, CircleAlert, Download, ExternalLink, EyeOff, FileClock, FileSpreadsheet, FileText, FileWarning, Landmark,
  MailWarning, Radio, RefreshCw, Search, Users, X,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import ToneBadge from './ToneBadge.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { navigateTo } from '../../utils/navigate.js'
import { unwrap } from '../noc/nocModel.js'
import { SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import TechErrorReminderSettings from './TechErrorReminderSettings.jsx'
import TechErrorPushSettings from './TechErrorPushSettings.jsx'
import { reminderState } from './techErrorReminderModel.js'
import { pushState } from './techErrorPushModel.js'
import { isDirty, normalizeIds, teamOptions } from './noc/operatorTeamsModel.js'
import { StatusBadge, periodLabel } from '../techerror/TechErrorParts.jsx'
import { TE_CHANNEL, TE_VIEW, VIEW_CHANNEL } from '../techerror/techErrorModel.js'
import {
  TES_FLAG_FILTERS, TES_PERIOD_FILTERS, TES_SORTS, TES_TABLE_MIN_PX, channelPeriodStatus, countChannels, exportFileName, exportTable,
  countOrNull, filterChannels, filtersActive, hasExecutives, missingTeamLabelFor, missingTeamOrdinals, normalizeFilter, normalizeSort, onlyDeletedTeams, searchChannels,
  sortChannels, teamEmailState, teamVisibleState, teamsOf,
} from './techErrorSettingsModel.js'
import { downloadBlob, downloadCsv, toCsv } from '../../utils/csvExport.js'
import { clientFailure } from '../../utils/errorMessages.js'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'

const ALL = 'all'
const TOUCH = 'pointer-coarse:min-h-10'

/**
 * Ayarlar → Platform → Teknik hata raporu (2026-10-17, S6; yeniden tasarım aynı gün — kullanıcı isteği: "shadcn ile
 * yeniden tasarla, mweb duyarlı, mevcut işlevler korunsun").
 *
 * İşlevler (sunucu sözleşmesi DEĞİŞMEDİ — `GET/PUT /api/admin/tech-error-reports/settings`):
 *  - modülü gören takımlar (boş = herkes; GLOBAL_ONLY — kapsamlı müdür `read_only` ile salt okunur görür, kayıt çubuğu yok);
 *  - kanalların onay akışı özeti: yönetici alıcı sayısı, PO onaylayıcı sayısı, takım e-postası, modül görünürlüğü, dönem
 *    raporunun durumu + onay durumu. Yönetici alıcıları kanalın kendi iletişim kutusundan girilir; burası yalnız gösterir.
 *
 * Yeni: dönem KPI kartları (süzgeç), araç çubuğu (arama — Türkçe katlama, takım, durum, sıralama, temizle), kap genişliğine
 * göre TABLO (≥ {@link TES_TABLE_MIN_PX}) ya da KART, sayfalama (`panel` ön ayarı), "Kanalı aç" → kanal sayfası. Yükleme
 * iskeletle, okuma hatası tehlike bloğu + Tekrar dene (boş "kanal yok" durumu YALNIZ başarılı yanıtta). Saf mantık
 * `techErrorSettingsModel.js`. Test kancaları `tes-*`.
 *
 * Hatırlatmalar ve posta sınırları kartı (12. sürüm) AYRI uçtan okunur / yazılır (`GET/PUT …/settings/reminders`, aynı
 * okuma dalgasında): eski pod'da 404 / 405 → kart "henüz desteklenmiyor" der; görünürlük kaydı (`PUT …/settings`) 11.
 * sürümle aynı kalır. Kart kayıt sürerken Yenile kapalıdır ve kayıt başında okuma sırası ilerler (geç gelen eski okuma düşer).
 *
 * Dışa aktarma (2026-10-17, E22): araç çubuğundaki "Dışa aktar" süzülmüş + sıralanmış listenin TÜMÜNÜ (bütün sayfalar) CSV ya
 * da Excel (.xlsx) olarak indirir; değerler ekrandaki metinler, bilinmeyen "Bilinmiyor" (`exportTable`).
 */
export default function TechErrorReportSettings({ readOnly = false }) {
  const t = useT()
  const toast = useToast()
  const [settings, setSettings] = useState(null)
  const [draft, setDraft] = useState([])
  const [directory, setDirectory] = useState([])
  const [loadError, setLoadError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [q, setQ] = useState('')
  const [team, setTeam] = useState(ALL)
  const [filter, setFilter] = useState(ALL)
  const [sort, setSort] = useState('name')
  const [remDirty, setRemDirty] = useState(false)
  // Hatırlatma kartı (12. sürüm): AYRI uçtan okunur (`reminderState`: ok / unsupported / failed; null = kart yok)
  const [rem, setRem] = useState(null)
  const [remSaving, setRemSaving] = useState(false)
  // Push bildirimleri kartı (2026-10-18): AYRI uçtan okunur (`pushState`: ok / unsupported / failed; null = kart yok)
  const [pushSt, setPushSt] = useState(null)
  const [pushSaving, setPushSaving] = useState(false)
  const [pushDirty, setPushDirty] = useState(false)
  const [listRef, listWidth] = useElementWidth()
  const alive = useRef(true)
  const loadSeq = useRef(0)
  // Yenile kaydedilmemiş seçimi silmesin (inceleme 2026-10-17): okuma yanıtı geldiğinde taslak kirliyse korunur
  const settingsRef = useRef(null)
  settingsRef.current = settings
  const { showConfirm } = useDialog()

  const load = useCallback(async () => {
    const id = ++loadSeq.current
    setLoadError(null)
    setLoading(true)
    try {
      const [cfg, dir, remRes, pushRes] = await Promise.all([
        api.admin.techErrorSettings(),
        Promise.resolve(api.teams?.directory ? api.teams.directory() : null).catch(() => null),
        // Ağ hatası "okunamadı"dır (eski pod'un 404 / 405'i değil): değer uydurulmaz, son okunan korunur
        api.admin.techErrorReminderSettings
          ? Promise.resolve(api.admin.techErrorReminderSettings()).catch((e) => ({ success: false, error: e?.message || null }))
          : Promise.resolve(undefined),
        api.admin.techErrorPushSettings
          ? Promise.resolve(api.admin.techErrorPushSettings()).catch((e) => ({ success: false, error: e?.message || null }))
          : Promise.resolve(undefined),
      ])
      if (!alive.current || id !== loadSeq.current) return
      if (remRes !== undefined) setRem((prev) => reminderState(remRes, prev))
      if (pushRes !== undefined) setPushSt((prev) => pushState(pushRes, prev))
      const r = unwrap(cfg)
      if (!r.ok) { setLoadError(r.error || t('te.set.loadError')); return }
      const data = r.data || {}
      const ids = normalizeIds(data.team_ids)
      const prev = settingsRef.current
      setSettings({ ...data, ids, readOnly: readOnly || data.read_only === true })
      setDraft((cur) => (prev && isDirty(prev.ids, cur) ? cur : ids))
      const d = unwrap(dir)
      setDirectory(d.ok && Array.isArray(d.data) ? d.data : [])
    } catch (e) {
      if (alive.current && id === loadSeq.current) setLoadError(e?.message || t('te.set.loadError'))
    } finally {
      if (alive.current && id === loadSeq.current) setLoading(false)
    }
  }, [readOnly, t])
  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Takım dizini okunamazsa adlar kanal satırlarından gelir ("#12" yerine takım adı)
  const teamList = useMemo(() => (directory.length ? directory : teamsFromChannels(settings?.channels)), [directory, settings])
  // Rehberde olmayan SEÇİLİ kimlik (inceleme 2026-10-18): rehber okunduysa takım silinmiştir ("Silinmiş takım"); okunamadıysa
  // bilinmez ("Takım adı okunamadı") — asla çıplak "#12". Sunucu silinmiş kimliği kayıtta sessizce düşürür. Numara SAYFA
  // genelinde tek haritadan (2. tur): kayıtlı görünürlük + hatırlatma listelerinin birleşimi, kimliğe göre artan — iki seçicide
  // aynı takım aynı numarayı taşır, çip kaldırılınca numaralar kaymaz
  const missingOrdinals = useMemo(() => missingTeamOrdinals([settings?.ids, rem?.data?.team_ids], teamList, [draft]),
    [settings, rem, teamList, draft])
  const missingTeamLabel = useMemo(() => missingTeamLabelFor(missingOrdinals, directory.length > 0, t), [missingOrdinals, directory, t])
  // Kayıtlı kısıt yalnız silinmiş takımlardan oluşuyor (inceleme 2026-10-18): modülü şu an yalnız kurum geneli görüntüleyiciler görür
  const visibilityOnlyDeleted = !!settings && onlyDeletedTeams(settings.ids, directory)
  const options = useMemo(() => teamOptions(teamList, draft, t('noc.ot.inactiveTeam'), missingTeamLabel),
    [teamList, draft, t, missingTeamLabel])
  const dirty = !!settings && isDirty(settings.ids, draft)
  const locked = !!settings?.readOnly

  // Hatırlatma kartının kaydı (inceleme 2026-10-18): görünürlük kaydıyla AYNI kural — kayıt sürerken Yenile kapalı, kayıt
  // başında okuma sırası ilerler (kayıttan ÖNCE başlamış bir okuma kayıttan SONRA gelirse eski değerleri "kayıtlı"
  // göstermesin ve temiz formu ezmesin)
  const onRemSaving = useCallback((on) => {
    if (!alive.current) return
    if (on) {
      loadSeq.current += 1
      setLoading(false)
    }
    setRemSaving(!!on)
  }, [])
  const onRemSaved = useCallback((next) => {
    if (alive.current) setRem({ status: 'ok', data: next, error: null })
  }, [])
  const onRemUnsupported = useCallback(() => {
    if (alive.current) setRem((prev) => ({ status: 'unsupported', data: prev?.data || null, error: null }))
  }, [])
  // Push kartının kaydı: hatırlatma kartıyla aynı eşgüdüm (Yenile kapalı, kayıt başında okuma sırası ilerler)
  const onPushSaving = useCallback((on) => {
    if (!alive.current) return
    if (on) {
      loadSeq.current += 1
      setLoading(false)
    }
    setPushSaving(!!on)
  }, [])
  const onPushSaved = useCallback((next) => {
    if (alive.current) setPushSt({ status: 'ok', data: next, error: null })
  }, [])
  const onPushUnsupported = useCallback(() => {
    if (alive.current) setPushSt((prev) => ({ status: 'unsupported', data: prev?.data || null, error: null }))
  }, [])

  async function save() {
    if (!settings || saving || locked) return
    setSaving(true)
    // Kayıttan ÖNCE başlamış bir okuma kayıttan SONRA gelirse eski seçimi "kayıtlı" gösterirdi → o yanıt düşer
    loadSeq.current += 1
    setLoading(false)
    try {
      const r = unwrap(await api.admin.saveTechErrorSettings({ team_ids: normalizeIds(draft) }))
      if (!alive.current) return
      if (!r.ok) { toast.error(r.error || t('te.set.saveError'), { details: r.errorInfo }); return }
      const ids = normalizeIds(r.data?.team_ids ?? draft)
      // Görünürlük rozetleri ve "modül kapalı" sayısı kaydedilen seçimi izler (sunucu satırları eski ayara göre hesaplamıştı)
      setSettings((s) => ({ ...s, ids, channels: withVisibility(s?.channels, ids) }))
      setDraft(ids)
      toast.success(t('te.set.saved'))
    } catch (e) {
      toast.error(e?.message || t('te.set.saveError'))
    } finally {
      if (alive.current) setSaving(false)
    }
  }

  const channels = useMemo(() => (Array.isArray(settings?.channels) ? settings.channels : []), [settings])
  const period = settings?.period
  const pLabel = periodLabel(period, t)
  const teams = useMemo(() => teamsOf(channels), [channels])
  const teamSelectOptions = useMemo(() => [{ value: ALL, label: t('te.allTeams') }, ...teams.map((tm) => ({ value: tm.id, label: tm.name }))], [teams, t])
  // KPI sayımları arama + takım süzgecinin ÜZERİNDEN (kart süzgecinin kendisi hariç) — kartlar "bu listede kaç tane" der
  const searched = useMemo(() => searchChannels(channels, { q, team }), [channels, q, team])
  const counts = useMemo(() => countChannels(searched), [searched])
  const visible = useMemo(() => sortChannels(filterChannels(channels, { q, team, filter }), sort), [channels, q, team, filter, sort])
  const pager = usePagination(visible, { listKey: 'tes-channels', preset: 'panel', resetDeps: [q, team, filter, sort] })
  const cardsLayout = listWidth > 0 && listWidth < TES_TABLE_MIN_PX
  const anyFilter = filtersActive({ q, team, filter })

  function clearFilters() {
    setQ('')
    setTeam(ALL)
    setFilter(ALL)
  }

  async function openChannel(ch) {
    if (dirty || remDirty || pushDirty) {
      const ok = await showConfirm({ title: t('te.set.leaveTitle'), message: t(remDirty || pushDirty ? 'te.set.leaveBodyAny' : 'te.set.leaveBody'), variant: 'warning',
        confirmText: t('te.set.leaveConfirm'), cancelText: t('te.cancel') })
      if (!ok) return
    }
    navigateTo('channelreports', { [TE_VIEW]: VIEW_CHANNEL, [TE_CHANNEL]: String(ch.id) })
  }
  // Salt okunur (kapsamlı) görüntüleyici modülü kapalı bir takımın kanalını açamaz (sayfa "erişim yok" derdi)
  const openFor = (ch) => (locked && ch.team_visible === false ? null : () => openChannel(ch))

  // Dışa aktarma (2026-10-17, E22): süzülmüş + sıralanmış listenin TÜMÜ (sayfalamadan bağımsız), ekrandaki metinlerle
  const [exporting, setExporting] = useState(false)
  function exportCsv() {
    const { header, rows } = exportTable(visible, t)
    downloadCsv(exportFileName(period, 'csv'), toCsv(header, rows))
  }
  async function exportXlsx() {
    if (exporting) return
    setExporting(true)
    try {
      const { header, rows } = exportTable(visible, t)
      const { buildXlsx, XLSX_MIME } = await loadXlsxWriter()
      const bytes = buildXlsx([{ name: t('te.set.export.sheet'), rows: [header, ...rows], freezeHeader: true, autoFilter: true,
        widths: [42, 34, 9, 18, 28, 14, 14, 34, 34] }])
      downloadBlob(exportFileName(period, 'xlsx'), new Blob([bytes], { type: XLSX_MIME }))
    } catch (e) {
      // API dışı hata (bayat paket "Failed to fetch dynamically imported module", bellek, çalışma kitabı): tostta yalnız
      // çevrilmiş metin, teknik metin "Teknik ayrıntı"da (hata sözleşmesi)
      const f = clientFailure(e, { fallback: t('te.set.export.unavailable'), chunk: t('te.pageUpdated') })
      toast.error(f.message, { details: f.details })
    } finally {
      if (alive.current) setExporting(false)
    }
  }

  const kpiItems = channels.length === 0 ? [] : [
    { key: ALL, Icon: Radio, label: t('te.set.kpi.total'), value: counts.total, cls: 'total', hint: t('te.set.kpi.hint.total') },
    { key: 'sent', Icon: CheckCircle2, label: t('te.kpi.sent', pLabel), value: counts.sent, cls: 'valid', hint: t('te.kpi.hint.sent') },
    { key: 'open', Icon: FileClock, label: t('te.kpi.open'), value: counts.open, cls: 'warning', hint: t('te.kpi.hint.open') },
    { key: 'missing', Icon: FileWarning, label: t('te.kpi.missing'), value: counts.missing, cls: 'alert', hint: t('te.kpi.hint.missing') },
    { key: 'exec', Icon: Landmark, label: t('te.set.kpi.exec'), value: counts.exec, cls: 'weak', hint: t('te.set.kpi.hint.exec') },
    { key: 'noEmail', Icon: MailWarning, label: t('te.set.kpi.noEmail'), value: counts.noEmail, cls: 'error', hint: t('te.set.kpi.hint.noEmail') },
    { key: 'hidden', Icon: EyeOff, label: t('te.set.kpi.hidden'), value: counts.hidden, cls: 'paused', hint: t('te.set.kpi.hint.hidden') },
  ]

  const refreshButton = settings && (
    <Button type="button" variant="outline" size="sm" onClick={load} disabled={loading || saving || remSaving || pushSaving} data-slot="tes-refresh" className={cn('h-9 sm:h-8', TOUCH)}>
      <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} /> {t('te.refresh')}
    </Button>
  )

  return (
    <div data-slot="tes-root" data-testid="techerror-settings" className="flex min-w-0 w-full flex-col gap-5">
      {/* 1 — Modül görünürlüğü (ayar) */}
      <SettingsSection title={t('te.set.title')} description={t('te.set.desc')} contentClassName="flex flex-col gap-4">
        {loadError && !settings && (
          <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('te.set.unreadableTitle')} description={String(loadError)}
            className="py-8"
            actions={<Button type="button" variant="outline" onClick={load} disabled={loading || saving || remSaving || pushSaving} className={TOUCH} data-slot="tes-retry"><RefreshCw aria-hidden="true" /> {t('te.retry')}</Button>} />
        )}
        {!settings && !loadError && (
          <div role="status" aria-live="polite" className="flex flex-col gap-3" data-slot="tes-skeleton">
            <span className="sr-only">{t('settings.loading')}</span>
            <Skeleton className="h-4 w-40 motion-reduce:animate-none" />
            <Skeleton className="h-10 w-full motion-reduce:animate-none" />
            <Skeleton className="h-4 w-3/4 motion-reduce:animate-none" />
          </div>
        )}
        {settings && (
          <>
            {loadError && (
              <AlertBanner tone="danger" title={t('te.refreshFailed')} className="mb-0"
                actions={<Button type="button" variant="outline" size="sm" className={cn('h-9 sm:h-8', TOUCH)} onClick={load}
                  disabled={loading || saving || remSaving || pushSaving} data-slot="tes-refresh-retry"><RefreshCw aria-hidden="true" />{t('te.retry')}</Button>}>
                {String(loadError)}
              </AlertBanner>
            )}
            {locked && (
              <div data-slot="tes-readonly">
                <AlertBanner tone="info" role="status" className="mb-0">{t('te.set.readOnly')}</AlertBanner>
              </div>
            )}
            <div data-touch-picker="" className="min-w-0">
              <Field label={t('te.set.visibility')} hint={t('te.set.visibilityHint')} name="team_ids" className="mb-0">
                {({ id }) => (
                  <MultiTeamSelect id={id} value={draft} options={options} disabled={locked} ariaLabel={t('te.set.visibility')}
                    placeholder={t('te.set.selectTeams')} onChange={(ids) => setDraft(normalizeIds(ids))} />
                )}
              </Field>
            </div>
            {visibilityOnlyDeleted && (
              <div data-slot="tes-only-deleted">
                <AlertBanner tone="warning" className="mb-0" title={t('te.set.onlyDeletedTitle')}>{t('te.set.onlyDeleted')}</AlertBanner>
              </div>
            )}
            {draft.length === 0 && (
              <p className="m-0 flex items-start gap-2 text-sm text-muted-foreground" data-slot="tes-everyone">
                <Users aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                <span>{t('te.set.everyone')}</span>
              </p>
            )}
            {!locked && (
              <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => setDraft(settings.ids)} />
            )}
          </>
        )}
      </SettingsSection>

      {/* 2 — Hatırlatmalar ve posta sınırları (12. sürüm) */}
      {settings && rem && (
        <TechErrorReminderSettings state={rem} teams={teamList} readOnly={locked} onDirtyChange={setRemDirty}
          onSaving={onRemSaving} onSaved={onRemSaved} onUnsupported={onRemUnsupported} onRetry={load}
          retryDisabled={loading || saving || remSaving || pushSaving} missingTeamLabel={missingTeamLabel} />
      )}

      {/* 3 — Push bildirimleri (2026-10-18) */}
      {settings && pushSt && (
        <TechErrorPushSettings state={pushSt} readOnly={locked} onDirtyChange={setPushDirty}
          onSaving={onPushSaving} onSaved={onPushSaved} onUnsupported={onPushUnsupported} onRetry={load}
          retryDisabled={loading || saving || remSaving || pushSaving} />
      )}

      {/* 4 — Kanallar ve onay akışı (özet) */}
      {settings && (
        <Card data-slot="tes-channels">
          <CardContent className="flex flex-col gap-4 px-4 sm:px-6">
            <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
              <div className="min-w-0">
                <h4 id="tes-channels-title" className="m-0 flex items-center gap-2 text-base font-semibold">
                  <FileWarning aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /> {t('te.set.channels')}
                </h4>
                <p className="m-0 mt-1 text-sm text-muted-foreground">{t('te.set.channelsHint')}</p>
              </div>
              <div className="flex shrink-0 flex-wrap items-center gap-2">{refreshButton}</div>
            </div>

            {kpiItems.length > 0 && (
              <div data-slot="tes-kpis">
                <MonitorStatsBar items={kpiItems} activeFilter={filter} className="mb-0"
                  onStatClick={(key) => setFilter(key === filter || key === ALL ? ALL : key)} />
              </div>
            )}

            {channels.length > 0 && (
              <Card data-slot="tes-toolbar" className="py-3 shadow-none">
                <CardContent className="flex flex-col gap-3 px-3 sm:px-4">
                  <div className="flex flex-wrap items-end gap-3">
                    <InputGroup className="w-full sm:w-72 pointer-coarse:h-10">
                      <InputGroupInput type="search" value={q} onChange={(e) => setQ(e.target.value)} data-page-search=""
                        placeholder={t('te.set.search')} aria-label={t('te.set.search')} data-slot="tes-search" className="pointer-coarse:h-10" />
                      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                    </InputGroup>
                    {teams.length > 1 && (
                      <div className="flex min-w-0 flex-col gap-1.5 sm:w-72" data-touch-picker="" data-slot="tes-team">
                        <span id="tes-team-label" className="text-xs font-medium text-muted-foreground">{t('te.teamFilter')}</span>
                        <SearchableSelect ariaLabelledBy="tes-team-label" value={team} options={teamSelectOptions} wrapLabels
                          onChange={(v) => setTeam(v == null || v === ALL ? ALL : String(v))} />
                      </div>
                    )}
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <label htmlFor="tes-status" className="text-xs font-medium text-muted-foreground">{t('te.set.filterLabel')}</label>
                      <NativeSelect id="tes-status" value={filter} onChange={(e) => setFilter(normalizeFilter(e.target.value))}
                        className="pointer-coarse:h-10" data-slot="tes-status">
                        <NativeSelectOption value={ALL}>{t('te.set.filter.all')}</NativeSelectOption>
                        <NativeSelectOptGroup label={t('te.set.filterGroup.period')}>
                          {TES_PERIOD_FILTERS.map((f) => <NativeSelectOption key={f} value={f}>{t(`te.set.filter.${f}`)}</NativeSelectOption>)}
                        </NativeSelectOptGroup>
                        <NativeSelectOptGroup label={t('te.set.filterGroup.config')}>
                          {TES_FLAG_FILTERS.map((f) => <NativeSelectOption key={f} value={f}>{t(`te.set.filter.${f}`)}</NativeSelectOption>)}
                        </NativeSelectOptGroup>
                      </NativeSelect>
                    </div>
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <label htmlFor="tes-sort" className="text-xs font-medium text-muted-foreground">{t('te.sort.label')}</label>
                      <NativeSelect id="tes-sort" value={sort} onChange={(e) => setSort(normalizeSort(e.target.value))}
                        className="pointer-coarse:h-10" data-slot="tes-sort">
                        {TES_SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`te.sort.${s}`)}</NativeSelectOption>)}
                      </NativeSelect>
                    </div>
                    {anyFilter && (
                      <Button type="button" variant="ghost" onClick={clearFilters} className={TOUCH} data-slot="tes-clear">
                        <X aria-hidden="true" /> {t('te.filters.clear')}
                      </Button>
                    )}
                    <ExportMenu disabled={visible.length === 0 || exporting} onCsv={exportCsv} onXlsx={exportXlsx} onOpen={preloadXlsxWriter} />
                  </div>
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                    <span data-slot="tes-count">{t('te.filters.count', visible.length, channels.length)}</span>
                    {period && <span data-slot="tes-period">{t('te.periodNote', pLabel)}</span>}
                  </div>
                </CardContent>
              </Card>
            )}

            {channels.length === 0 ? (
              <div data-slot="tes-empty"><StatusBlock tone="neutral" icon={Radio} title={t('te.set.noChannels')} className="py-8" /></div>
            ) : (
              <div ref={listRef} className="flex min-w-0 flex-col gap-3" data-slot="tes-list" data-layout={cardsLayout ? 'cards' : 'table'}>
                {visible.length === 0 ? (
                  <StatusBlock tone="neutral" icon={Search} title={t('te.noMatchTitle')} description={t('te.noMatchBody')} className="py-8"
                    actions={<Button type="button" variant="outline" onClick={clearFilters} className={TOUCH}><X aria-hidden="true" /> {t('te.filters.clear')}</Button>} />
                ) : cardsLayout ? (
                  // Kart düzeni yalnız dar kapta (< TES_TABLE_MIN_PX) — tek sütun: 768 px tablette kap ≈ 350 px, iki sütun
                  // uzun takım adını 160 px'e sıkıştırıyordu (görünüm alanı değil KAP dar)
                  <div className="grid grid-cols-1 gap-3">
                    {pager.pageItems.map((ch) => <ChannelCard key={ch.id} ch={ch} period={period} onOpen={openFor(ch)} />)}
                  </div>
                ) : (
                  <div className="overflow-hidden rounded-xl border" data-slot="tes-table">
                    {/* Dört sütun — Ayarlar içeriği dar (bkz. TES_TABLE_MIN_PX): kanal adı kanalı AÇAN bağlantı, takım durumu
                        (görünürlük + e-posta) ve onay akışı (yönetici + PO) rozetleri alt alta */}
                    <Table className="table-fixed">
                      <TableHeader>
                        <TableRow>
                          <TableHead className="whitespace-normal">{t('te.col.channel')}</TableHead>
                          <TableHead className="w-28 whitespace-normal">{t('te.set.col.teamState')}</TableHead>
                          <TableHead className="w-32 whitespace-normal">{t('te.set.col.flow')}</TableHead>
                          <TableHead className="w-36 whitespace-normal">{t('te.col.periodReport', pLabel)}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {pager.pageItems.map((ch) => <ChannelRow key={ch.id} ch={ch} period={period} onOpen={openFor(ch)} />)}
                      </TableBody>
                    </Table>
                  </div>
                )}
                {pager.totalPages > 1 && <PaginationBar {...pager} />}
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}

/** "Dışa aktar" menüsü: CSV ve Excel (.xlsx) — araç çubuğunun sonunda (geniş ekranda sağa yaslı). */
/**
 * Excel yazıcısı (fflate) TEMBEL yüklenir; menü AÇILIRKEN önceden istenir (2026-10-18, CI bulgusu): parça tıklamadan sonra
 * yüklenirse indirme kullanıcı eyleminden (Chrome ~5 sn) kopar ve aynı sayfadaki İKİNCİ indirmeyi Chrome sessizce engeller
 * (yavaş ağda gerçek kullanıcı da yaşar). Söz paylaşılır; başarısız yükleme saklanmaz (bir sonraki tıklama yeniden dener).
 */
let xlsxWriterPromise = null
function loadXlsxWriter() {
  if (!xlsxWriterPromise) {
    xlsxWriterPromise = import('../../utils/xlsxWriter.js').catch((e) => { xlsxWriterPromise = null; throw e })
  }
  return xlsxWriterPromise
}
function preloadXlsxWriter() {
  loadXlsxWriter().catch(() => { /* tıklamada yeniden denenir ve hata orada söylenir */ })
}

function ExportMenu({ disabled, onCsv, onXlsx, onOpen }) {
  const t = useT()
  return (
    <DropdownMenu modal={false} onOpenChange={(open) => { if (open) onOpen?.() }}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" disabled={disabled} data-slot="tes-export" className={cn('sm:ms-auto', TOUCH)}>
          <Download aria-hidden="true" /> {t('te.set.export.open')} <ChevronDown aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="z-(--z-menu) max-w-[calc(100vw-2rem)]" data-slot="tes-export-menu">
        <DropdownMenuItem onSelect={onCsv} className={TOUCH} data-slot="tes-export-csv">
          <FileText aria-hidden="true" /> {t('te.set.export.csv')}
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={onXlsx} className={TOUCH} data-slot="tes-export-xlsx">
          <FileSpreadsheet aria-hidden="true" /> {t('te.set.export.xlsx')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

const CHIP = 'rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]'

/** Ortak satır / kart öznitelikleri: akış (onaylı / takım), dönem durumu, kanal kimliği. */
function rowAttrs(ch) {
  return {
    'data-channel': ch.id,
    'data-flow': hasExecutives(ch) ? 'approval' : 'team',
    'data-status': channelPeriodStatus(ch),
    'data-active': ch.active ? 'true' : 'false',
  }
}

/**
 * Kanal adı = kanalı açan bağlantı (kanal listesi sayfasıyla aynı kalıp); altında takım rozeti (+ pasif çipi). Dokunmatikte
 * aralık 12 px: rozetin `::after` vuruş alanı (burada 12 px) ad düğmesinin üstüne binmesin (inceleme 2026-10-17).
 */
function ChannelName({ ch, onOpen }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-1.5 pointer-coarse:gap-3">
      {onOpen ? (
        <Button type="button" variant="link" onClick={onOpen} data-slot="tes-open" aria-label={t('te.set.openChannelFor', ch.name)}
          className={cn('h-auto justify-start p-0 text-left text-base font-semibold leading-snug whitespace-normal text-foreground [overflow-wrap:anywhere]', TOUCH)}>
          <span data-slot="tes-channel-name" className="[overflow-wrap:anywhere]">{ch.name}</span>
        </Button>
      ) : (
        <span data-slot="tes-channel-name" className="text-base font-semibold leading-snug [overflow-wrap:anywhere]">{ch.name}</span>
      )}
      <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <TeamBadge teamId={ch.team_id} teamName={ch.team_name} wrap className="pointer-coarse:after:-inset-y-3" />
        {!ch.active && <Badge variant="outline" className={CHIP}>{t('te.inactive')}</Badge>}
      </div>
    </div>
  )
}

/** Modül görünürlüğü — yalnız açık `false` "kapalı"; alan yoksa "bilinmiyor" (yeşil değil). */
function VisibilityBadge({ ch }) {
  const t = useT()
  const v = teamVisibleState(ch)
  if (v === true) return <ToneBadge tone="success" className={CHIP} data-slot="tes-visibility" data-state="visible">{t('te.set.visible')}</ToneBadge>
  if (v === false) return <ToneBadge tone="warning" className={CHIP} data-slot="tes-visibility" data-state="hidden">{t('te.set.teamHidden')}</ToneBadge>
  return <ToneBadge tone="muted" className={CHIP} data-slot="tes-visibility" data-state="unknown">{t('te.set.visibilityUnknown')}</ToneBadge>
}

/**
 * Onay akışı: yönetici alıcı sayısı + PO onaylayıcı sayısı (metinler eski ekranla aynı). Okunamayan / gelmeyen sayı "—"
 * (inceleme 2026-10-18: bilinmeyen sayı "PO yok" / "onay akışı yok" diyordu — bilinmiyor ≠ 0).
 */
function FlowBadges({ ch }) {
  const t = useT()
  const exec = countOrNull(ch.executive_count)
  const po = countOrNull(ch.approver_count)
  const label = (n, col, none) => (n == null ? `${t(col)}: —` : n > 0 ? `${t(col)}: ${n}` : t(none))
  return (
    <div className="flex flex-wrap gap-1.5" data-slot="tes-flow">
      <Badge variant={exec > 0 ? 'secondary' : 'outline'} className={CHIP} data-slot="tes-exec" data-state={exec == null ? 'unknown' : undefined}>
        {label(exec, 'te.set.col.exec', 'te.set.noFlow')}
      </Badge>
      <Badge variant={po > 0 ? 'secondary' : 'outline'} className={CHIP} data-slot="tes-approvers" data-state={po == null ? 'unknown' : undefined}>
        {label(po, 'te.set.col.approvers', 'te.set.noApprover')}
      </Badge>
    </div>
  )
}

/** Takım e-postası — `true` tanımlı, `false` eksik (rapor gönderilemez), bilinmiyor gri. */
function EmailBadge({ ch }) {
  const t = useT()
  const s = teamEmailState(ch)
  if (s === true) return <ToneBadge tone="success" className={CHIP} data-slot="tes-email" data-state="set">{t('te.set.emailSet')}</ToneBadge>
  if (s === false) return <ToneBadge tone="danger" className={CHIP} data-slot="tes-email" data-state="missing">{t('te.set.emailMissing')}</ToneBadge>
  return <ToneBadge tone="muted" className={CHIP} data-slot="tes-email" data-state="unknown">{t('te.set.emailUnknown')}</ToneBadge>
}

/** Dönem raporu durumu (+ onay durumu) — rapor yoksa "Dönem raporu yok" (yeşil değil). */
function ReportBadges({ ch, period, withPrefix = false }) {
  const t = useT()
  const pr = ch.period_report
  const appr = pr?.approval?.state
  return (
    <div className="flex flex-wrap gap-1.5" data-slot="tes-report">
      {pr ? (
        <StatusBadge status={pr.status || null} sendState={pr.last_send_status} prefix={withPrefix && period ? periodLabel(period, t) : undefined} />
      ) : (
        <Badge variant="outline" className={CHIP}>{t('te.set.noReport')}</Badge>
      )}
      {appr && appr !== 'none' && (
        <Badge variant="outline" className={CHIP} data-slot="tes-approval" data-state={appr}>{t(`te.appr.state.${appr}`)}</Badge>
      )}
    </div>
  )
}

/** Takım durumu: modül görünürlüğü + takım e-postası alt alta (tablo sütunu / kart satırı). */
function TeamStateBadges({ ch }) {
  return (
    <div className="flex flex-wrap gap-1.5" data-slot="tes-team-state">
      <VisibilityBadge ch={ch} />
      <EmailBadge ch={ch} />
    </div>
  )
}

function ChannelRow({ ch, period, onOpen }) {
  return (
    <TableRow data-slot="tes-channel" {...rowAttrs(ch)} className={cn(!ch.active && 'bg-muted/30')}>
      <TableCell className="whitespace-normal align-top"><ChannelName ch={ch} onOpen={onOpen} /></TableCell>
      <TableCell className="whitespace-normal align-top"><TeamStateBadges ch={ch} /></TableCell>
      <TableCell className="whitespace-normal align-top"><FlowBadges ch={ch} /></TableCell>
      <TableCell className="whitespace-normal align-top"><ReportBadges ch={ch} period={period} /></TableCell>
    </TableRow>
  )
}

function ChannelCard({ ch, period, onOpen }) {
  const t = useT()
  return (
    <Card data-slot="tes-channel" {...rowAttrs(ch)} className={cn('gap-3 py-4', !ch.active && 'border-dashed')}>
      <CardContent className="flex flex-col gap-3 px-4">
        <ChannelName ch={ch} onOpen={onOpen} />
        <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-2 text-sm">
          <dt className="text-xs font-medium text-muted-foreground">{t('te.set.col.teamState')}</dt>
          <dd className="m-0"><TeamStateBadges ch={ch} /></dd>
          <dt className="text-xs font-medium text-muted-foreground">{t('te.set.col.flow')}</dt>
          <dd className="m-0"><FlowBadges ch={ch} /></dd>
          <dt className="text-xs font-medium text-muted-foreground">{t('te.col.periodReport', periodLabel(period, t))}</dt>
          <dd className="m-0"><ReportBadges ch={ch} period={period} /></dd>
        </dl>
        {onOpen ? (
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" variant="outline" onClick={onOpen} data-slot="tes-open-btn" className={cn(TOUCH, 'whitespace-normal')}>
              <ExternalLink aria-hidden="true" /> {t('te.set.openChannel')}
            </Button>
          </div>
        ) : (
          <p className="m-0 text-xs text-muted-foreground" data-slot="tes-open-hidden">{t('te.set.openHiddenTeam')}</p>
        )}
      </CardContent>
    </Card>
  )
}

/** Kaydedilen seçime göre `team_visible` (boş seçim = herkes); `team_id`'si olmayan satır dokunulmadan kalır. */
export function withVisibility(channels, ids) {
  if (!Array.isArray(channels)) return channels
  const set = new Set((ids || []).map(String))
  return channels.map((c) => (c?.team_id == null ? c : { ...c, team_visible: set.size === 0 || set.has(String(c.team_id)) }))
}

/** Dizin okunamazsa seçicinin takım adları kanal satırlarından (id → ad). */
export function teamsFromChannels(channels) {
  const seen = new Map()
  for (const c of Array.isArray(channels) ? channels : []) {
    if (c?.team_id == null || seen.has(String(c.team_id))) continue
    seen.set(String(c.team_id), { id: c.team_id, name: c.team_name || `#${c.team_id}` })
  }
  return [...seen.values()]
}
