import { useId, useMemo, useState } from 'react'
import {
  ArrowLeftRight, Ban, ChevronRight, CircleAlert, CircleCheck, CircleDashed, CircleSlash, CircleX, Download, FlaskConical, Hourglass,
  ListChecks, Play, RefreshCw, RotateCw, Search, Shuffle, TrendingDown, TrendingUp, X,
} from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { ProgressBar, Spinner } from '../../ui/Progress.jsx'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { SettingsSaveBar } from '../SettingsControls.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import {
  ALL, DIFFS, DIFF_TONE, RESULTS_TABLE_MIN_PX, diffTileTone, SCAN_STATE_TONE, STATUS_TONE, diffCounts, durationParts, filterResults,
  filtersActive, fmtUtc, isOpenScan, normalizeIds, pickableMonitors, resultClasses, resultTeams, scanProgress, sortResults,
} from './k6EngineModel.js'
import { CHIP, SectionCard, TOUCH } from './K6EngineParts.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/** Fark türü → kutucuk ikonu. */
const DIFF_ICON = {
  REGRESSION: TrendingDown, FLAKY: Shuffle, CHANGED: ArrowLeftRight, NEW_FAIL: CircleAlert, SLOWER: Hourglass, IMPROVED: TrendingUp,
  SAME_FAIL: CircleX, SAME_PASS: CircleCheck, NO_BASELINE: CircleDashed, NOT_RUN: CircleSlash,
}

/** Seçici listesinde aynı anda çizilen en çok izleme (2000 izlemelik envanterde sayfa ağırlaşmasın — arama daraltır). */
const PICK_LIMIT = 100

function Dur({ ms, lang }) {
  const t = useT()
  const p = durationParts(ms, lang)
  return p ? t(p.key, ...p.params) : '—'
}

// ── Tarama kartı ────────────────────────────────────────────────────────────────────────────────

/**
 * Uyumluluk taraması: açıklama, tarama dışı izlemeler (aranabilir çoklu seçim + kendi kayıt çubuğu), "Taramayı başlat"
 * (onaylı), sürerken ilerleme + iptal.
 */
