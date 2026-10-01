import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  Radar, RefreshCw, Search, PauseCircle, CircleAlert, Clock, CheckCircle2, BellRing, Boxes, Activity, ListChecks,
  ShieldAlert, FilterX, Download, LayoutGrid,
} from 'lucide-react'
import { api } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { usePagination } from '../hooks/usePagination.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { navigateTo } from '../utils/navigate.js'
import { downloadCsv, stampedName } from '../utils/csvExport.js'
import PageHeader from './ui/PageHeader.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import {
  applyFilters, sortRows, facetCounts, optionCounts, parseSort, sortValue, toggleSort, toList, teamKey,
  activeColumnFilterCount, matchLastCheck, matchChecks, matchAlert, LAST_CHECK_OPTS, CHECKS_OPTS, ALERT_OPTS, NO_TEAM,
} from './monitoring/overviewFilters.js'
import { TYPE_META, TYPE_ORDER, STATUS_META, STATUS_ORDER, WINDOWS } from './monitoring/overviewMeta.js'
import { QUICK_VIEWS, matchQuickView, quickViewCounts, teamHealth, overviewCsv } from './monitoring/overviewModel.js'
import { FilterChips, PhoneFilterSheet } from './monitoring/OverviewColumnFilters.jsx'
import { LiveIndicator } from './monitoring/OverviewParts.jsx'
import OverviewHealthCard from './monitoring/OverviewHealthCard.jsx'
import OverviewTeamsCard from './monitoring/OverviewTeamsCard.jsx'
import OverviewTypeCard from './monitoring/OverviewTypeCard.jsx'
import OverviewKpiDialog, { KPI_DIALOG_KINDS } from './monitoring/OverviewKpiDialog.jsx'
import { OverviewCards, OverviewTable, TABLE_MIN_WIDTH } from './monitoring/OverviewList.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

// Tanımlar monitoring/overviewMeta.js'e taşındı; bu dosyadan içe aktaranlar (testler, diğer ekranlar) için aynı adlarla.
export { TYPE_META, TYPE_ORDER, STATUS_META, successPct } from './monitoring/overviewMeta.js'

