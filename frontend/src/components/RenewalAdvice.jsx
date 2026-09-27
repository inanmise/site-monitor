import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import {
  AlertOctagon, AlertTriangle, BookOpenText, CalendarClock, Clock, Download, FileDown, FolderOpen, Hourglass, Layers,
  Link2Off, ListFilter, RefreshCw, ShieldCheck, Tag, Users,
} from 'lucide-react'
import { api, formatDate, formatDateOnly, localDayKey } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { buildIcs, downloadIcs } from '../utils/ics.js'
import { csvRows } from '../utils/csv.js'
import { copyText } from '../utils/copyText.js'
import { navigateTo } from '../utils/navigate.js'
import DiagnosticsModal from './admin/DiagnosticsModal.jsx'
import RenewalPlanModal from './RenewalPlanModal.jsx'
import { expiryKey } from '../pages/forecastModel.js'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import MonthCalendar from './ui/MonthCalendar.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { useToast } from './ui/Toast.jsx'
import AdviceCard from './renewal/AdviceCard.jsx'
import AdviceTable from './renewal/AdviceTable.jsx'
import AdviceToolbar from './renewal/AdviceToolbar.jsx'
import {
  CODES, NONE, SORT_KEYS, STAT_KEYS, facetOptions, filterAdvice, fingerprintCounts, isShared, joinList, parseList,
  sortAdvice, summarise, tagFacetOptions,
} from './renewal/renewalModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'

const VIEWS = ['list', 'table', 'calendar']
const GROUP_ICON = { critical: AlertOctagon, warning: AlertTriangle, info: CalendarClock }

/**
 * Sertifika Yenileme Önerileri — shadcn yeniden tasarımı (2026-09-26, kullanıcı isteği: "shadcn ile yeniden tasarla,
 * zenginleştir, mweb duyarlı yap"). Önceki tur (2026-09-18) süzgeç/sayfalama/tablo/takvim getirmişti; bu tur:
 *
 *  - Başlık satırı: amaç cümlesi + "HH:MM itibarıyla" + Yenile / Dışa aktar (CSV, .ics) / Değişim Rehberi bağlantısı.
 *  - Özet şeridi = `MonitorStatsBar` (tek etkin süzgeç, URL `r_stat`): Hemen ilgilenin · Bu hafta · Bu ay ·
 *    Önümüzdeki 60 gün · Zincir/erişim sorunu · Paylaşılan sertifika. Eski `r_pri` bağlantıları okunur.
 *  - Süzgeçler (renewal/AdviceToolbar): arama + Neden/Takım/Grup/Etiket çoklu fasetleri (telefonda alttan Sheet) +
 *    sıralama menüsü + görünüm; etkin süzgeç çipleri tek tek kaldırılır.
 *  - Kart (renewal/AdviceCard): öncelik + neden rozeti, alan adı + TeamBadge, önerilen işlem + gerekçe, kalan gün +
 *    60 günlük pencere çubuğu, "Sertifikayı aç" / "Yenilemeyi planla" (RenewalPlanModal) / "Tanıla".
 *  - Varsayılan sıralamada (öncelik) liste öncelik öbeklerine bölünür (öbek başlığı + sayı).
 *  - Yükleniyor: Skeleton; boş: StatusBlock; hata: AlertBanner + "Tekrar dene".
 *
 * Sayfalama standardı korunur: usePagination (panel ön ayarı) + `<PaginationBar {...pager} />`, `page`/`ps` adreste.
 * API: `/api/renewal-advice` plan bilgisi DÖNMEZ — plan rozeti yalnız bu oturumda kaydedilen plan için görünür.
 */