export function ScanCard({
  data, lang, exDraft, exDirty, exSaving, exFe, onExChange, onExSave, onExDiscard, targetCount, live, busy, onStart, onCancel,
}) {
  const t = useT()
  const [q, setQ] = useState('')
  const searchId = useId()
  const monitors = useMemo(() => (Array.isArray(data?.monitors) ? data.monitors : []), [data])
  const byId = useMemo(() => new Map(monitors.map((m) => [Number(m.id), m])), [monitors])
  const draft = normalizeIds(exDraft)
  const draftSet = new Set(draft)
  const pickable = useMemo(() => pickableMonitors(monitors, q), [monitors, q])
  const shown = pickable.slice(0, PICK_LIMIT)
  const running = isOpenScan(live)
  const candidate = data?.engines?.candidate
  const canStart = !!candidate?.available && !running && !busy && !exDirty && targetCount > 0
  const toggle = (id, on) => onExChange(on ? [...draft, id] : draft.filter((x) => x !== id))

  return (
    <SectionCard slot="k6e-scan" icon={FlaskConical} title={t('k6e.scan.title')} description={t('k6e.scan.desc')}>
      <ul className="m-0 flex list-disc flex-col gap-1 ps-5 text-sm text-muted-foreground" data-slot="k6e-scan-rules">
        <li>{t('k6e.scan.rule1')}</li>
        <li>{t('k6e.scan.rule2')}</li>
        <li>{t('k6e.scan.rule3')}</li>
      </ul>

      {/* Tarama dışı izlemeler */}
      <div className="flex min-w-0 flex-col gap-3 rounded-lg border px-3 py-3 sm:px-4" data-slot="k6e-excluded" data-field="excluded_ids">
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-sm font-semibold">{t('k6e.ex.title')}</span>
          <span className="text-xs text-muted-foreground">{t('k6e.ex.hint')}</span>
        </div>
        {draft.length > 0 ? (
          <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0" data-slot="k6e-ex-chips">
            {draft.map((id) => {
              const m = byId.get(id)
              const name = m ? m.name : t('k6e.ex.missing', id)
              return (
                <li key={id} data-slot="k6e-ex-chip" data-id={id}
                  className="inline-flex min-w-0 max-w-full items-center gap-1 rounded-md border bg-muted/50 py-0.5 ps-2 pe-0.5 text-sm">
                  <span className="min-w-0 [overflow-wrap:anywhere]">
                    {name}{m && m.active !== true && <span className="text-muted-foreground"> · {t('k6e.ex.inactive')}</span>}
                  </span>
                  <Button type="button" variant="ghost" size="icon-xs" onClick={() => toggle(id, false)} disabled={exSaving}
                    aria-label={t('k6e.ex.remove', m ? m.name : id)} className="shrink-0 pointer-coarse:size-10" data-slot="k6e-ex-remove">
                    <X aria-hidden="true" />
                  </Button>
                </li>
              )
            })}
          </ul>
        ) : (
          <p className="m-0 text-sm text-muted-foreground" data-slot="k6e-ex-none">{t('k6e.ex.none')}</p>
        )}
        <InputGroup className="w-full sm:max-w-md pointer-coarse:h-10">
          <InputGroupInput id={searchId} type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('k6e.ex.search')}
            aria-label={t('k6e.ex.search')} autoComplete="off" data-slot="k6e-ex-search" className="pointer-coarse:h-10" />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        </InputGroup>
        {pickable.length === 0 ? (
          <p className="m-0 text-sm text-muted-foreground" data-slot="k6e-ex-nomatch">{t('k6e.ex.noMatch')}</p>
        ) : (
          <ul className="m-0 flex max-h-72 list-none flex-col gap-0.5 overflow-y-auto rounded-md border p-1" data-slot="k6e-ex-list"
            aria-label={t('k6e.ex.title')}>
            {shown.map((m) => (
              <ExRow key={m.id} m={m} checked={draftSet.has(Number(m.id))} disabled={exSaving} onChange={(on) => toggle(Number(m.id), on)} />
            ))}
          </ul>
        )}
        {pickable.length > PICK_LIMIT && (
          <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-ex-more">{t('k6e.ex.more', PICK_LIMIT, pickable.length)}</p>
        )}
        {exFe.errors.excluded_ids && <p className="m-0 text-xs text-destructive" data-slot="k6e-ex-error">{exFe.errors.excluded_ids}</p>}
        <SettingsSaveBar dirty={exDirty} saving={exSaving} onSave={onExSave} onDiscard={onExDiscard} saveLabel={t('k6e.ex.save')} />
      </div>

      {/* Başlat / ilerleme */}
      {running ? (
        <RunningPanel live={live} lang={lang} busy={busy} onCancel={onCancel} />
      ) : (
        <div className="flex min-w-0 flex-col gap-2" data-slot="k6e-scan-start-row">
          <p className="m-0 text-sm" data-slot="k6e-scan-target">{t('k6e.scan.target', targetCount)}</p>
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" onClick={onStart} disabled={!canStart} aria-busy={busy === 'start' || undefined}
              className={cn(TOUCH, 'whitespace-normal')} data-slot="k6e-scan-start">
              {busy === 'start' ? <Spinner size={15} inline decorative /> : <Play aria-hidden="true" />} {t('k6e.scan.start')}
            </Button>
          </div>
          {!candidate?.available && <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-scan-why">{t('k6e.scan.noCandidate')}</p>}
          {candidate?.available && exDirty && <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-scan-why">{t('k6e.scan.saveExFirst')}</p>}
          {candidate?.available && !exDirty && targetCount === 0 && (
            <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-scan-why">{t('k6e.scan.noTargets')}</p>
          )}
        </div>
      )}
    </SectionCard>
  )
}

function ExRow({ m, checked, disabled, onChange }) {
  const t = useT()
  const id = useId()
  return (
    <li data-slot="k6e-ex-item" data-id={m.id} data-checked={checked ? 'true' : 'false'}
      className="flex min-w-0 items-start gap-2.5 rounded-md px-2 py-1.5 hover:bg-muted/50">
      <Checkbox id={id} checked={checked} onCheckedChange={(v) => onChange(v === true)} disabled={disabled} className="mt-0.5" />
      <Label htmlFor={id} className="flex min-w-0 cursor-pointer flex-col items-start gap-0 font-normal pointer-coarse:min-h-10">
        <span className="[overflow-wrap:anywhere]">{m.name}</span>
        <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{m.team_name || t('k6e.noTeam')}</span>
      </Label>
    </li>
  )
}

function RunningPanel({ live, lang, busy, onCancel }) {
  const t = useT()
  const p = scanProgress(live)
  const cancelling = live?.state === 'CANCELLING'
  return (
    <div className="flex min-w-0 flex-col gap-3 rounded-lg border border-primary/40 bg-primary/5 px-3 py-3 sm:px-4"
      data-slot="k6e-scan-running" data-state={live?.state} role="status" aria-live="polite">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <Spinner size={15} inline decorative />
        <span className="font-semibold">{t(cancelling ? 'k6e.scan.cancelling' : 'k6e.scan.running', live?.id)}</span>
      </div>
      <ProgressBar value={p.pct ?? 0} max={100} decorative size="md" />
      <p className="m-0 text-sm" data-slot="k6e-scan-progress">{t('k6e.scan.progress', p.done, p.total)}</p>
      <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="k6e-scan-meta">
        {t('k6e.scan.startedBy', live?.started_by_name || '—', fmtUtc(live?.started_at, lang), live?.owner_pod || '—')}
      </p>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" onClick={onCancel} disabled={cancelling || !!busy} aria-busy={busy === 'cancel' || undefined}
          className={TOUCH} data-slot="k6e-scan-cancel">
          {busy === 'cancel' ? <Spinner size={15} inline decorative /> : <Ban aria-hidden="true" />} {t('k6e.scan.cancel')}
        </Button>
      </div>
    </div>
  )
}

// ── Sonuçlar ────────────────────────────────────────────────────────────────────────────────────

/**
 * Tarama sonuçları: geçmişten tarama seçimi (en yeni varsayılan), durum + sebep, fark kutucukları (tık = süzgeç), arama /
 * takım / fark / hata sınıfı süzgeçleri, kap genişliğine göre tablo (≥ {@link RESULTS_TABLE_MIN_PX}) ya da kart, satır
 * başına "Yeniden tara", süzülmüş satırların CSV'si. Okuma hatası tehlike bloğu + Tekrar dene ("sonuç yok" değil).
 */
export function ResultsCard({
  lang, scans, selectedId, onSelect, detail, onRetryScans, onRetryDetail, scanRunning, busy, onRescan, onExport,
}) {
  const t = useT()
  const [q, setQ] = useState('')
  const [team, setTeam] = useState(ALL)
  const [diff, setDiff] = useState(ALL)
  const [cls, setCls] = useState(ALL)
  const [listRef, listWidth] = useElementWidth()
  const cards = listWidth > 0 && listWidth < RESULTS_TABLE_MIN_PX
  const scan = detail.data && detail.data.id === selectedId ? detail.data : null
  const rows = useMemo(() => sortResults(scan?.results), [scan])
  const visible = useMemo(() => filterResults(rows, { q, team, diff, cls }), [rows, q, team, diff, cls])
  const teams = useMemo(() => resultTeams(rows), [rows])
  const classes = useMemo(() => resultClasses(rows), [rows])
  const counts = useMemo(() => diffCounts(scan), [scan])
  const any = filtersActive({ q, team, diff, cls })
  const clear = () => { setQ(''); setTeam(ALL); setDiff(ALL); setCls(ALL) }
  const list = scans.list || []

  const refresh = (
    <Button type="button" variant="outline" onClick={onRetryScans} disabled={scans.status === 'loading' || scans.status === 'refreshing'}
      className={TOUCH} data-slot="k6e-results-refresh">
      <RefreshCw aria-hidden="true" className={cn(scans.status === 'refreshing' && 'animate-spin motion-reduce:animate-none')} /> {t('k6e.refresh')}
    </Button>
  )
  const exportBtn = scan && rows.length > 0 && (
    <Button type="button" variant="outline" onClick={() => onExport(visible, scan)} disabled={visible.length === 0}
      className={TOUCH} data-slot="k6e-results-csv">
      <Download aria-hidden="true" /> {t('k6e.res.csv')}
    </Button>
  )

  let body
  if (scans.status === 'loading' && !list.length) {
    body = <ResultsSkeleton t={t} />
  } else if (scans.status === 'failed' && !list.length) {
    body = (
      <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('k6e.res.scansUnreadable')}
        description={scans.error ? String(scans.error) : t('k6e.res.scansUnreadableBody')} details={scans.res || undefined} className="py-8"
        actions={<Button type="button" variant="outline" onClick={onRetryScans} className={TOUCH} data-slot="k6e-results-retry"><RefreshCw aria-hidden="true" /> {t('k6e.retry')}</Button>} />
    )
  } else if (!list.length) {
    body = <div data-slot="k6e-results-empty"><StatusBlock tone="neutral" icon={ListChecks} title={t('k6e.res.noScan')} description={t('k6e.res.noScanBody')} className="py-8" /></div>
  } else {
    body = (
      <div className="flex min-w-0 flex-col gap-4">
        {scans.status === 'failed' && (
          <AlertBanner tone="danger" className="mb-0" title={t('k6e.res.scansRefreshFailed')} details={scans.res || undefined}>
            {scans.error ? String(scans.error) : t('k6e.res.scansUnreadableBody')}
          </AlertBanner>
        )}
        {/* Tarama seçici SARAR (E10, 2026-10-31): yerel <select> kapalıyken seçili metni telefonda kırpıyordu — uzun seçenek
            ("#12 · tarih · durum · aday sürüm · kapsam") SearchableSelect `wrapLabels` ile tam okunur. */}
        <div className="flex min-w-0 flex-col gap-1.5 sm:max-w-xl" data-slot="k6e-scan-pick">
          <Label htmlFor="k6e-scan-pick" className="text-xs font-medium text-muted-foreground">{t('k6e.res.pick')}</Label>
          <SearchableSelect id="k6e-scan-pick" wrapLabels searchThreshold={8}
            value={selectedId == null ? '' : String(selectedId)} onChange={(v) => onSelect(Number(v))}
            options={list.map((s) => ({
              value: String(s.id),
              label: t('k6e.res.pickOption', s.id, fmtUtc(s.started_at, lang), t(`k6e.scanState.${s.state}`), s.candidate_version || '—',
                t(`k6e.scope.${s.scope === 'SELECTED' ? 'SELECTED' : 'ALL'}`)),
            }))} />
        </div>
        <DetailBody t={t} lang={lang} detail={detail} scan={scan} onRetry={onRetryDetail} counts={counts} diff={diff} setDiff={setDiff}
          rows={rows} visible={visible} q={q} setQ={setQ} team={team} setTeam={setTeam} cls={cls} setCls={setCls} teams={teams}
          classes={classes} any={any} clear={clear} listRef={listRef} cards={cards} scanRunning={scanRunning} busy={busy} onRescan={onRescan} />
      </div>
    )
  }

  return (
    <SectionCard slot="k6e-results" icon={ListChecks} title={t('k6e.res.title')} description={t('k6e.res.desc')}
      actions={<>{exportBtn}{list.length > 0 || scans.status === 'failed' ? refresh : null}</>}>
      {body}
    </SectionCard>
  )
}

