import { Fragment, useEffect, useId, useMemo, useState } from 'react'
import {
  Search, X, Filter, ChevronDown, Layers, ListChecks, CircleAlert, UserSearch, SlidersHorizontal, CalendarRange,
  Activity, BellRing, Send, PencilLine, Shapes,
} from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { Label } from '@/components/shadcn/label'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import FacetedFilter from '../ui/FacetedFilter.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import { CHECK_FAILURE_CODES } from '../checks/checkFailureCodes.js'
import { KW_FAILURE_CODES } from '../keyword/diagnose/keywordDiagCodes.js'
import { FAILURE_CODES as DBQ_FAILURE_CODES } from '../dbquery/dbqueryPageModel.js'
import { TYPES } from './ActivityParts.jsx'
import MonitorFilterPicker from './MonitorFilterPicker.jsx'
import {
  DEFAULT_FILTERS, ERROR_CLASSES, GROUPS, MAX_REASONS, RANGES, STATUSES, activeChips, customRangeValid, isFiltered, istanbulShort,
  reasonLabel, removeChip,
} from './activityModel.js'

/**
 * Hata nedeni seçenekleri: kontrol kataloğu + yalnız keyword'e özgü kodlar + DB sorgu kodları (`DB_*`, inceleme CT O3 —
 * DB sorgu hataları nedene göre süzülemiyordu; kod listesi sunucu `DbQueryErrors` / `DbQueryCheckerService` ile aynı,
 * kapı activityModelFilters.test) — sıra katalog sırası.
 */
export const REASON_CODES = [...CHECK_FAILURE_CODES, ...KW_FAILURE_CODES.filter((c) => !CHECK_FAILURE_CODES.includes(c)),
  ...DBQ_FAILURE_CODES.filter((c) => !CHECK_FAILURE_CODES.includes(c)),
  // Neden kodu yazılmadan önceki satırlar hata sınıfıyla süzülür (be2: `reason=class:<SINIF>`)
  ...ERROR_CLASSES.map((c) => `class:${c}`)]

const GROUP_ICON = { check: Activity, alarm: BellRing, notification: Send, change: PencilLine, other: Shapes }
const MAX_ACTOR = 100

/**
 * Aktivite süzgeç kartı (2026-11-05, E — 2026-10-11 kartının genişletilmiş hâli). Satır 1: aralık (Bugün / 24 sa / 7 g /
 * Tümü / Özel) + arama + eylemler (`actions`: yenile, dışa aktar, görünümler). Özel aralık: iki `DateTimeField` + Uygula
 * (ters aralık alanın altında söylenir, istek gitmez). Satır 2 (dar kapta "Daha fazla süzgeç" altında katlanır): durum
 * (çoklu — zaman aşımı dahil), hata nedeni, takım, izleme (arama), kişi. Satır 3: olay grubu çipleri (sayılı), satır 4:
 * tür çipleri (sayılı; dar kapta katlanır), satır 5: etkin süzgeç çipleri. Durum çipi YOK (KPI kartı + seçici gösterir).
 *
 * Props: `{ filters, setFilters, typeCounts, groupCounts, teams: {status, list}, compact, actions, rangeError }` —
 * `rangeError`: dışa aktarımın aralık reddi (en çok 90 gün, inceleme be2) — özel aralık alanlarının ALTINDA söylenir.
 * Test kancaları: `data-slot="act-filters"`, `act-group-chips`, `act-type-chips`, `act-types-toggle`, `act-more-toggle`,
 * `act-custom-range`, `act-active-filters`, `act-chip` (+ `data-kind`).
 */