export default function RenewalAdvice({ onSelectDomain }) {
  const t = useT()
  const toast = useToast()
  const isMobile = useIsMobile()
  const canPlan = usePermissions().canEdit('inventory.crud')

  const [advice, setAdvice] = useState([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [loadError, setLoadError] = useState(null)
  const [asOf, setAsOf] = useState(null)
  const [diag, setDiag] = useState(null)       // { domain, port } → DiagnosticsModal
  const [planRow, setPlanRow] = useState(null) // RenewalPlanModal satırı
  const [plans, setPlans] = useState({})       // domain → { renewal_planned_at, renewal_planned_note } (bu oturum)

  const [view, setView] = useState(() => { try { const v = localStorage.getItem('renewal-view'); return VIEWS.includes(v) ? v : 'list' } catch { return 'list' } })
  const switchView = (v) => { setView(v); try { localStorage.setItem('renewal-view', v) } catch { /* yoksay */ } }
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [stat, setStat] = useState(() => { const v = readUrlParam('r_stat', null) ?? readUrlParam('r_pri', null); return STAT_KEYS.includes(v) ? v : null })
  const [codes, setCodes] = useState(() => parseList(readUrlParam('r_code', '')).filter((c) => c !== 'all'))
  const [teams, setTeams] = useState(() => parseList(readUrlParam('team', '')).filter((c) => c !== 'all'))
  const [groups, setGroups] = useState(() => parseList(readUrlParam('group', '')).filter((c) => c !== 'all'))
  const [tags, setTags] = useState(() => parseList(readUrlParam('tag', '')).filter((c) => c !== 'all'))
  const [sortKey, setSortKey] = useState(() => { const v = readUrlParam('sort', 'priority'); return SORT_KEYS.includes(v) ? v : 'priority' })

  // Mesaj/eylem arayüz dilinde (QA 2026-09-12, ISSUE-002): sunucu yalnız TR üretir; `code` + gün sayısı ile çevrilir,
  // bilinmeyen kodda sunucu metni kalır.
  const adviceText = useCallback((a) => {
    const n = a.days_remaining == null ? '' : Math.abs(a.days_remaining)
    const msg = t(`renewal.msg.${a.code}`, n), act = t(`renewal.act.${a.code}`, n)
    return { message: msg.startsWith('renewal.msg.') ? a.message : msg, action: act.startsWith('renewal.act.') ? a.action : act }
  }, [t])
  const codeLabel = useCallback((c) => { const k = `renewal.code.${c}`; const v = t(k); return v === k ? c : v }, [t])

  // request() ağ hatasında throw eder → .catch ŞART (yoksa spinner sonsuza kadar dönüyordu). Yenilemede eldeki veri
  // korunur; hata bandı listenin üstünde "tekrar dene" ile görünür.
  const load = useCallback((initial = false) => {
    if (!initial) setRefreshing(true)
    return api.getRenewalAdvice()
      .then((res) => {
        if (res?.success) { setAdvice(res.data || []); setLoadError(null); setAsOf(res.timestamp || new Date().toISOString()) }
        else setLoadError(res?.error || t('renewal.loadError'))
      })
      .catch((e) => setLoadError(e?.message || t('renewal.loadError')))
      .finally(() => { setLoading(false); setRefreshing(false) })
  }, [t])
  useEffect(() => { load(true) }, [])   // eslint-disable-line react-hooks/exhaustive-deps

  const fpCount = useMemo(() => fingerprintCounts(advice), [advice])
  const summary = useMemo(() => summarise(advice, fpCount), [advice, fpCount])

  const facetState = { codes, teams, groups, tags }
  const filtered = useMemo(
    () => sortAdvice(filterAdvice(advice, { q: search, stat, ...facetState }, fpCount), sortKey),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [advice, fpCount, search, stat, codes, teams, groups, tags, sortKey])

  const listKey = (l) => l.join('|')
  // Sayfalama standardı (2026-09-26): panel ön ayarı (ağır kart listesi); `page`/`ps` adresten okunur ve yazılır.
  const pager = usePagination(filtered, {
    listKey: 'renewal-advice', preset: 'panel',
    resetDeps: [search, stat, listKey(codes), listKey(teams), listKey(groups), listKey(tags), sortKey, view],
    url: { pageKey: 'page', sizeKey: 'ps' },
  })
  useUrlQuerySync({
    q: search.trim() || null, r_stat: stat, r_pri: null, r_code: joinList(codes),
    team: joinList(teams), group: joinList(groups), tag: joinList(tags), sort: sortKey !== 'priority' ? sortKey : null,
  })

  const clearFilters = () => { setSearch(''); setStat(null); setCodes([]); setTeams([]); setGroups([]); setTags([]) }
  const addTo = (setter) => (v) => setter((cur) => (cur.includes(v) ? cur : [...cur, v]))

  // ── Faset seçenekleri (sayılı) ──
  const codeOpts = useMemo(() => {
    const present = new Map(); for (const a of advice) present.set(a.code, (present.get(a.code) || 0) + 1)
    const known = CODES.filter((c) => present.has(c)).map((c) => ({ value: c, label: codeLabel(c), count: present.get(c) }))
    const unknown = [...present.keys()].filter((c) => !CODES.includes(c)).map((c) => ({ value: c, label: codeLabel(c), count: present.get(c) }))
    return [...known, ...unknown]
  }, [advice, codeLabel])
  const teamOpts = useMemo(() => facetOptions(advice, (a) => (a.team_name ? [a.team_name] : []), t('app.noTeam')), [advice, t])
  const groupOpts = useMemo(() => facetOptions(advice, (a) => (a.group_name ? [a.group_name] : []), t('app.noGroup')), [advice, t])
  const tagOpts = useMemo(() => tagFacetOptions(advice, t('mon.noTags')), [advice, t])
  const facets = [
    { key: 'code', title: t('renewal.reason'), icon: ListFilter, options: codeOpts, value: codes, onChange: setCodes },
    { key: 'team', title: t('inv.colTeam'), icon: Users, options: teamOpts, value: teams, onChange: setTeams },
    { key: 'group', title: t('renewal.group'), icon: FolderOpen, options: groupOpts, value: groups, onChange: setGroups },
    { key: 'tag', title: t('renewal.tag'), icon: Tag, options: tagOpts, value: tags, onChange: setTags },
  ].filter((f) => f.options.length > 0)

  const statLabel = (k) => t(`renewal.stat.${k}`)
  const optLabel = (opts, v) => opts.find((o) => o.value === v)?.label ?? (v === NONE ? t('app.noTeam') : v)
  const chips = [
    ...(stat ? [{ key: `s:${stat}`, label: statLabel(stat), onRemove: () => setStat(null) }] : []),
    ...codes.map((v) => ({ key: `c:${v}`, label: `${t('renewal.reason')}: ${optLabel(codeOpts, v)}`, onRemove: () => setCodes((l) => l.filter((x) => x !== v)) })),
    ...teams.map((v) => ({ key: `t:${v}`, label: `${t('inv.colTeam')}: ${optLabel(teamOpts, v)}`, onRemove: () => setTeams((l) => l.filter((x) => x !== v)) })),
    ...groups.map((v) => ({ key: `g:${v}`, label: `${t('renewal.group')}: ${optLabel(groupOpts, v)}`, onRemove: () => setGroups((l) => l.filter((x) => x !== v)) })),
    ...tags.map((v) => ({ key: `e:${v}`, label: `${t('renewal.tag')}: ${optLabel(tagOpts, v)}`, onRemove: () => setTags((l) => l.filter((x) => x !== v)) })),
    ...(search.trim() ? [{ key: 'q', label: `“${search.trim()}”`, onRemove: () => setSearch('') }] : []),
  ]

  const statItems = [
    { key: 'critical', Icon: AlertOctagon, cls: 'critical', value: summary.critical },
    { key: 'week', Icon: Hourglass, cls: 'high', value: summary.week },
    { key: 'warning', Icon: AlertTriangle, cls: 'warning', value: summary.warning },
    { key: 'info', Icon: CalendarClock, cls: 'total', value: summary.info },
    { key: 'problems', Icon: Link2Off, cls: 'certissue', value: summary.problems },
    { key: 'shared', Icon: Layers, cls: 'weak', value: summary.batches, sub: t('renewal.stat.sharedSub', summary.shared) },
  ].map((s) => ({ label: statLabel(s.key), sub: t(`renewal.stat.${s.key}Sub`), hint: t(`renewal.stat.${s.key}Hint`), ...s }))

  const sortOptions = [
    { value: 'priority', label: t('renewal.sortPriority') },
    { value: 'days', label: t('renewal.sortDays') },
    { value: 'domain', label: t('renewal.sortDomain') },
    { value: 'team', label: t('renewal.sortTeam') },
  ]

  const openCert = (d) => onSelectDomain?.(d)
  const openPlan = canPlan ? (a) => setPlanRow({
    domain: a.domain,
    renewal_planned_at: plans[a.domain]?.renewal_planned_at || '',
    renewal_planned_note: plans[a.domain]?.renewal_planned_note || '',
    // Yerel gün (2026-09-27): `not_after.slice(0,10)` UTC günüydü → İstanbul'da gece bitenler bir gün erken görünürdü.
    expiry_key: expiryKey(a),
  }) : null
  const diagnose = (a) => setDiag({ domain: a.domain, port: a.port || 443 })
  async function copyDomain(d) {
    if (await copyText(d)) toast.success(t('renewal.copiedDomain', d))
  }

  const calEvents = filtered.filter((a) => a.not_after).map((a) => ({
    date: a.not_after, label: a.domain, title: `${a.domain} · ${adviceText(a).message}`,   // takvim yerel güne yerleştirir (ISSUE-004)
    tone: a.priority === 'critical' ? 'bad' : a.priority === 'warning' ? 'warn' : 'info', onClick: () => openCert(a.domain),
  }))
  function exportIcs() {
    downloadIcs('sertifika-yenilemeleri.ics', buildIcs(filtered.filter((a) => a.not_after).map((a) => {
      const tx = adviceText(a)
      return { uid: `cert-${a.domain}-${localDayKey(a.not_after)}`, date: a.not_after, summary: `${t('renewal.icsPrefix')} ${a.domain}`, description: `${tx.message}\n${tx.action || ''}` }
    }), { calName: t('renewal.icsCal') }))
  }
  function exportCsv() {
    const head = ['domain', 'priority', 'reason', 'days_remaining', 'not_after', 'team', 'tier', 'group', 'tags', 'issuer', 'action']
    const rows = filtered.map((a) => [a.domain, a.priority, a.code, a.days_remaining ?? '', a.not_after ? formatDateOnly(a.not_after) : '', a.team_name || '', a.tier ?? '', a.group_name || '', a.tags || '', a.issuer_cn || '', adviceText(a).action || ''])
    const csv = '﻿' + csvRows([head, ...rows])
    try {
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const el = document.createElement('a'); el.href = url; el.download = 'yenileme-onerileri.csv'; document.body.appendChild(el); el.click(); el.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom */ }
  }

  // ── Yükleniyor: gerçek yerleşimle aynı boyda iskelet (zıplama yok) ──
  if (loading) {
    return (
      <div data-slot="renewal-advice" aria-busy="true" className="flex min-w-0 flex-col gap-3">
        <span role="status" className="sr-only">{t('renewal.loading')}</span>
        <Skeleton className="h-4 w-full max-w-xl" />
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
          {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-24 rounded-lg" />)}
        </div>
        <Skeleton className="h-9 w-full sm:w-2/3" />
        {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-44 rounded-xl" />)}
      </div>
    )
  }
  // Hata bandı "her şey yolunda" boş durumunun ÖNÜNDE: yükleme hatası "yenilenecek sertifika yok" gibi okunmasın.
  if (loadError && advice.length === 0) {
    return (
      <AlertBanner tone="danger" title={t('renewal.loadError')} role="alert"
        actions={<Button type="button" variant="outline" size="sm" onClick={() => load()} disabled={refreshing}><RefreshCw aria-hidden="true" />{t('renewal.retry')}</Button>}>
        {String(loadError)}
      </AlertBanner>
    )
  }

  const header = (
    <div data-slot="rn-header" className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0">
        <p className="max-w-[78ch] text-sm text-muted-foreground">{t('renewal.subtitle')}</p>
        {asOf && (
          <p className="mt-1 flex items-center gap-1.5 text-xs text-muted-foreground" data-slot="rn-asof">
            <Clock aria-hidden="true" className="size-3.5" />{t('renewal.asOf', formatDate(asOf))}
          </p>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => load()} disabled={refreshing} aria-busy={refreshing || undefined}>
          <RefreshCw aria-hidden="true" className={refreshing ? 'animate-spin motion-reduce:animate-none' : undefined} />{t('renewal.refresh')}
        </Button>
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" disabled={!filtered.length}><Download aria-hidden="true" />{t('renewal.export')}</Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="z-(--z-menu)">
            <DropdownMenuItem onSelect={exportCsv} title={t('renewal.csvTip')}><FileDown aria-hidden="true" />{t('renewal.exportCsv')}</DropdownMenuItem>
            <DropdownMenuItem onSelect={exportIcs} disabled={!calEvents.length} title={t('renewal.icsTip')}><CalendarClock aria-hidden="true" />{t('renewal.ics')}</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <Button type="button" variant="ghost" size="sm" className="pointer-coarse:h-10" onClick={() => navigateTo('renewal-guide')}>
          <BookOpenText aria-hidden="true" />{t('renewal.openGuide')}
        </Button>
      </div>
    </div>
  )

  if (advice.length === 0) {
    return (
      <div data-slot="renewal-advice" className="flex min-w-0 flex-col gap-4">
        {header}
        <StatusBlock tone="success" icon={ShieldCheck} title={t('renewal.allGood')} description={t('empty.hintAllGood')}
          className="rounded-xl border border-dashed" />
      </div>
    )
  }

  const grouped = view === 'list' && sortKey === 'priority'
  const groupCount = (p) => filtered.filter((a) => a.priority === p).length

  return (
    <div data-slot="renewal-advice" className="flex min-w-0 flex-col gap-4">
      {header}

      {loadError && (
        <AlertBanner tone="warning" title={t('renewal.refreshFailed')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" onClick={() => load()} disabled={refreshing}><RefreshCw aria-hidden="true" />{t('renewal.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      )}

      <div className="[&>[data-slot=stats-panel]]:mb-0">
        <MonitorStatsBar items={statItems} activeFilter={stat} onStatClick={(k) => setStat((cur) => (cur === k ? null : k))} />
      </div>

      <AdviceToolbar t={t} isMobile={isMobile} search={search} onSearch={setSearch} facets={facets}
        sortKey={sortKey} sortOptions={sortOptions} onSort={setSortKey} view={view} onView={switchView}
        chips={chips} onClearAll={clearFilters} shown={filtered.length} total={advice.length} />

      {filtered.length === 0 && (
        <StatusBlock tone="neutral" icon={ListFilter} title={t('renewal.noneFiltered')} description={t('empty.hintFilter')}
          className="rounded-xl border border-dashed"
          actions={<Button type="button" variant="outline" size="sm" onClick={clearFilters}>{t('app.clearFilters')}</Button>} />
      )}

      {view === 'calendar' && filtered.length > 0 && <MonthCalendar events={calEvents} ariaLabel={t('renewal.viewCalendar')} />}

      {view === 'table' && filtered.length > 0 && (
        <AdviceTable rows={pager.pageItems} t={t} codeLabel={codeLabel} fpCount={fpCount}
          onOpen={openCert} onPlan={openPlan} onDiagnose={diagnose} onCopy={copyDomain} />
      )}

      {view === 'list' && filtered.length > 0 && (
        <div data-slot="rn-list" className="flex min-w-0 flex-col gap-3">
          {pager.pageItems.map((item, i) => {
            const head = grouped && (i === 0 || pager.pageItems[i - 1].priority !== item.priority)
            const GIcon = GROUP_ICON[item.priority] ?? CalendarClock
            const shared = isShared(item, fpCount) ? fpCount.get(item.fingerprint) : 0
            return (
              <Fragment key={item.domain + item.code}>
                {head && (
                  <h3 data-slot="rn-group" data-priority={item.priority} className="flex items-center gap-2 pt-1 text-sm font-semibold">
                    <GIcon aria-hidden="true" className="size-4 text-muted-foreground" />
                    {t(`renewal.group.${item.priority}`)}
                    <Badge variant="secondary" className="tabular-nums">{groupCount(item.priority)}</Badge>
                    <span aria-hidden="true" className="h-px flex-1 bg-border" />
                  </h3>
                )}
                <AdviceCard item={item} t={t} text={adviceText(item)} codeLabel={codeLabel(item.code)} shared={shared}
                  plan={plans[item.domain]} onOpen={openCert} onPlan={openPlan} onDiagnose={diagnose} onCopy={copyDomain}
                  onFilterGroup={addTo(setGroups)} onFilterTag={addTo(setTags)} />
              </Fragment>
            )
          })}
        </div>
      )}

      {view !== 'calendar' && filtered.length > 0 && <PaginationBar {...pager} />}

      {/* Tanılama modalı (envanter ile ortak) — backend izlenen domainlere açık */}
      {diag && <DiagnosticsModal domain={diag.domain} port={diag.port} onClose={() => setDiag(null)} />}
      {planRow && (
        <RenewalPlanModal row={planRow} onClose={() => setPlanRow(null)}
          onSaved={(data) => { setPlans((p) => ({ ...p, [planRow.domain]: data || { renewal_planned_at: planRow.renewal_planned_at } })); setPlanRow(null) }}
          onCleared={() => { setPlans((p) => { const n = { ...p }; delete n[planRow.domain]; return n }); setPlanRow(null) }} />
      )}
    </div>
  )
}