function ResultsSkeleton({ t }) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-3" data-slot="k6e-results-skeleton">
      <span className="sr-only">{t('k6e.loading')}</span>
      <Skeleton className="h-10 w-full max-w-xl motion-reduce:animate-none" />
      <Skeleton className="h-20 w-full motion-reduce:animate-none" />
      <Skeleton className="h-40 w-full motion-reduce:animate-none" />
    </div>
  )
}

function DetailBody({
  t, lang, detail, scan, onRetry, counts, diff, setDiff, rows, visible, q, setQ, team, setTeam, cls, setCls, teams, classes, any, clear,
  listRef, cards, scanRunning, busy, onRescan,
}) {
  if (!scan && detail.status === 'failed') {
    return (
      <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('k6e.res.detailUnreadable')}
        description={detail.error ? String(detail.error) : t('k6e.res.detailUnreadableBody')} details={detail.res || undefined} className="py-8"
        actions={<Button type="button" variant="outline" onClick={onRetry} className={TOUCH} data-slot="k6e-detail-retry"><RefreshCw aria-hidden="true" /> {t('k6e.retry')}</Button>} />
    )
  }
  if (!scan) return <ResultsSkeleton t={t} />
  const open = isOpenScan(scan)
  const p = scanProgress(scan)
  const tiles = DIFFS.map((d) => ({
    // Sıfır değerli kutucuk NÖTR (E10, 2026-10-31): "0 Dalgalı" amber boyanınca bir sorun varmış gibi okunuyordu
    key: d, Icon: DIFF_ICON[d], label: t(`k6e.diff.${d}`), value: counts[d], cls: diffTileTone(d, counts[d]), hint: t(`k6e.diffHint.${d}`),
  }))
  return (
    <div className="flex min-w-0 flex-col gap-4" data-slot="k6e-detail" data-scan={scan.id} data-state={scan.state}>
      {detail.status === 'failed' && (
        <AlertBanner tone="warning" className="mb-0" title={t('k6e.res.detailRefreshFailed')}>
          {detail.error ? String(detail.error) : t('k6e.res.detailUnreadableBody')}
        </AlertBanner>
      )}
      <div className="flex min-w-0 flex-col gap-1.5">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <ToneBadge tone={SCAN_STATE_TONE[scan.state] || 'muted'} className={CHIP} data-slot="k6e-detail-state" data-state={scan.state}>
            {t(`k6e.scanState.${scan.state}`)}
          </ToneBadge>
          <Badge variant="outline" className={CHIP}>{t(`k6e.scope.${scan.scope === 'SELECTED' ? 'SELECTED' : 'ALL'}`)}</Badge>
          <Badge variant="outline" className={cn(CHIP, 'font-mono')} data-slot="k6e-detail-versions">
            {t('k6e.res.versions', scan.active_version || '—', scan.candidate_version || '—')}
          </Badge>
        </div>
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="k6e-detail-meta">
          {t('k6e.scan.startedBy', scan.started_by_name || '—', fmtUtc(scan.started_at, lang), scan.owner_pod || '—')}
          {scan.finished_at ? ` · ${t('k6e.res.finishedAt', fmtUtc(scan.finished_at, lang))}` : ''}
          {' · '}{t('k6e.scan.progress', p.done, p.total)}
        </p>
        {scan.failure && (
          <div data-slot="k6e-detail-failure">
            <AlertBanner tone={scan.state === 'FAILED' ? 'danger' : 'warning'} className="mb-0">{String(scan.failure)}</AlertBanner>
          </div>
        )}
        {open && <p className="m-0 text-xs text-muted-foreground" data-slot="k6e-detail-live">{t('k6e.res.live')}</p>}
      </div>

      <div data-slot="k6e-kpis">
        <MonitorStatsBar items={tiles} activeFilter={diff === ALL ? null : diff} className="mb-0" dense
          onStatClick={(key) => setDiff(key === diff ? ALL : key)} />
      </div>

      {rows.length > 0 && (
        <Card className="py-3 shadow-none" data-slot="k6e-filters">
          <CardContent className="flex flex-col gap-3 px-3 sm:px-4">
            <div className="flex flex-wrap items-end gap-3">
              <InputGroup className="w-full sm:w-64 pointer-coarse:h-10">
                <InputGroupInput type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('k6e.res.search')}
                  aria-label={t('k6e.res.search')} autoComplete="off" data-page-search="" data-slot="k6e-res-search" className="pointer-coarse:h-10" />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              </InputGroup>
              {teams.length > 1 && (
                <FilterSelect id="k6e-f-team" label={t('k6e.res.team')} value={team} onChange={setTeam} slot="k6e-res-team">
                  <NativeSelectOption value={ALL}>{t('k6e.res.allTeams')}</NativeSelectOption>
                  {teams.map((tm) => <NativeSelectOption key={tm.value} value={tm.value}>{tm.none ? t('k6e.noTeam') : (tm.name || t('k6e.teamUnnamed'))}</NativeSelectOption>)}
                </FilterSelect>
              )}
              <FilterSelect id="k6e-f-diff" label={t('k6e.res.diff')} value={diff} onChange={setDiff} slot="k6e-res-diff">
                <NativeSelectOption value={ALL}>{t('k6e.res.allDiffs')}</NativeSelectOption>
                {DIFFS.map((d) => <NativeSelectOption key={d} value={d}>{t(`k6e.diff.${d}`)}</NativeSelectOption>)}
              </FilterSelect>
              {classes.length > 0 && (
                <FilterSelect id="k6e-f-cls" label={t('k6e.res.cls')} value={cls} onChange={setCls} slot="k6e-res-cls">
                  <NativeSelectOption value={ALL}>{t('k6e.res.allClasses')}</NativeSelectOption>
                  {classes.map((c) => <NativeSelectOption key={c} value={c}>{t(`k6e.cls.${c}`)}</NativeSelectOption>)}
                </FilterSelect>
              )}
              {any && (
                <Button type="button" variant="ghost" onClick={clear} className={TOUCH} data-slot="k6e-res-clear">
                  <X aria-hidden="true" /> {t('k6e.res.clear')}
                </Button>
              )}
            </div>
            <p className="m-0 text-sm text-muted-foreground" data-slot="k6e-res-count">{t('k6e.res.count', visible.length, rows.length)}</p>
          </CardContent>
        </Card>
      )}

      <div ref={listRef} className="flex min-w-0 flex-col gap-3" data-slot="k6e-res-list" data-layout={cards ? 'cards' : 'table'}>
        {rows.length === 0 ? (
          <StatusBlock tone="neutral" icon={ListChecks} title={open ? t('k6e.res.waitingFirst') : t('k6e.res.noRows')} className="py-8" />
        ) : visible.length === 0 ? (
          <StatusBlock tone="neutral" icon={Search} title={t('k6e.res.noMatch')} className="py-8"
            actions={<Button type="button" variant="outline" onClick={clear} className={TOUCH}><X aria-hidden="true" /> {t('k6e.res.clear')}</Button>} />
        ) : cards ? (
          <div className="grid grid-cols-1 gap-3">
            {visible.map((r) => <ResultCard key={r.monitor_id} r={r} lang={lang} scanRunning={scanRunning} busy={busy} onRescan={onRescan} />)}
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border" data-slot="k6e-res-table">
            <Table className="table-fixed">
              <TableHeader>
                <TableRow>
                  {/* Dört sütun — Ayarlar içeriği dar (bkz. RESULTS_TABLE_MIN_PX): fark rozeti izleme hücresinin başında */}
                  <TableHead className="whitespace-normal">{t('k6e.col.monitor')}</TableHead>
                  <TableHead className="w-32 whitespace-normal">{t('k6e.col.baseline')}</TableHead>
                  <TableHead className="w-32 whitespace-normal">{t('k6e.col.candidate')}</TableHead>
                  <TableHead className="whitespace-normal">{t('k6e.col.problem')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {visible.map((r) => <ResultRow key={r.monitor_id} r={r} lang={lang} scanRunning={scanRunning} busy={busy} onRescan={onRescan} />)}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
    </div>
  )
}

function FilterSelect({ id, label, value, onChange, slot, children }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Label htmlFor={id} className="text-xs font-medium text-muted-foreground">{label}</Label>
      <NativeSelect id={id} value={value} onChange={(e) => onChange(e.target.value)} className="h-10 sm:h-9 pointer-coarse:h-10" data-slot={slot}>
        {children}
      </NativeSelect>
    </div>
  )
}