/**
 * İZLEME PANOSU (2026-09-30, kullanıcı isteği; 2026-10-01 shadcn yeniden tasarımı): İzleme menüsünün en üstünde, 9 izleme
 * türünün TEK ekranda durumu — hangi izleme ne durumda, sorun var mı, ne zaman kontrol edilmiş, açık/çözülen alarmı var
 * mı, çalışmayan (duraklatılmış / kontrolü gecikmiş / envanterden çıkmış) ve silinmiş izlemeler, izleme adetleri ve
 * pencere içi koşum sayıları.
 *
 * <p><b>Düzen (mobil-öncelikli, shadcn):</b>
 * <ol>
 *   <li>Başlık (PageHeader): "canlı" tazelik çipi (sunucunun veriyi ürettiği an, 60 sn yoklama) · pencere (24 sa / 7 gün)
 *       · Yenile (sunucu belleğini `fresh=1` ile atlar).</li>
 *   <li>KPI şeridi (MonitorStatsBar): Toplam / Sağlıklı süzgeç kartları; Sorunlu / Kontrolü gecikmiş / Duraklatılmış /
 *       Açık alarm → ÖZET PENCERESİ (tür + takım dağılımı, izleme listesi, "Listede süz"); Koşum → hata veren
 *       görünümü; Çözülen → Alarm Geçmişi.</li>
 *   <li>Filo sağlığı kartı (hüküm + durum dağılım çubuğu + "Dikkat gerektirenler" ilk 5) ve takım sağlığı kartı (birden
 *       çok takım görülüyorsa) — ≥ 1280 px yan yana (3/5 + 2/5), daha dar alt alta.</li>
 *   <li>Tür kartları: 1 / 2 (≥ 640) / 3 (≥ 1280) sütun; başarı oranı + ağırlıklı ortalama yanıt + anlık sayılar.</li>
 *   <li>İzleme listesi: hızlı görünümler (Tümü · Sorunlu · Alarmlı · Hata veren · Duraklatılmış) + arama/süzgeçler +
 *       CSV; liste KABI ≥ 720 px tablo (sütunlar kap genişliğiyle açılır), daha dar kartlar; sayfalama.</li>
 * </ol>
 *
 * <p><b>Zenginleştirmeler — seçim ve gerekçe (Datadog / Grafana / Better Stack / Checkly / Pingdom / New Relic Synthetics
 * taraması):</b> (1) Filo hükmü + dağılım çubuğu — "her şey yolunda mı?" sorusunun tek satırlık cevabı (Statuspage/Better
 * Stack üst şeridi). (2) Dikkat gerektirenler — Datadog "Triggered monitors" / Checkly "failing checks first": sorunlu →
 * gecikmiş → hiç kontrol edilmemiş, neden satırıyla (son hata, açık kalma süresi, beklenen aralık). (3) Satır başına
 * pencere başarı oranı ölçeri — Pingdom/UptimeRobot "uptime %" (veri zaten satırda). (4) Yanıt süresi sütunu: son ölçüm +
 * pencere ortalaması + "yavaş" işareti (son ≥ 2× ortalama ve +200 ms) — Checkly/Pingdom; ortalama mevcut pencere
 * sorgusundan, ek sorgu YOK. (5) Takım sağlığı — müdür / global yönetici hangi takımın yandığını görür (Grafana takım
 * panoları). (6) Hızlı görünümler — Better Stack "Down / Paused" sekmeleri gibi tek tık önayar. (7) Canlı tazelik + CSV
 * dışa aktarım. Yoğunluk seçici ALINMADI: kullanıcı kararı (2026-09-27) yoğunluk tercihinin tarayıcıya yazılmamasıdır;
 * zaman serisi grafikleri ALINMADI: 9 depoya yeni kova sorgusu ve dakikalık yoklamada DB yükü gerektirir.
 *
 * <p>Veri: `GET /api/monitoring/overview` (dakikada bir; sekme gizliyken durur; sunucu 30 sn paylaşır). URL durumu `mo_*`
 * önekiyle (`mo_win mo_type mo_status mo_team mo_q mo_lc mo_ck mo_al mo_sort mo_dlg`).
 *
 * <p>Test kancaları: `data-slot="mo-page"`, `mo-window`, `mo-live`, `mo-health` (`data-tone`), `mo-verdict`,
 * `mo-distribution`, `mo-legend-item`, `mo-attention`, `mo-attention-item`, `mo-teams`, `mo-team-row` (`data-team`),
 * `mo-type-card` (`data-type`, `data-tone`), `mo-views` (öğe `data-view`), `mo-list-anchor`, `mo-status-filter`,
 * `mo-csv`, `mo-row` (`data-status`, `data-type`), `mo-uptime`, `mo-response`, `mo-inv-inactive`, `mo-kpi-dialog`
 * (`data-kind`), `mo-dlg-filter`, `mo-dlg-item`; sütun süzgeçleri `mo-col-filter`, `mo-sort`, `mo-chip(s)`,
 * `mo-phone-filters`; KPI `[data-slot="stat-item"][data-key]`.
 */

/** Liste süzgeci — saf (test edilebilir). */
export function filterRows(rows, filters = {}, sort = { key: '', dir: 'asc' }, nowMs = Date.now()) {
  // Tek değerli eski imza ({ type, status, team, q }) ve sütun süzgeçleri ({ types[], statuses[], teams[], last,
  // checks, alert }) aynı kuraldan geçer (monitoring/overviewFilters.js). Varsayılan sıra: durum → açık alarm → ad.
  return sortRows(applyFilters(rows, filters, { nowMs }), sort)
}

/** Öğenin genişliğini izleyen callback ref (ResizeObserver). Ölçüm yoksa (jsdom) 0. */
function useElementWidth() {
  const [width, setWidth] = useState(0)
  const roRef = useRef(null)
  const ref = useCallback((el) => {
    roRef.current?.disconnect()
    roRef.current = null
    if (!el) return
    const measure = () => setWidth(el.clientWidth || 0)
    measure()
    if (typeof ResizeObserver !== 'undefined') {
      roRef.current = new ResizeObserver(measure)
      roRef.current.observe(el)
    }
  }, [])
  useEffect(() => () => roRef.current?.disconnect(), [])
  return [ref, width]
}