export default function ActivityFilterBar({ filters, setFilters, typeCounts, groupCounts, teams, compact = false, actions, rangeError = null }) {
  const t = useT()
  const { lang } = useLanguage()
  const uid = useId()
  const [qInput, setQInput] = useState(filters.q)
  const [actorInput, setActorInput] = useState(filters.actor)
  const [draft, setDraft] = useState({ from: filters.from, to: filters.to })
  const [typesOpen, setTypesOpen] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)
  const [customOpen, setCustomOpen] = useState(filters.range === 'custom')
  useEffect(() => { if (filters.range === 'custom') setCustomOpen(true) }, [filters.range])
  // Dışa aktarımın aralık reddi alanın altında söylenir — alanlar görünür olsun
  useEffect(() => { if (rangeError) setCustomOpen(true) }, [rangeError])

  // Dış değişim (çip kaldırma, Tümünü temizle, kayıtlı görünüm) girdileri de günceller
  useEffect(() => { setQInput(filters.q) }, [filters.q])
  useEffect(() => { setActorInput(filters.actor) }, [filters.actor])
  useEffect(() => { setDraft({ from: filters.from, to: filters.to }) }, [filters.from, filters.to])

  const applySearch = () => setFilters((f) => ({ ...f, q: qInput.trim() }))
  const applyActor = () => {
    const v = actorInput.trim().slice(0, MAX_ACTOR)
    if (v !== filters.actor) setFilters((f) => ({ ...f, actor: v }))
  }
  const draftValid = customRangeValid(draft.from, draft.to)
  const applyCustom = () => {
    if (!draftValid || (!draft.from && !draft.to)) return
    setFilters((f) => ({ ...f, range: 'custom', from: draft.from, to: draft.to }))
  }
  const changeRange = (rg) => {
    if (rg === 'custom') {
      // Uçlar sonradan seçilir; seçilene dek süzgeç değişmez (boş özel aralık istek göndermez)
      setFilters((f) => (f.from || f.to ? { ...f, range: 'custom' } : f))
      setCustomOpen(true)
      return
    }
    setCustomOpen(false)
    setFilters((f) => ({ ...f, range: rg, from: '', to: '' }))
  }
  const rangeValue = customOpen ? 'custom' : filters.range

  const teamName = (id) => teams?.list?.find((x) => String(x.id) === String(id))?.name || null
  const chips = activeChips(filters)
  const chipLabel = (c) => {
    if (c.kind === 'type') return c.value
    if (c.kind === 'range') {
      if (c.value !== 'custom') return t('act.range.' + c.value)
      return t('act.flt.customChip', filters.from ? istanbulShort(filters.from, lang) : '…', filters.to ? istanbulShort(filters.to, lang) : t('act.flt.now'))
    }
    if (c.kind === 'q') return `“${c.value}”`
    if (c.kind === 'team') return t('act.flt.teamChip', teamName(c.value) || t('act.flt.teamUnknown'))
    if (c.kind === 'monitor') return t('act.flt.monitorChip', filters.monitorLabel || c.value)
    if (c.kind === 'actor') return t('act.flt.actorChip', c.value)
    if (c.kind === 'group') return t('act.grp.' + c.value)
    if (c.kind === 'reason') return t('act.flt.reasonChip', reasonLabel(c.value, t))
    return c.value
  }
  const clearAll = () => { setQInput(''); setActorInput(''); setCustomOpen(false); setFilters({ ...DEFAULT_FILTERS }) }

  const statusOptions = useMemo(() => STATUSES.map((s) => ({ value: s, label: t('act.st.' + s) })), [t])
  const reasonOptions = useMemo(() => REASON_CODES.map((c) => ({ value: c, label: reasonLabel(c, t), hint: c })), [t])
  const teamOptions = useMemo(() => {
    const list = Array.isArray(teams?.list) ? teams.list : []
    const opts = [{ value: '', label: t('act.flt.allTeams') }, ...list.map((x) => ({ value: String(x.id), label: x.name || t('act.flt.teamUnknown') }))]
    if (filters.team && !list.some((x) => String(x.id) === String(filters.team))) opts.push({ value: filters.team, label: t('act.flt.teamUnknown') })
    return opts
  }, [teams, filters.team, t])
  const moreCount = (filters.statuses.length ? 1 : 0) + (filters.reasons.length ? 1 : 0) + (filters.team ? 1 : 0)
    + (filters.monitor ? 1 : 0) + (filters.actor ? 1 : 0)

  /** Olay grubu çipleri — sayı (kendi süzgeci hariç diğer süzgeçlerle) görsel + ekran okuyucuya açıklama. */
  const groupChips = (
    <div className="flex flex-wrap gap-1.5" role="group" aria-label={t('act.flt.groups')} data-slot="act-group-chips">
      {GROUPS.map((g) => {
        const on = filters.groups.includes(g)
        const count = groupCounts ? (groupCounts[g] ?? 0) : null
        const Icon = GROUP_ICON[g]
        const cntId = `${uid}-gcnt-${g}`
        return (
          <Fragment key={g}>
            <Button type="button" variant="outline" size="sm" aria-pressed={on} data-group={g}
              aria-describedby={count != null ? cntId : undefined}
              onClick={() => setFilters((f) => ({ ...f, groups: on ? f.groups.filter((x) => x !== g) : [...f.groups, g] }))}
              className={cn('h-8 gap-1.5 rounded-full px-2.5 text-[0.8em] font-medium pointer-coarse:h-10',
                on ? 'border-primary bg-primary/10 text-primary' : 'text-muted-foreground', count === 0 && !on && 'opacity-60')}>
              <Icon aria-hidden="true" className="size-3.5" /> {t('act.grp.' + g)}
              {count != null && (
                <span aria-hidden="true" data-slot="act-group-count"
                  className="min-w-5 rounded-full bg-muted px-1.5 text-center text-[0.9em] font-semibold text-foreground tabular-nums">{count}</span>
              )}
            </Button>
            {count != null && <span id={cntId} className="sr-only">{t('act.typeCount', count)}</span>}
          </Fragment>
        )
      })}
    </div>
  )

  /** Tür çipleri — sayı (tür süzgeci hariç diğer süzgeçlerle) görsel + ekran okuyucuya açıklama olarak. */
  const typeChips = (
    <div className="flex flex-wrap gap-1.5" role="group" aria-label={t('act.typeFilter')} data-slot="act-type-chips">
      {TYPES.map(({ key, Icon, color }) => {
        const on = filters.types.includes(key)
        const count = typeCounts ? (typeCounts[key] ?? 0) : null
        const cntId = `${uid}-cnt-${key}`
        return (
          <Fragment key={key}>
            <Button type="button" variant="outline" size="sm" aria-pressed={on}
              onClick={() => setFilters((f) => ({ ...f, types: on ? f.types.filter((x) => x !== key) : [...f.types, key] }))}
              aria-describedby={count != null ? cntId : undefined} data-type={key}
              className={cn('h-8 gap-1.5 rounded-full px-2.5 text-[0.8em] font-medium text-muted-foreground pointer-coarse:h-10',
                count === 0 && !on && 'opacity-60')}
              style={on ? { borderColor: color, color, background: color + '18' } : undefined}>
              <Icon aria-hidden="true" className="size-3.5" /> {key}
              {count != null && (
                <span aria-hidden="true" data-slot="act-type-count"
                  className="min-w-5 rounded-full bg-muted px-1.5 text-center text-[0.9em] font-semibold text-foreground tabular-nums">{count}</span>
              )}
            </Button>
            {/* Açıklama düğmenin DIŞINDA: içeride olsaydı erişilebilir ada karışırdı ("HTTP 12 kayıt") */}
            {count != null && <span id={cntId} className="sr-only">{t('act.typeCount', count)}</span>}
          </Fragment>
        )
      })}
    </div>
  )

  const facets = (
    <div data-slot="act-facets" className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
      <FacetedFilter title={t('act.d.status')} icon={ListChecks} options={statusOptions} value={filters.statuses}
        onChange={(v) => setFilters((f) => ({ ...f, statuses: STATUSES.filter((s) => v.includes(s)) }))}
        className="w-full justify-start sm:w-auto pointer-coarse:h-10" />
      <FacetedFilter title={t('act.flt.reason')} icon={CircleAlert} options={reasonOptions} value={filters.reasons}
        searchPlaceholder={t('act.flt.reasonPh')}
        onChange={(v) => setFilters((f) => ({ ...f, reasons: v.slice(0, MAX_REASONS) }))}
        className="w-full justify-start sm:w-auto pointer-coarse:h-10" />
      <div data-touch-picker className="w-full min-w-0 sm:w-56">
        <SearchableSelect value={filters.team} onChange={(v) => setFilters((f) => ({ ...f, team: v ? String(v) : '' }))}
          options={teamOptions} ariaLabel={t('act.flt.team')} wrapLabels
          placeholder={teams?.status === 'error' ? t('act.flt.teamsUnread') : t('act.flt.allTeams')} />
      </div>
      <MonitorFilterPicker value={filters.monitor} label={filters.monitorLabel} className="w-full sm:w-auto sm:max-w-xs"
        onChange={(ref, label) => setFilters((f) => ({ ...f, monitor: ref, monitorLabel: String(label || '').slice(0, 160) }))} />
      <InputGroup className="w-full pointer-coarse:h-11 sm:w-56">
        <InputGroupAddon><UserSearch aria-hidden="true" /></InputGroupAddon>
        <InputGroupInput value={actorInput} maxLength={MAX_ACTOR} placeholder={t('act.flt.actorPh')} aria-label={t('act.flt.actor')}
          data-slot="act-actor-input" className="pointer-coarse:h-10" onChange={(e) => setActorInput(e.target.value)}
          onBlur={applyActor} onKeyDown={(e) => { if (e.key === 'Enter') applyActor() }} />
      </InputGroup>
    </div>
  )

  return (
    <Card data-slot="act-filters" className="min-w-0 gap-0 py-3 shadow-xs">
      <CardContent className="flex min-w-0 flex-col gap-3 px-3 sm:px-4">
        <div className="flex min-w-0 flex-col gap-2 md:flex-row md:flex-wrap md:items-center">
          <SegmentedControl value={rangeValue} onChange={changeRange} ariaLabel={t('act.rangeFilter')} className="max-w-full flex-wrap"
            options={RANGES.map((rg) => ({ value: rg, label: t('act.range.' + rg) }))} />
          {/* md:min-w-64: dar kapta (768 px tablet + kenar çubuğu) aralık seçicinin yanına 47 px'e ezilmesin, alt satıra sarsın */}
          <InputGroup className="w-full pointer-coarse:h-11 md:max-w-sm md:min-w-64 md:flex-1">
            <InputGroupInput type="search" placeholder={t('act.searchPh')} value={qInput} aria-label={t('act.searchPh')}
              data-page-search className="pointer-coarse:h-10" maxLength={200} onChange={(e) => setQInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') applySearch() }} />
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="sm" variant="secondary" onClick={applySearch} className="pointer-coarse:h-10">{t('act.search')}</InputGroupButton>
            </InputGroupAddon>
          </InputGroup>
          <div className="flex flex-wrap items-center gap-2 md:ml-auto">{actions}</div>
        </div>

        {customOpen && (
          <div data-slot="act-custom-range" className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 p-3 sm:flex-row sm:flex-wrap sm:items-end">
            <span className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground sm:w-full">
              <CalendarRange aria-hidden="true" className="size-3.5" /> {t('act.flt.customTitle')}
            </span>
            <div className="flex min-w-0 flex-col gap-1 sm:w-56">
              <Label htmlFor={`${uid}-from`} className="text-xs text-muted-foreground">{t('act.flt.from')}</Label>
              <DateTimeField id={`${uid}-from`} value={draft.from} onChange={(v) => setDraft((d) => ({ ...d, from: v }))} clearable
                max={draft.to || undefined} invalid={!draftValid || !!rangeError}
                describedBy={!draftValid ? `${uid}-err` : rangeError ? `${uid}-xerr` : undefined} />
            </div>
            <div className="flex min-w-0 flex-col gap-1 sm:w-56">
              <Label htmlFor={`${uid}-to`} className="text-xs text-muted-foreground">{t('act.flt.to')}</Label>
              <DateTimeField id={`${uid}-to`} value={draft.to} onChange={(v) => setDraft((d) => ({ ...d, to: v }))} clearable
                min={draft.from || undefined} />
            </div>
            <Button type="button" className="pointer-coarse:h-10" onClick={applyCustom} disabled={!draftValid || (!draft.from && !draft.to)}
              data-slot="act-custom-apply">
              {t('act.flt.apply')}
            </Button>
            {!draftValid && (
              <p id={`${uid}-err`} role="alert" data-slot="act-custom-error" className="m-0 text-sm text-destructive sm:w-full">{t('act.flt.rangeOrder')}</p>
            )}
            {draftValid && rangeError && (
              <p id={`${uid}-xerr`} role="alert" data-slot="act-export-range-error" className="m-0 text-sm text-destructive sm:w-full [overflow-wrap:anywhere]">{rangeError}</p>
            )}
          </div>
        )}

        {compact ? (
          <Collapsible open={moreOpen} onOpenChange={setMoreOpen} className="min-w-0">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="outline" data-slot="act-more-toggle" className="h-10 w-full justify-between font-normal">
                <span className="flex items-center gap-2"><SlidersHorizontal aria-hidden="true" className="size-4" /> {t('act.flt.more')}</span>
                <span className="flex items-center gap-1.5">
                  {moreCount > 0 && <Badge variant="secondary" className="tabular-nums">{t('act.typesSelected', moreCount)}</Badge>}
                  <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform', moreOpen && 'rotate-180')} />
                </span>
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent className="pt-2">{facets}</CollapsibleContent>
          </Collapsible>
        ) : facets}

        {groupChips}

        {compact ? (
          <Collapsible open={typesOpen} onOpenChange={setTypesOpen} className="min-w-0">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="outline" data-slot="act-types-toggle"
                className="h-10 w-full justify-between font-normal">
                <span className="flex items-center gap-2"><Filter aria-hidden="true" className="size-4" /> {t('act.typeFilter')}</span>
                <span className="flex items-center gap-1.5">
                  {filters.types.length > 0 && <Badge variant="secondary" className="tabular-nums">{t('act.typesSelected', filters.types.length)}</Badge>}
                  <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform', typesOpen && 'rotate-180')} />
                </span>
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent className="pt-2">{typeChips}</CollapsibleContent>
          </Collapsible>
        ) : typeChips}

        {(chips.length > 0 || isFiltered(filters)) && (
          <div data-slot="act-active-filters" className="flex min-w-0 flex-wrap items-center gap-1.5 border-t pt-3">
            <span className="mr-1 inline-flex items-center gap-1 text-xs font-semibold text-muted-foreground">
              <Layers aria-hidden="true" className="size-3.5" /> {t('act.activeFilters')}
            </span>
            {chips.map((c) => (
              <Badge key={c.key} variant="secondary" data-slot="act-chip" data-kind={c.kind}
                className="h-auto gap-1 rounded-md py-0.5 pr-0.5 pl-2 text-xs font-medium whitespace-normal [overflow-wrap:anywhere] overflow-visible">
                {chipLabel(c)}
                <Button type="button" variant="ghost" size="icon-xs" className="size-6 shrink-0 rounded-full pointer-coarse:size-10"
                  aria-label={t('act.removeFilter', chipLabel(c))}
                  onClick={() => {
                    if (c.kind === 'q') setQInput('')
                    if (c.kind === 'actor') setActorInput('')
                    if (c.kind === 'range') setCustomOpen(false)
                    setFilters((f) => removeChip(f, c))
                  }}>
                  <X aria-hidden="true" />
                </Button>
              </Badge>
            ))}
            <Button type="button" variant="ghost" size="sm" className="h-7 text-muted-foreground hover:text-destructive pointer-coarse:h-10"
              onClick={clearAll}>
              <X aria-hidden="true" /> {t('act.clearFilter')}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