function DiffBadge({ diff }) {
  const t = useT()
  const d = diff || 'NOT_RUN'
  return <ToneBadge tone={DIFF_TONE[d] || 'muted'} className={CHIP} data-slot="k6e-diff" data-diff={d}>{t(`k6e.diff.${d}`)}</ToneBadge>
}

function StatusBadge({ status, slot }) {
  const t = useT()
  if (!status) return <ToneBadge tone="muted" className={CHIP} data-slot={slot} data-status="none">{t('k6e.st.none')}</ToneBadge>
  return <ToneBadge tone={STATUS_TONE[status] || 'muted'} className={CHIP} data-slot={slot} data-status={status}>{t(`k6e.st.${status}`)}</ToneBadge>
}

function Baseline({ r, lang }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col items-start gap-1 text-xs text-muted-foreground" data-slot="k6e-baseline">
      <StatusBadge status={r.baseline_status} slot="k6e-baseline-status" />
      {r.baseline_status && (
        <>
          <span className="[overflow-wrap:anywhere]">{t('k6e.res.k6', r.baseline_k6_version || '—')}</span>
          <span>{fmtUtc(r.baseline_checked_at, lang)}</span>
          <span><Dur ms={r.baseline_duration_ms} lang={lang} /></span>
        </>
      )}
    </div>
  )
}