function OverviewSkeleton({ label }) {
  return (
    <div role="status" aria-live="polite" data-slot="mo-skeleton" className="flex flex-col gap-4">
      <span className="sr-only">{label}</span>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-8">
        {Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-24 motion-reduce:animate-none" />)}
      </div>
      <Skeleton className="h-36 motion-reduce:animate-none" />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-48 motion-reduce:animate-none" />)}
      </div>
    </div>
  )
}

export default function MonitoringOverviewPage() {
  const t = useT()
  const locale = useDateLocale()
  const phone = useIsMobile()
  const listTitleId = useId()
  const [windowHours, setWindowHours] = useState(() => (Number(readUrlParam('mo_win', 24)) === 168 ? 168 : 24))
  const [state, setState] = useState({ loading: true, error: null, data: null, at: null })
  // Sütun süzgeçleri (2026-10-01): tür / durum / takım ÇOKLU (URL'de virgüllü), son kontrol / koşum / alarm tek seçim,
  // arama izleme sütununun süzgeci; sıralama başlıktan (mo_sort). Kart tıklaması / hızlı görünüm önayar seçer.
  const [types, setTypes] = useState(() => toList(readUrlParam('mo_type', '')).filter((k) => TYPE_META[k]))
  const [statuses, setStatuses] = useState(() => toList(readUrlParam('mo_status', '')).filter((k) => STATUS_META[k]))
  const [teamsSel, setTeamsSel] = useState(() => toList(readUrlParam('mo_team', '')))
  const [q, setQ] = useState(() => readUrlParam('mo_q', ''))
  const [lastSel, setLastSel] = useState(() => (LAST_CHECK_OPTS.includes(readUrlParam('mo_lc', '')) ? readUrlParam('mo_lc', '') : ''))
  const [checksSel, setChecksSel] = useState(() => (CHECKS_OPTS.includes(readUrlParam('mo_ck', '')) ? readUrlParam('mo_ck', '') : ''))
  const [alertSel, setAlertSel] = useState(() => (ALERT_OPTS.includes(readUrlParam('mo_al', '')) ? readUrlParam('mo_al', '') : ''))
  const [sort, setSort] = useState(() => parseSort(readUrlParam('mo_sort', '')))
  // KPI özet penceresi (down | stale | alerts | paused) — URL'de: paylaşılan bağlantı pencereyi açık getirir
  const [kpi, setKpi] = useState(() => (KPI_DIALOG_KINDS.includes(readUrlParam('mo_dlg', '')) ? readUrlParam('mo_dlg', '') : ''))
  useUrlQuerySync({
    mo_win: windowHours === 24 ? null : String(windowHours), mo_type: types.join(',') || null, mo_status: statuses.join(',') || null,
    mo_team: teamsSel.join(',') || null, mo_q: q || null, mo_lc: lastSel || null, mo_ck: checksSel || null, mo_al: alertSel || null,
    mo_sort: sortValue(sort) || null, mo_dlg: kpi || null,
  })
  // Eski tek değerli adlar (araç çubuğu seçicileri) — dizinin tek elemanı ya da boş
  const type = types.length === 1 ? types[0] : ''
  const status = statuses.length === 1 ? statuses[0] : ''
  const team = teamsSel.length === 1 ? teamsSel[0] : ''
  const setType = (v) => setTypes(v ? [v] : [])
  const setStatus = (v) => setStatuses(v ? [v] : [])
  const setTeam = (v) => setTeamsSel(v ? [v] : [])

  // fresh: Yenile düğmesi — sunucu belleğini atlar (en fazla 5 sn'de bir); yoklama bellekten okur.
  const load = useCallback(async (fresh = false) => {
    try {
      const res = fresh === true ? await api.monitoring.getOverview(windowHours, true) : await api.monitoring.getOverview(windowHours)
      if (res?.success && res.data) setState({ loading: false, error: null, data: res.data, at: new Date() })
      else setState((s) => ({ ...s, loading: false, error: res?.error || t('mo.loadError') }))
    } catch (e) {
      setState((s) => ({ ...s, loading: false, error: e?.message || t('mo.loadError') }))
    }
  }, [windowHours, t])
  // İlk yükleme + pencere değişince yeniden yükleme (load kimliği windowHours ile değişir); dakikalık yoklama ayrı.
  useEffect(() => { load() }, [load])
  useVisibleInterval(load, 60_000, false)
  const refresh = () => { setState((s) => ({ ...s, loading: true })); load(true) }

  const data = state.data
  const rows = useMemo(() => data?.monitors || [], [data])
  const teams = useMemo(() => {
    const m = new Map()
    for (const r of rows) if (r.team_id != null && r.team_name) m.set(String(r.team_id), r.team_name)
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1], 'tr'))
  }, [rows])
  const filters = useMemo(() => ({ types, statuses, teams: teamsSel, q, last: lastSel, checks: checksSel, alert: alertSel }),
    [types, statuses, teamsSel, q, lastSel, checksSel, alertSel])
  // "Şimdi" veri yüklendiğinde sabitlenir — son kontrol süzgeci her çizimde kaymasın (yoklama 60 sn'de tazeler)
  const nowMs = useMemo(() => (state.at ? state.at.getTime() : Date.now()), [state.at])
  const filtered = useMemo(() => filterRows(rows, filters, sort, nowMs), [rows, filters, sort, nowMs])
  const pager = usePagination(filtered, { listKey: 'monitoring-overview', preset: 'page',
    resetDeps: [types.join(','), statuses.join(','), teamsSel.join(','), q, lastSel, checksSel, alertSel, windowHours, sortValue(sort)] })
  const totals = data?.totals || {}
  const columnFilterCount = activeColumnFilterCount(filters)
  const anyFilter = !!(columnFilterCount || q)
  const clearFilters = () => { setTypes([]); setStatuses([]); setTeamsSel([]); setQ(''); setLastSel(''); setChecksSel(''); setAlertSel('') }
  const onSort = (key) => setSort((cur) => toggleSort(cur, key))
  const windowLabel = windowHours === 24 ? t('mo.win.24') : t('mo.win.168')
  const teamGroups = useMemo(() => teamHealth(rows, t('mo.colf.noTeam')), [rows, t])
  const viewCounts = useMemo(() => quickViewCounts(rows, nowMs), [rows, nowMs])
  const activeView = matchQuickView(filters)
  const invInactive = Number(totals.inventory_inactive ?? 0)

  // Liste görünümü KABIN genişliğine göre: ≥ 720 px tablo, daha dar kartlar; ölçüm yoksa (jsdom) telefon eşiği.
  const [measureRef, listWidth] = useElementWidth()
  const tableMode = listWidth > 0 ? listWidth >= TABLE_MIN_WIDTH : !phone

  // Sütun süzgeci grupları — başlık menüleri, süzgeç paneli ve çipler aynı tanımı kullanır. Sayılar: diğer süzgeçler
  // etkinken o değer seçilirse kalan satır (faset). Satır sayısı yüzlerle sınırlı; tek useMemo.
  const columnGroups = useMemo(() => {
    const fc = (col, keyOf) => facetCounts(rows, filters, col, keyOf, nowMs)
    const st = fc('status', (r) => r.status), ty = fc('type', (r) => r.type), tm = fc('team', teamKey)
    const lc = optionCounts(rows, filters, 'last', LAST_CHECK_OPTS, matchLastCheck, nowMs)
    const ck = optionCounts(rows, filters, 'checks', CHECKS_OPTS, (r, o) => matchChecks(r, o), nowMs)
    const al = optionCounts(rows, filters, 'alert', ALERT_OPTS, (r, o) => matchAlert(r, o), nowMs)
    const teamOpts = teams.map(([id, name]) => ({ value: id, label: name, count: tm[id] || 0 }))
    if (rows.some((r) => r.team_id == null)) teamOpts.push({ value: NO_TEAM, label: t('mo.colf.noTeam'), count: tm[NO_TEAM] || 0 })
    const LAST_LABEL = { '15m': t('mo.colf.last.15m'), '1h': t('mo.colf.last.1h'), '24h': t('mo.colf.last.24h'), older: t('mo.colf.last.older'), never: t('mo.colf.last.never') }
    const CHECKS_LABEL = { failed: t('mo.colf.checks.failed'), clean: t('mo.colf.checks.clean'), none: t('mo.colf.checks.none') }
    const ALERT_LABEL = { any: t('mo.colf.alert.any'), CRITICAL: t('mo.colf.alert.CRITICAL'), HIGH: t('mo.colf.alert.HIGH'), WARNING: t('mo.colf.alert.WARNING'), none: t('mo.colf.alert.none') }
    return {
      status: { name: 'status', label: t('mo.col.status'), kind: 'multi', value: statuses, onChange: setStatuses,
        options: STATUS_ORDER.map((k) => ({ value: k, label: t(STATUS_META[k].labelKey), count: st[k] || 0 })) },
      monitor: { name: 'q', label: t('mo.col.monitor'), kind: 'text', value: q, onChange: setQ, placeholder: t('mo.searchPlaceholder') },
      type: { name: 'type', label: t('mo.col.type'), kind: 'multi', value: types, onChange: setTypes,
        options: TYPE_ORDER.map((k) => ({ value: k, label: t(TYPE_META[k].labelKey), count: ty[k] || 0 })) },
      team: { name: 'team', label: t('mo.col.team'), kind: 'multi', value: teamsSel, onChange: setTeamsSel, options: teamOpts },
      last: { name: 'last', label: t('mo.col.lastCheck'), kind: 'single', value: lastSel, onChange: setLastSel,
        options: LAST_CHECK_OPTS.map((k) => ({ value: k, label: LAST_LABEL[k], count: lc[k] })) },
      checks: { name: 'checks', label: t('mo.col.checks'), kind: 'single', value: checksSel, onChange: setChecksSel,
        options: CHECKS_OPTS.map((k) => ({ value: k, label: CHECKS_LABEL[k], count: ck[k] })) },
      alert: { name: 'alert', label: t('mo.col.alert'), kind: 'single', value: alertSel, onChange: setAlertSel,
        options: ALERT_OPTS.map((k) => ({ value: k, label: ALERT_LABEL[k], count: al[k] })) },
    }
  }, [rows, filters, nowMs, teams, statuses, types, teamsSel, q, lastSel, checksSel, alertSel, t])

  // Etkin süzgeç çipleri (arama araç çubuğunda göründüğü için çip değil)
  const chips = useMemo(() => {
    const out = []
    const labelOf = (g, v) => g.options.find((o) => o.value === v)?.label ?? v
    for (const key of ['status', 'type', 'team']) {
      const g = columnGroups[key]
      if (g.value.length) out.push({ key, label: g.label, value: g.value.map((v) => labelOf(g, v)).join(', '), onRemove: () => g.onChange([]) })
    }
    for (const key of ['last', 'checks', 'alert']) {
      const g = columnGroups[key]
      if (g.value) out.push({ key, label: g.label, value: labelOf(g, g.value), onRemove: () => g.onChange('') })
    }
    return out
  }, [columnGroups])
  const sortOptions = useMemo(() => [
    { value: '', label: t('mo.sort.default') },
    { value: 'status_asc', label: t('mo.sort.opt.status_asc') }, { value: 'status_desc', label: t('mo.sort.opt.status_desc') },
    { value: 'name_asc', label: t('mo.sort.opt.name_asc') }, { value: 'name_desc', label: t('mo.sort.opt.name_desc') },
    { value: 'type_asc', label: t('mo.sort.opt.type_asc') }, { value: 'type_desc', label: t('mo.sort.opt.type_desc') },
    { value: 'team_asc', label: t('mo.sort.opt.team_asc') }, { value: 'team_desc', label: t('mo.sort.opt.team_desc') },
    { value: 'last_asc', label: t('mo.sort.opt.last_asc') }, { value: 'last_desc', label: t('mo.sort.opt.last_desc') },
    { value: 'uptime_asc', label: t('mo.sort.opt.uptime_asc') }, { value: 'uptime_desc', label: t('mo.sort.opt.uptime_desc') },
    { value: 'response_desc', label: t('mo.sort.opt.response_desc') }, { value: 'response_asc', label: t('mo.sort.opt.response_asc') },
    { value: 'checks_desc', label: t('mo.sort.opt.checks_desc') }, { value: 'checks_asc', label: t('mo.sort.opt.checks_asc') },
    { value: 'alert_desc', label: t('mo.sort.opt.alert_desc') }, { value: 'alert_asc', label: t('mo.sort.opt.alert_asc') },
  ], [t])

  // Kart/KPI tıklaması listeyi süzer AMA liste ekranın altındaydı — kullanıcı "ne oldu" göremiyordu (2026-09-30).
  // Süzgeç değişince liste başlığına kaydırılır (jsdom'da scrollIntoView yok → isteğe bağlı çağrı).
  const listRef = useRef(null)
  const focusList = () => { try { listRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }) } catch { /* yok say */ } }
  const pickStatus = (key) => { setStatuses((cur) => (cur.length === 1 && cur[0] === key ? [] : [key])); focusList() }
  const pickType = (key) => { setTypes((cur) => (cur.length === 1 && cur[0] === key ? [] : [key])); focusList() }
  const pickTeam = (key) => { setTeamsSel((cur) => (cur.length === 1 && cur[0] === key ? [] : [key])); focusList() }
  const applyView = (key) => {
    const v = QUICK_VIEWS.find((x) => x.key === key)?.filters ?? {}
    setTypes(v.types ?? []); setStatuses(v.statuses ?? []); setTeamsSel(v.teams ?? [])
    setLastSel(v.last ?? ''); setChecksSel(v.checks ?? ''); setAlertSel(v.alert ?? '')
  }
  // Özet penceresinden "Listede süz": pencere kapanınca odak KPI kutusuna döner (ModalShell) — kaydırma ondan SONRA.
  const onKpiFilter = (kind) => {
    if (kind === 'alerts') { setStatuses([]); setAlertSel('any') } else setStatuses([kind])
    setKpi('')
    setTimeout(focusList, 0)
  }
  const exportCsv = () => {
    const csv = overviewCsv(filtered, { t, locale,
      typeLabel: (k) => (TYPE_META[k] ? t(TYPE_META[k].labelKey) : k),
      statusLabel: (k) => t((STATUS_META[k] ?? STATUS_META.unknown).labelKey) })
    downloadCsv(stampedName(t('mo.csv.file')), csv)
  }

  const kpiTip = (label) => t('mo.kpi.openSummary', label)
  const kpis = [
    { key: 'total',   Icon: Boxes,       label: t('mo.kpi.total'),   value: totals.total ?? 0,   cls: 'total' },
    { key: 'up',      Icon: CheckCircle2, label: t('mo.kpi.up'),      value: Math.max(0, (totals.active ?? 0) - (totals.down ?? 0) - (totals.stale ?? 0) - (totals.unknown ?? 0)), cls: 'valid' },
    { key: 'down',    Icon: CircleAlert, label: t('mo.kpi.down'),    value: totals.down ?? 0,    cls: 'critical', tip: kpiTip(t('mo.kpi.down')), onClick: () => setKpi('down') },
    { key: 'stale',   Icon: Clock,       label: t('mo.kpi.stale'),   value: totals.stale ?? 0,   cls: 'warning',  hint: t('mo.kpi.staleHint'), tip: kpiTip(t('mo.kpi.stale')), onClick: () => setKpi('stale') },
    { key: 'paused',  Icon: PauseCircle, label: t('mo.kpi.paused'),  value: totals.paused ?? 0,  cls: 'paused',
      sub: invInactive > 0 ? t('mo.kpi.invSub', invInactive) : undefined, tip: kpiTip(t('mo.kpi.paused')), onClick: () => setKpi('paused') },
    { key: 'alerts',  Icon: BellRing,    label: t('mo.kpi.alerts'),  value: totals.open_alerts ?? 0, cls: 'alert', hint: t('mo.kpi.alertsHint', totals.open_critical ?? 0), tip: kpiTip(t('mo.kpi.alerts')), onClick: () => setKpi('alerts') },
    { key: 'checks',  Icon: ListChecks,  label: t('mo.kpi.checks', windowLabel), value: Number(totals.checks_window ?? 0).toLocaleString(locale), cls: 'total',
      sub: t('mo.kpi.failed', Number(totals.failed_window ?? 0).toLocaleString(locale)), tip: t('mo.kpi.checksTip'),
      onClick: () => { applyView('failing'); focusList() } },
    { key: 'resolved', Icon: ShieldAlert, label: t('mo.kpi.resolved'), value: totals.resolved_window ?? 0, cls: 'valid', tip: t('mo.kpi.resolvedTip'), onClick: () => navigateTo('alerthistory', { view: 'closed' }) },
  ]
  const kpiActive = statuses.length === 0 ? 'total' : status === 'up' ? 'up' : null
  const onStatClick = (key) => {
    if (key === 'total') { setStatuses([]); focusList() } else pickStatus(key)
  }

  const VIEW_LABEL = {
    all: t('mo.view.all'), problems: t('mo.view.problems'), alerts: t('mo.view.alerts'),
    failing: t('mo.view.failing'), paused: t('mo.view.paused'),
  }
  const filterSheet = (
    <PhoneFilterSheet groups={[columnGroups.status, columnGroups.type, columnGroups.team, columnGroups.last, columnGroups.checks, columnGroups.alert]}
      sort={sortValue(sort)} sortOptions={sortOptions} onSortChange={(v) => setSort(parseSort(v))}
      activeCount={columnFilterCount} onClearAll={clearFilters} className={tableMode ? '@6xl/list:hidden' : undefined} />
  )

  return (
    <div data-slot="mo-page" className="min-w-0" aria-busy={state.loading || undefined}>
      <PageHeader icon={Radar} title={t('mo.title')} description={t('mo.subtitle')}
        meta={data ? (
          <>
            <LiveIndicator generatedAt={data.generated_at} fetchedAt={state.at} failed={!!state.error} loading={state.loading} />
            <Badge variant="outline" className="h-6 rounded-full px-2 font-normal text-muted-foreground">{t('mo.meta.window', windowLabel)}</Badge>
          </>
        ) : null}
        actions={<>
          <ToggleGroup type="single" value={String(windowHours)} onValueChange={(v) => { if (v) { setWindowHours(Number(v)); setState((s) => ({ ...s, loading: true })) } }}
            variant="outline" size="sm" data-slot="mo-window" aria-label={t('mo.win.label')}>
            {WINDOWS.map((w) => <ToggleGroupItem key={w} value={String(w)} className="px-3 pointer-coarse:h-10">{w === 24 ? t('mo.win.24') : t('mo.win.168')}</ToggleGroupItem>)}
          </ToggleGroup>
          <Button type="button" variant="outline" size="sm" onClick={refresh} aria-busy={state.loading || undefined} className="pointer-coarse:h-10">
            <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} />{t('mo.refresh')}
          </Button>
        </>} />

      {state.loading && !data && <OverviewSkeleton label={t('mo.loading')} />}
      {state.error && !data && <StatusBlock tone="danger" icon={CircleAlert} title={t('mo.loadError')} description={state.error}
        actions={<Button type="button" variant="outline" onClick={refresh}>{t('mo.refresh')}</Button>} />}

      {data && (
        <div className={cn('flex min-w-0 flex-col gap-5 transition-opacity motion-reduce:transition-none', state.loading && 'opacity-70')}>
          {state.error && (
            <div data-slot="mo-stale-data">
              <AlertBanner tone="warning" title={t('mo.staleData.title')}
                actions={<Button type="button" variant="outline" size="sm" onClick={refresh} className="pointer-coarse:h-10">{t('mo.staleData.retry')}</Button>}>
                {t('mo.staleData.text', state.error)}
              </AlertBanner>
            </div>
          )}

          <div className="[&>[data-slot=stats-panel]]:mb-0">
            <MonitorStatsBar items={kpis} activeFilter={kpiActive} onStatClick={onStatClick} />
          </div>

          <div className={cn('grid min-w-0 grid-cols-1 gap-4', teamGroups.length > 1 && 'xl:grid-cols-5')}>
            <OverviewHealthCard totals={totals} rows={rows} nowMs={nowMs} windowLabel={windowLabel} activeStatuses={statuses}
              onPickStatus={pickStatus} onShowAll={() => { applyView('problems'); focusList() }}
              {...(teamGroups.length > 1 ? { className: 'xl:col-span-3' } : {})} />
            <OverviewTeamsCard teams={teamGroups} activeTeams={teamsSel} onPickTeam={pickTeam} className="xl:col-span-2 xl:self-start" />
          </div>

          <section aria-label={t('mo.types')} className="flex min-w-0 flex-col gap-3">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
              <h3 className="m-0 flex items-center gap-2 text-base font-semibold tracking-tight">
                <LayoutGrid aria-hidden="true" className="size-4 text-muted-foreground" />{t('mo.types')}
              </h3>
              <span className="text-xs text-muted-foreground">{t('mo.types.hint')}</span>
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {(data.types || []).map((ty) => (
                <OverviewTypeCard key={ty.type} type={ty} active={types.includes(ty.type)} nowMs={nowMs} windowLabel={windowLabel}
                  onSelect={pickType} onOpen={(tab) => navigateTo(tab)} />
              ))}
            </div>
          </section>

          <section ref={measureRef} aria-labelledby={listTitleId} className="@container/list flex min-w-0 flex-col gap-3">
            {/* Liste başlığı — kart/KPI/pencere tıklaması buraya kaydırır (scroll-mt: yapışkan başlık payı) */}
            <div ref={listRef} data-slot="mo-list-anchor" className="flex scroll-mt-24 flex-col gap-3">
              <div className="flex flex-wrap items-end justify-between gap-2">
                <div className="min-w-0">
                  <h3 id={listTitleId} className="m-0 flex items-center gap-2 text-base font-semibold tracking-tight">
                    <Activity aria-hidden="true" className="size-4 text-muted-foreground" />{t('mo.list.title')}
                  </h3>
                  <p className="m-0 mt-0.5 text-xs text-muted-foreground" data-slot="mo-count">{t('mo.count', filtered.length, rows.length)}</p>
                </div>
                <Button type="button" variant="outline" size="sm" data-slot="mo-csv" onClick={exportCsv} disabled={filtered.length === 0}
                  aria-label={t('mo.csv.aria', filtered.length)} className="pointer-coarse:h-10">
                  <Download aria-hidden="true" />{t('mo.csv.button')}
                </Button>
              </div>

              {/* Hızlı görünümler — telefonda yatay kayar (kendi kabında) */}
              <div className="-mx-1 overflow-x-auto px-1 pb-0.5 [scrollbar-width:thin]">
                <ToggleGroup type="single" variant="outline" size="sm" value={activeView} data-slot="mo-views"
                  onValueChange={(v) => applyView(v || 'all')} aria-label={t('mo.view.label')} className="w-max">
                  {QUICK_VIEWS.map((v) => (
                    <ToggleGroupItem key={v.key} value={v.key} data-view={v.key} className="gap-1.5 px-3 pointer-coarse:h-10">
                      {VIEW_LABEL[v.key]}
                      <span className="rounded-full bg-muted px-1.5 text-[11px] font-semibold text-muted-foreground tabular-nums">{viewCounts[v.key] ?? 0}</span>
                    </ToggleGroupItem>
                  ))}
                </ToggleGroup>
              </div>

              {/* Telefonda 2 sütunlu ızgara (arama tam satır, seçiciler ikişer), ≥ 640 px sarmalı tek satır. NativeSelect
                  sarmalayıcısı `w-fit` → kap `[&>*]:w-full` ile genişletilir (yoksa en uzun seçenek kadar kalır). */}
              <div className="grid grid-cols-2 items-center gap-2 sm:flex sm:flex-wrap">
                <InputGroup className="col-span-2 w-full sm:w-auto sm:max-w-xs sm:flex-1">
                  <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('mo.searchPlaceholder')} aria-label={t('mo.search')} />
                  <InputGroupAddon><Search aria-hidden="true" className="size-4" /></InputGroupAddon>
                </InputGroup>
                <div className="min-w-0 sm:w-44 [&>*]:w-full">
                <NativeSelect value={types.length > 1 ? '__multi' : type} onChange={(e) => setType(e.target.value)} aria-label={t('mo.filter.type')} className="w-full">
                  <NativeSelectOption value="">{t('mo.filter.allTypes')}</NativeSelectOption>
                  {types.length > 1 && <NativeSelectOption value="__multi" disabled>{t('mo.colf.multi', types.length)}</NativeSelectOption>}
                  {TYPE_ORDER.map((k) => <NativeSelectOption key={k} value={k}>{t(TYPE_META[k].labelKey)}</NativeSelectOption>)}
                </NativeSelect>
                </div>
                <div className="min-w-0 sm:w-44 [&>*]:w-full">
                <NativeSelect value={statuses.length > 1 ? '__multi' : status} onChange={(e) => setStatus(e.target.value)} aria-label={t('mo.filter.status')} data-slot="mo-status-filter" className="w-full">
                  <NativeSelectOption value="">{t('mo.filter.allStatuses')}</NativeSelectOption>
                  {statuses.length > 1 && <NativeSelectOption value="__multi" disabled>{t('mo.colf.multi', statuses.length)}</NativeSelectOption>}
                  {STATUS_ORDER.map((k) => <NativeSelectOption key={k} value={k}>{t(STATUS_META[k].labelKey)}</NativeSelectOption>)}
                </NativeSelect>
                </div>
                {teams.length > 1 && (
                  <div className="min-w-0 sm:w-48 [&>*]:w-full">
                  <NativeSelect value={teamsSel.length > 1 ? '__multi' : team} onChange={(e) => setTeam(e.target.value)} aria-label={t('mo.filter.team')} className="w-full">
                    <NativeSelectOption value="">{t('mo.filter.allTeams')}</NativeSelectOption>
                    {teamsSel.length > 1 && <NativeSelectOption value="__multi" disabled>{t('mo.colf.multi', teamsSel.length)}</NativeSelectOption>}
                    {teams.map(([id, name]) => <NativeSelectOption key={id} value={id}>{name}</NativeSelectOption>)}
                  </NativeSelect>
                  </div>
                )}
                {filterSheet}
                {anyFilter && (
                  <Button type="button" variant="ghost" size="sm" onClick={clearFilters} className="col-span-2 justify-self-start sm:ml-auto pointer-coarse:h-10">
                    <FilterX aria-hidden="true" />{t('mo.clearFilters')}
                  </Button>
                )}
              </div>
            </div>

            <FilterChips chips={chips} onClearAll={clearFilters} />

            {filtered.length === 0 ? (
              <StatusBlock tone="neutral" icon={rows.length === 0 ? Activity : FilterX}
                title={rows.length === 0 ? t('mo.empty') : t('mo.emptyFiltered')}
                description={rows.length === 0 ? t('mo.emptyText') : t('mo.emptyFilteredText')} className="py-12"
                actions={anyFilter ? <Button type="button" variant="outline" onClick={clearFilters}><FilterX aria-hidden="true" />{t('mo.clearFilters')}</Button> : null} />
            ) : tableMode ? (
              <OverviewTable rows={pager.pageItems} nowMs={nowMs} sort={sort} onSort={onSort} groups={columnGroups} />
            ) : (
              <OverviewCards rows={pager.pageItems} nowMs={nowMs} />
            )}
            {filtered.length > 0 && <PaginationBar {...pager} />}
          </section>

          <OverviewKpiDialog kind={kpi} rows={rows} totals={totals} nowMs={nowMs} onClose={() => setKpi('')} onFilter={onKpiFilter} />
        </div>
      )}
    </div>
  )
}