function Candidate({ r, lang }) {
  const t = useT()
  const archiveNote = r.archive && !['OK', 'NOT_RUN'].includes(r.archive)
  return (
    <div className="flex min-w-0 flex-col items-start gap-1 text-xs text-muted-foreground" data-slot="k6e-candidate">
      <StatusBadge status={r.status} slot="k6e-candidate-status" />
      {r.exit_code != null && <span>{t('k6e.res.exit', r.exit_code)}</span>}
      <span><Dur ms={r.duration_ms} lang={lang} /></span>
      {r.retried === true && <span data-slot="k6e-retried">{t('k6e.res.retried')}</span>}
      {r.via_proxy === true && <span>{t('k6e.res.viaProxy')}</span>}
      {archiveNote && <span data-slot="k6e-archive" data-archive={r.archive}>{t(`k6e.archive.${r.archive}`)}</span>}
    </div>
  )
}

function ProblemCell({ r }) {
  const t = useT()
  const hasText = !!(r.error || r.archive_error)
  return (
    <div className="flex min-w-0 flex-col items-start gap-1.5" data-slot="k6e-problem">
      {r.error_class
        ? <Badge variant="outline" className={CHIP} data-slot="k6e-cls" data-cls={r.error_class}>{t(`k6e.cls.${r.error_class}`)}</Badge>
        : !hasText && <span className="text-xs text-muted-foreground">—</span>}
      {hasText && (
        <Collapsible className="w-full min-w-0" data-slot="k6e-problem-text">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="link" size="xs"
              className="group/k6err h-auto min-h-6 px-0 whitespace-normal text-left text-foreground pointer-coarse:min-h-10">
              <ChevronRight aria-hidden="true" className="transition-transform group-data-[state=open]/k6err:rotate-90" />
              <span className="group-data-[state=open]/k6err:hidden">{t('k6e.res.showText')}</span>
              <span className="hidden group-data-[state=open]/k6err:inline">{t('k6e.res.hideText')}</span>
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            {r.error && (
              <pre className="m-0 mt-1 max-h-72 overflow-y-auto rounded-md bg-muted px-2 py-1.5 font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]"
                data-slot="k6e-problem-pre">{r.error}</pre>
            )}
            {r.archive_error && (
              <pre className="m-0 mt-1 max-h-48 overflow-y-auto rounded-md bg-muted px-2 py-1.5 font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]"
                data-slot="k6e-archive-pre">{r.archive_error}</pre>
            )}
          </CollapsibleContent>
        </Collapsible>
      )}
    </div>
  )
}

function RescanButton({ r, scanRunning, busy, onRescan }) {
  const t = useT()
  const mine = busy === `rescan:${r.monitor_id}`
  return (
    <Button type="button" variant="outline" size="sm" onClick={() => onRescan(r)} disabled={scanRunning || !!busy}
      aria-label={t('k6e.res.rescanFor', r.monitor_name || r.monitor_id)} aria-busy={mine || undefined}
      className={cn('h-auto min-h-8 whitespace-normal', TOUCH)} data-slot="k6e-rescan">
      {mine ? <Spinner size={14} inline decorative /> : <RotateCw aria-hidden="true" />} {t('k6e.res.rescan')}
    </Button>
  )
}

function MonitorName({ r }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="font-semibold leading-snug [overflow-wrap:anywhere]" data-slot="k6e-res-name">{r.monitor_name || t('k6e.res.unnamed')}</span>
      <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="k6e-res-team-name">{r.team_name || t('k6e.noTeam')}</span>
    </div>
  )
}

function ResultRow({ r, lang, scanRunning, busy, onRescan }) {
  return (
    <TableRow data-slot="k6e-res-row" data-id={r.monitor_id} data-diff={r.diff || 'NOT_RUN'}>
      <TableCell className="whitespace-normal align-top">
        <div className="flex min-w-0 flex-col items-start gap-2">
          <DiffBadge diff={r.diff} />
          <MonitorName r={r} />
          <RescanButton r={r} scanRunning={scanRunning} busy={busy} onRescan={onRescan} />
        </div>
      </TableCell>
      <TableCell className="whitespace-normal align-top"><Baseline r={r} lang={lang} /></TableCell>
      <TableCell className="whitespace-normal align-top"><Candidate r={r} lang={lang} /></TableCell>
      <TableCell className="whitespace-normal align-top"><ProblemCell r={r} /></TableCell>
    </TableRow>
  )
}

function ResultCard({ r, lang, scanRunning, busy, onRescan }) {
  const t = useT()
  return (
    <Card data-slot="k6e-res-row" data-id={r.monitor_id} data-diff={r.diff || 'NOT_RUN'} className="gap-3 py-4">
      <CardContent className="@container flex min-w-0 flex-col gap-3 px-4">
        <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
          <MonitorName r={r} />
          <DiffBadge diff={r.diff} />
        </div>
        <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-2 text-sm @sm:grid-cols-2">
          <div className="flex min-w-0 flex-col gap-1">
            <dt className="text-xs font-medium text-muted-foreground">{t('k6e.col.baseline')}</dt>
            <dd className="m-0"><Baseline r={r} lang={lang} /></dd>
          </div>
          <div className="flex min-w-0 flex-col gap-1">
            <dt className="text-xs font-medium text-muted-foreground">{t('k6e.col.candidate')}</dt>
            <dd className="m-0"><Candidate r={r} lang={lang} /></dd>
          </div>
          <div className="flex min-w-0 flex-col gap-1 @sm:col-span-2">
            <dt className="text-xs font-medium text-muted-foreground">{t('k6e.col.problem')}</dt>
            <dd className="m-0"><ProblemCell r={r} /></dd>
          </div>
        </dl>
        <div className="flex flex-wrap gap-2">
          <RescanButton r={r} scanRunning={scanRunning} busy={busy} onRescan={onRescan} />
        </div>
      </CardContent>
    </Card>
  )
}
