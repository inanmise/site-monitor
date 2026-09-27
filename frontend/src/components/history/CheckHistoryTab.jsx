import { Children, Fragment, isValidElement, useEffect, useMemo, useState } from 'react'
import {
  Activity, BellOff, BellRing, Calendar, ChevronDown, CircleCheck, Download, ExternalLink, Inbox, Siren, TriangleAlert,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { api, formatDateSec, formatDateOnly } from '../../api/client'
import { localDayKey } from '../../utils/localDay.js'
import { navigateTo } from '../../utils/navigate.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import DensityStrip from './DensityStrip.jsx'
import OutageTimeline, { formatDuration, ms, summarizeOutages } from './OutageTimeline.jsx'
import { isOutageAlert } from '../../utils/alertKinds.js'
import useCheckHistory from './useCheckHistory.js'
import useUrlQuerySync from '../../hooks/useUrlQuerySync.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

// Sayaç rozeti (kutucuk ve grup düğmesi).
const COUNT = 'h-4 rounded-full px-1.5 py-0 text-[11px] font-bold leading-4'

// Alarm olay kartı tonları — TAM çerçeve + hafif zemin (eski `.hist-alert-row` sol 3 px renkli şeritti; kullanıcı
// kuralı 2026-09-26: detay penceresinde sol renk şeridi YOK).
const ALERT_ROW = {
  triggered: 'border-destructive/40 bg-destructive/[0.07] text-destructive',
  advisory: 'border-amber-600/40 bg-amber-500/[0.08] text-amber-700 dark:text-amber-400',
  resolved: 'border-success/40 bg-success/[0.07] text-success',
}
const LEVEL_VARIANT = { CRITICAL: 'destructive', HIGH: 'warning', WARNING: 'warning' }

// Kutucuk tonları — MonitorStatsBar sözlüğünün aynısı (ton = değer/ikon rengi + hafif zemin, şerit yok).
const TILE_TONE = {
  total:   { text: 'text-foreground', bg: 'bg-muted/40' },
  success: { text: 'text-success', bg: 'bg-success/10 dark:bg-success/15' },
  warning: { text: 'text-amber-600 dark:text-amber-400', bg: 'bg-amber-500/10' },
  danger:  { text: 'text-red-600 dark:text-red-400', bg: 'bg-red-500/[0.08]' },
}

const TH = 'h-8 px-2.5 text-[11px] font-bold tracking-[.04em] text-muted-foreground uppercase'
// `[&>*]:max-w-full`: hücre içeriği (ör. HTTP'nin uzun hata metinli "ayrıntı" düğmesi — shadcn Button nowrap) kabını
// aşmasın, kendi içinde kırpılsın. Son sütun kalan genişliği alır (`w-full max-w-0`: otomatik tablo düzeninde yalnız
// ARTAN genişliği alır) — uzun ayrıntı metni tabloyu taşırmaz, sarar ya da kırpılır.
// `[&>.truncate]:block`: sayfaların hücreleri çoğu zaman satır içi <span className="truncate"> — satır içi öğede
// overflow/max-width İŞLEMEZ, uzun hata metni kartı/hücreyi taşırıyordu (Alan Adı detayı 390 px, 2026-09-27). Blok yapınca kırpılır.
const TD = 'px-2.5 py-1.5 align-top text-xs [&>*]:max-w-full [&>.truncate]:block'
const TD_LAST = 'w-full max-w-0 min-w-[9rem] whitespace-normal'
const SLOT = 'block min-w-0 [&>*]:max-w-full [&>.truncate]:block'
const DAY_SEP = 'text-[11px] font-bold tracking-[.04em] text-muted-foreground uppercase'

/**
 * `renderRow` sözleşmesi: sayfa hücreleri bir Fragment içinde sırayla verir (eski CSS ızgarası da böyle
 * yerleştiriyordu). Tabloda her çocuk bir TableCell, telefon kartında etiket + değer satırı olur.
 */
function cellsOf(node) {
  if (node == null || node === false) return []
  if (Array.isArray(node)) return node
  if (isValidElement(node) && node.type === Fragment) return Children.toArray(node.props.children)
  return [node]
}

/** Özet kutucuğu — süzgeç olanı shadcn Button (aria-pressed), diğerleri salt gösterim. */
function HistTile({ icon: Icon, label, value, sub, tone = 'total', pressed, onClick, hint }) {
  const tn = TILE_TONE[tone] ?? TILE_TONE.total
  const body = (<>
    <Icon aria-hidden="true" className={cn('size-4 shrink-0', tn.text)} />
    <span className="flex min-w-0 flex-1 flex-col items-start gap-px text-left">
      <span data-slot="hist-tile-value" className={cn('text-lg leading-none font-extrabold tracking-[-.02em] tabular-nums', tn.text)}>{value}</span>
      <span className="text-[11px] leading-tight font-semibold text-muted-foreground">{label}</span>
      {sub && <span data-slot="hist-tile-sub" className="text-[10.5px] leading-tight text-muted-foreground">{sub}</span>}
    </span>
  </>)
  const cls = cn('flex min-h-14 min-w-0 items-center gap-2.5 rounded-lg border px-2.5 py-2', tn.bg)
  if (!onClick) {
    const el = <div data-slot="hist-tile" data-tone={tone} className={cls}>{body}</div>
    return hint ? <SimpleTooltip content={hint}>{el}</SimpleTooltip> : el
  }
  return (
    <Button type="button" variant="ghost" data-slot="hist-tile" data-tone={tone} aria-pressed={pressed} title={hint}
      onClick={onClick}
      className={cn(cls, 'h-auto justify-start whitespace-normal hover:bg-accent/60 dark:hover:bg-accent/40',
        pressed && 'border-primary ring-2 ring-primary/40')}>
      {body}
    </Button>
  )
}

/** Alarm olayı kartı — açılış/çözüm/uyarı. Seviye rozeti, süre, ileti (görünür metin — dokunmatikte ipucu yok). */
function AlertEventCard({ t, type, alert: a, ts }) {
  const triggered = type === 'triggered'
  const kind = triggered ? (isOutageAlert(a.alert_type) ? 'triggered' : 'advisory') : 'resolved'
  const Icon = triggered ? BellRing : BellOff
  const title = t(triggered ? (isOutageAlert(a.alert_type) ? 'hist.alertTriggered' : 'hist.advisoryTriggered') : 'hist.alertResolved')
  const started = ms(a.created_at), ended = ms(a.resolved_at)
  const duration = !triggered && Number.isFinite(started) && Number.isFinite(ended) && ended > started
    ? formatDuration((ended - started) / 60000, t) : null
  return (
    <div data-slot="hist-alert-row" data-kind={kind}
      className={cn('flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 rounded-md border px-2.5 py-1.5 text-xs', ALERT_ROW[kind])}>
      <Icon size={13} aria-hidden="true" className="shrink-0" />
      <span className="font-semibold">{title}</span>
      <Badge variant="outline" className="h-5 border-current/30 px-1.5 font-mono text-[10.5px] font-semibold text-current">{a.alert_type}</Badge>
      {a.alert_level && (
        <Badge variant={LEVEL_VARIANT[a.alert_level] ?? 'secondary'} data-level={a.alert_level} className="h-5 px-1.5 text-[10.5px] font-bold">
          {a.alert_level}
        </Badge>
      )}
      {duration && <span className="opacity-80">{t('hist.alertDuration', duration)}</span>}
      {a.message && <span className="min-w-0 flex-1 basis-40 truncate opacity-80" title={a.message}>{a.message}</span>}
      <span className="ml-auto shrink-0 tabular-nums opacity-80">{formatDateSec(ts)}</span>
      {a.id != null && (
        <SimpleTooltip content={t('hist.openAlert')}>
          <Button type="button" variant="ghost" size="icon-xs" className="shrink-0 text-current hover:bg-black/5 hover:text-current pointer-coarse:size-9 dark:hover:bg-white/10"
            aria-label={t('a11y.rowAction', `${title} · ${a.alert_type}`, t('hist.openAlert'))}
            onClick={() => navigateTo('alerthistory', { incident: a.id })}>
            <ExternalLink aria-hidden="true" />
          </Button>
        </SimpleTooltip>
      )}
    </div>
  )
}

/**
 * Hücreleri sütunlara böler: ilk `columns.length` hücre sütunlardır; FAZLASI tam genişlikte ek içeriktir (DNS'in
 * değer farkı paneli — eski ızgarada `grid-column: 1 / -1` ile satırın altına iniyordu). Sütunsuz çağrıda hepsi hücre.
 */
function splitCells(cells, columns) {
  const n = columns.length || cells.length
  return [cells.slice(0, n), cells.slice(n)]
}

/** Telefon kartı: ilk iki hücre (zaman + durum) başlık satırı, kalanlar sütun etiketiyle alt alta, ek içerik en altta. */
function CheckCard({ cells, columns }) {
  const [main, extra] = splitCells(cells, columns)
  return (
    <Card data-slot="hist-card" className="min-w-0 gap-1.5 px-3 py-2.5 text-xs shadow-none">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className={SLOT}>{main[0]}</span>
        {main.length > 1 && <span className={SLOT}>{main[1]}</span>}
      </div>
      {main.slice(2).map((c, i) => (
        <div key={i} className="flex min-w-0 gap-2">
          {columns[i + 2] ? <span className="w-24 shrink-0 text-muted-foreground">{columns[i + 2]}</span> : null}
          <span className={cn(SLOT, 'flex-1 [overflow-wrap:anywhere]')}>{c}</span>
        </div>
      ))}
      {extra.map((c, i) => <div key={`x${i}`} data-slot="hist-row-extra" className={SLOT}>{c}</div>)}
    </Card>
  )
}

/**
 * Kontrol Geçmişi v2 — TÜM izleme türlerinin paylaşılan geçmiş sekmesi.
 * Eski desen (7 sayfada kopyalanan range-buton + client-side sayfalama + 500 kayıt tavanı) yerine:
 * segmented aralık + özel tarih/saat aralığı + özet kutucukları (kontrol / hata süzgeci / erişilebilirlik /
 * kesinti) + yoğunluk şeridi (tıkla→zoom) + kesinti zaman çizelgesi + gün ayırıcıları + alarm olay kartları +
 * server-side sayfalama + CSV + canlı yenileme.
 *
 * <p>2026-09-27 (shadcn + mobil web): satırlar md+ ekranda shadcn Table (sütun başlıkları `columns`), telefonda
 * Card listesi (`useIsMobile` — TEK varyant çizilir; jsdom medya sorgusu uygulamaz). Tip-özel olan yalnız üç şey
 * dışarıdan gelir: kolon başlıkları (columns), satır hücreleri (renderRow — Fragment içinde sıralı hücreler) ve
 * geriye uyum için `gridClass` (artık yalnız `data-grid` kancası; yerleşimi Table verir).
 */
export default function CheckHistoryTab({
  kind, monitorId, listKey,
  presets = [1, 7, 15, 30], defaultPreset = 1,
  filterMode = 'fail',                       // 'fail' | 'changed' | 'none'
  columns = [], gridClass = '', renderRow,
  extraParams = null,                        // uptime-http: { port }
  csv = true, live = true, urlSync = true,
  reloadSignal = 0,                          // dışarıdan tazeleme (modaldaki "Çalıştır") — sayfa/filtre korunur
  onCounts = null,                           // modal başlık özeti için {total, fail} bildirimi
  range = null, onRangeChange = null,        // kontrollü aralık (Uptime: tek picker iki kolonu sürer)
  timeline = true,                            // kesinti zaman çizelgesi (alan adı: kapalı — kesinti değil süre izlenir; 2026-09-22)
  renderAbove = null,                         // aralık değişince yeniden çizilen özel blok, ör. kalan-gün trendi (h.preset/h.range verilir)
  // ── Ardışık aynı sonuçları tek satırda topla (OPT-IN, varsayılan KAPALI) ──
  // Sürekli aynı hatayı veren bir monitörde geçmiş, aynı satırın yüzlerce kopyasına dönüşüyor
  // (gerçek vaka: 291 kaydın 291'i aynı k6 sözdizimi hatası). Varsayılanı kapalı tutmak şart:
  // bu bileşen 9 izleme sayfasında ortak, diğerlerinin davranışı bit düzeyinde aynı kalmalı.
  groupIdenticalErrors = false,
  rowSignature = null,                       // (item) => string|null ; null ⇒ o satır gruplanmaz
}) {
  const t = useT()
  const isMobile = useIsMobile()
  const h = useCheckHistory({ kind, id: monitorId, listKey, presets, defaultPreset, filterMode, extraParams,
    live: range ? false : live, fixed: range, reloadSignal })
  const [showPicker, setShowPicker] = useState(false)
  const [openGroups, setOpenGroups] = useState({})
  const fixedMode = !!range
  // Özel aralık seçicisinin varsayılan uçları KARARLI (2026-09-27 regresyon B1): izleme sayfaları saniyede bir
  // yeniden çiziliyor; her çizimde `new Date()` geçmek seçicinin taslağını (kullanıcının seçtiği ilk Başlangıç)
  // 1 sn içinde sıfırlıyordu. "Şimdi" seçici AÇILDIĞINDA bir kez alınır, açık kaldıkça sabit kalır.
  const pickerOpen = !fixedMode && h.preset === 'custom' && (showPicker || !h.customFrom)
  const pickerRange = useMemo(() => (pickerOpen ? {
    from: h.customFrom ?? new Date(Date.now() - 86400000),
    to: h.customTo ?? new Date(),
  } : null), [pickerOpen, h.customFrom, h.customTo])

  useEffect(() => { if (h.counts) onCounts?.(h.counts) },   // sayfa üstbilgisi (%OK / toplam / hata)
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [h.counts?.total, h.counts?.fail])

  // ── URL senkronu: range + hfrom/hto/hst (paylaşılabilir link) — modal kapanınca temizlenir ──
  useUrlQuerySync({
    range: fixedMode || h.preset === defaultPreset ? null : String(h.preset),
    hfrom: !fixedMode && h.preset === 'custom' && h.customFrom ? h.customFrom.toISOString().slice(0, 19) : null,
    hto:   !fixedMode && h.preset === 'custom' && h.customTo   ? h.customTo.toISOString().slice(0, 19)   : null,
    hst:   h.status === 'all' ? null : h.status,
  }, { enabled: urlSync && !fixedMode })
  useEffect(() => () => {
    if (!urlSync) return
    try {   // unmount: bu bileşenin paramları URL'de kalmasın (sekme-değişimi temizliğini beklemeden)
      const url = new URL(window.location.href)
      let changed = false
      for (const k of ['range', 'hfrom', 'hto', 'hst']) {
        if (url.searchParams.has(k)) { url.searchParams.delete(k); changed = true }
      }
      if (changed) {
        const qs = url.searchParams.toString()
        window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
      }
    } catch { /* en iyi çaba */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ── Retention/clamp bildirimi: istenen from, dönen range.from'dan gerideyse kırpılmıştır ──
  const clampedFrom = useMemo(() => {
    if (!h.range?.from) return null
    let requested
    if (h.preset === 'custom' && h.customFrom) requested = h.customFrom.toISOString().slice(0, 19)
    else if (typeof h.preset === 'number') {
      requested = new Date(Date.now() - h.preset * 86400000).toISOString().slice(0, 19)
    } else return null
    // 2 saatlik tolerans — saat farkları/istek gecikmesi sahte uyarı üretmesin
    return (h.range.from.localeCompare(requested) > 0
        && (new Date(h.range.from + 'Z') - new Date(requested + 'Z')) > 2 * 3600000)
      ? h.range.from : null
  }, [h.range, h.preset, h.customFrom])

  // ── Sayfa satırları: gün ayırıcıları + alarm işaret satırları serpiştirilmiş ──
  const rows = useMemo(() => {
    const items = h.items
    const events = []
    for (const a of h.alerts) {
      if (a.created_at) events.push({ ts: a.created_at, ev: 'triggered', a })
      if (a.resolved_at) events.push({ ts: a.resolved_at, ev: 'resolved', a })
    }
    events.sort((x, y) => String(y.ts).localeCompare(String(x.ts)))
    const out = []
    let ei = 0
    let lastDay = null
    const itemTs = (c) => c.checked_at || c.checkedAt || ''
    // Sayfa penceresi dışındaki işaretler atlanır (başka sayfaya aittir); 1. sayfada en-üst pencere açık.
    if (items.length > 0 && h.page > 1) {
      while (ei < events.length && String(events[ei].ts).localeCompare(itemTs(items[0])) > 0) ei++
    }
    items.forEach((c, i) => {
      while (ei < events.length && String(events[ei].ts).localeCompare(itemTs(c)) >= 0) {
        out.push({ type: events[ei].ev, key: `ev-${events[ei].a.id}-${events[ei].ev}`, alert: events[ei].a, ts: events[ei].ts })
        ei++
      }
      const day = localDayKey(itemTs(c))   // satırlar yerel saat gösterir; gün başlığı da yerel gün olmalı (UTC dilimlemesi 00:00–03:00 kontrollerini önceki güne atıyordu)
      if (day && day !== lastDay) { out.push({ type: 'day', key: `day-${day}`, day }); lastDay = day }
      out.push({ type: 'item', key: `it-${itemTs(c)}#${i}`, item: c, index: i })
    })
    if (!groupIdenticalErrors || !rowSignature) return out

    // YAN YANA duran, imzası eşit item satırlarını topla. Gün ayırıcısı veya alarm işaret satırı
    // araya girerse grup KIRILIR — zaman/alarm bağlamı gruplamaya feda edilmemeli.
    const grouped = []
    for (const row of out) {
      const sig = row.type === 'item' ? rowSignature(row.item) : null
      const prev = grouped[grouped.length - 1]
      if (sig != null && prev && prev.type === 'group' && prev.sig === sig) {
        prev.rows.push(row)
        continue
      }
      grouped.push(sig == null ? row : { type: 'group', key: `grp-${row.key}`, sig, rows: [row] })
    }
    // Tek elemanlı "grup" diye bir şey yok — normal satıra geri döndür.
    return grouped.map(r => (r.type === 'group' && r.rows.length === 1 ? r.rows[0] : r))
  }, [h.items, h.alerts, h.page, groupIdenticalErrors, rowSignature])

  const presetOptions = [
    ...presets.map(d => ({ value: d, label: t(`hist.range${d}d`) })),
    { value: 'custom', label: t('hist.rangeCustom'), icon: Calendar },
  ]

  // ── Özet kutucukları: kontrol / hata (süzgeç) · erişilebilirlik · kesinti ──
  const total = Number(h.counts.total) || 0
  const fail = Number(h.counts.fail) || 0
  const outage = useMemo(() => summarizeOutages(h.alerts, h.range), [h.alerts, h.range])
  // 'changed' kipinde (DNS) ikinci sayaç DEĞİŞEN kayıt sayısıdır, hata değil — erişilebilirliği ondan türetmek
  // kayıt rotasyonunu kesinti gibi gösterirdi. O kipte oran zaman tabanlıdır (açık kesinti alarmı olmayan süre).
  const changedMode = filterMode === 'changed'
  const availability = changedMode ? (outage ? outage.availability : null)
    : total > 0 ? ((total - fail) * 100) / total : null
  const statusValue = h.status === 'all' ? 'all' : 'fail'
  const filterable = filterMode !== 'none'
  const failLabel = t(filterMode === 'changed' ? 'hist.filterChanged' : 'hist.filterFail')
  const tiles = (
    <div data-slot="hist-tiles" role={filterable ? 'group' : undefined} aria-label={filterable ? t('hist.filterLabel') : undefined}
      className="grid grid-cols-2 gap-2 @xl/hist:grid-cols-4">
      <HistTile icon={Activity} label={t('hist.tileChecks')} value={total.toLocaleString()} tone="total"
        pressed={filterable ? statusValue === 'all' : undefined}
        onClick={filterable ? () => h.setStatus('all') : undefined} hint={filterable ? t('hist.filterAllHint') : undefined} />
      <HistTile icon={TriangleAlert} label={failLabel} value={fail.toLocaleString()} tone={fail > 0 ? (changedMode ? 'warning' : 'danger') : 'total'}
        pressed={filterable ? statusValue === 'fail' : undefined}
        onClick={filterable ? () => h.setStatus(filterMode) : undefined} hint={filterable ? t('hist.filterFailHint', failLabel) : undefined} />
      <HistTile icon={CircleCheck} label={t('hist.tileAvailability')}
        value={availability == null ? '—' : `${availability >= 99.995 ? '100' : availability.toFixed(2)}%`}
        tone={availability == null ? 'total' : availability >= 99.9 ? 'success' : availability >= 99 ? 'warning' : 'danger'}
        hint={t(changedMode ? 'hist.tileAvailabilityTimeHint' : 'hist.tileAvailabilityHint')} />
      <HistTile icon={Siren} label={t('hist.tileOutages')} value={outage ? String(outage.segs.length) : '—'}
        sub={outage && outage.segs.length > 0 ? formatDuration(outage.downMinutes, t) : undefined}
        tone={!outage || outage.segs.length === 0 ? 'success' : 'danger'} hint={t('hist.tileOutagesHint')} />
    </div>
  )

  const n = Math.max(1, columns.length)
  const cellsRow = (item, index) => cellsOf(renderRow(item, { index }))
  /** Bir kontrolün tablo satırı (+ sütun sayısını aşan hücreler varsa altında tam genişlik ek satır). */
  const itemRows = (key, item, index, cls) => {
    const [main, extra] = splitCells(cellsRow(item, index), columns)
    return (
      <Fragment key={key}>
        <TableRow className={cls}>
          {main.map((c, i) => <TableCell key={i} className={cn(TD, i === main.length - 1 && main.length > 1 && TD_LAST)}>{c}</TableCell>)}
        </TableRow>
        {extra.length > 0 && (
          <TableRow data-slot="hist-row-extra" className={cn(cls, 'hover:bg-transparent')}>
            <TableCell colSpan={n} className={cn(TD, 'pt-0 whitespace-normal')}>{extra}</TableCell>
          </TableRow>
        )}
      </Fragment>
    )
  }

  /** Grup başlığı düğmesi — sayı SAYFA İÇİDİR (sayfa başına {pageSize} kayıt); gerçek toplam kutucuklarda. */
  const groupToggle = (r, open) => (
    <Button type="button" variant="ghost" size="xs" aria-expanded={open}
      onClick={() => setOpenGroups(g => ({ ...g, [r.key]: !open }))}
      className="h-auto w-full justify-start gap-1.5 rounded-sm px-2 py-1 text-[11.5px] font-normal text-muted-foreground hover:bg-transparent hover:text-foreground pointer-coarse:min-h-10 dark:hover:bg-transparent">
      <Badge variant="secondary" className={COUNT}>{r.rows.length}×</Badge>
      <span>{open ? t('hist.groupCollapse') : t('hist.groupExpand')}</span>
      <ChevronDown aria-hidden="true" className={cn('size-3.5 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
    </Button>
  )

  const tableBody = rows.map(r => {
    if (r.type === 'day') {
      return (
        <TableRow key={r.key} data-slot="hist-day-sep" className="hover:bg-transparent">
          <TableCell colSpan={n} className={cn(DAY_SEP, 'bg-muted/40 px-2.5 py-1')}>{formatDateOnly(r.day + 'T12:00:00')}</TableCell>
        </TableRow>
      )
    }
    if (r.type === 'triggered' || r.type === 'resolved') {
      return (
        <TableRow key={r.key} className="hover:bg-transparent">
          <TableCell colSpan={n} className="p-1.5"><AlertEventCard t={t} type={r.type} alert={r.alert} ts={r.ts} /></TableCell>
        </TableRow>
      )
    }
    if (r.type === 'group') {
      const open = !!openGroups[r.key]
      const [first, ...rest] = r.rows
      return (
        <Fragment key={r.key}>
          {itemRows('lead', first.item, first.index, 'bg-muted/30')}
          <TableRow data-slot="hist-group-toggle" className="bg-muted/30 hover:bg-muted/30">
            <TableCell colSpan={n} className="px-1 py-0">{groupToggle(r, open)}</TableCell>
          </TableRow>
          {open && rest.map(row => itemRows(row.key, row.item, row.index, 'bg-muted/20'))}
        </Fragment>
      )
    }
    return itemRows(r.key, r.item, r.index)
  })

  const cardBody = rows.map(r => {
    if (r.type === 'day') return <li key={r.key} data-slot="hist-day-sep" className={cn(DAY_SEP, 'pt-1')}>{formatDateOnly(r.day + 'T12:00:00')}</li>
    if (r.type === 'triggered' || r.type === 'resolved') {
      return <li key={r.key} className="min-w-0"><AlertEventCard t={t} type={r.type} alert={r.alert} ts={r.ts} /></li>
    }
    if (r.type === 'group') {
      const open = !!openGroups[r.key]
      const [first, ...rest] = r.rows
      return (
        <li key={r.key} className="flex min-w-0 flex-col gap-1.5 rounded-lg bg-muted/40 p-1.5">
          <CheckCard cells={cellsRow(first.item, first.index)} columns={columns} />
          {groupToggle(r, open)}
          {open && rest.map(row => <CheckCard key={row.key} cells={cellsRow(row.item, row.index)} columns={columns} />)}
        </li>
      )
    }
    return <li key={r.key} className="min-w-0"><CheckCard cells={cellsRow(r.item, r.index)} columns={columns} /></li>
  })

  return (
    // `@container/hist`: kutucuk ızgarası görünüm alanına değil KABA göre (Uptime'da iki geçmiş yan yana ~450 px)
    <div data-slot="check-history" data-grid={gridClass || undefined} className="@container/hist flex min-w-0 flex-col gap-3">
      {/* Araç çubuğu: aralık ön ayarları (telefonda kendi kabında kayar) · sağda canlı rozeti + CSV */}
      <div data-slot="hist-toolbar" className="flex min-w-0 flex-wrap items-center gap-2">
        {!fixedMode && (
          // Bitişik 5 düğmelik aralık seçici sarmaz (w-fit): telefonda (≈310 px) sağa taşıyordu → kendi kabında yatay kayar.
          <div data-slot="hist-range-scroll" className="max-w-full min-w-0 overflow-x-auto overscroll-x-contain rounded-lg [scrollbar-width:thin]">
            <SegmentedControl ariaLabel={t('hist.rangeLabel')} options={presetOptions} className="w-max flex-nowrap"
              value={h.preset} onChange={(v) => { h.setPreset(v); setShowPicker(v === 'custom') }} />
          </div>
        )}
        <span className="flex-1" />
        {/* Hata varken "Canlı" rozeti GÖSTERİLMEZ. useCheckHistory hatayı error'a yazarken eski
            data'yı koruyor; 30 sn'lik sessiz yenileme 500/403/timeout almaya başladığında ekranda
            eski kayıtlar duruyor ve rozet yanıp sönmeye devam ediyordu — kullanıcı bayat veriye
            canlı veri diye bakıyordu ("bilinmiyor" ile "sorun yok" aynı ekrana düşmemeli). */}
        {h.liveActive && !h.error && (
          <SimpleTooltip content={t('hist.liveHint')}>
            <Badge variant="outline" data-slot="hist-live" className="gap-1.5 font-normal text-muted-foreground">
              <span aria-hidden="true" className="size-[7px] rounded-full bg-success animate-pulse motion-reduce:animate-none" />
              {t('hist.live')}
            </Badge>
          </SimpleTooltip>
        )}
        {h.liveActive && h.error && (
          <SimpleTooltip content={String(h.error)}>
            <Badge variant="warning" data-slot="hist-live" data-stale="true" className="font-normal">{t('hist.liveStale')}</Badge>
          </SimpleTooltip>
        )}
        {csv && h.total > 0 && (
          <SimpleTooltip content={t('hist.exportCsvHint')}>
            <Button asChild variant="outline" size="sm" className="font-semibold pointer-coarse:h-10">
              <a href={api.monitoring.getCheckHistoryCsvUrl(kind, monitorId, h.csvParams)} download data-slot="hist-csv">
                <Download aria-hidden="true" /> CSV
              </a>
            </Button>
          </SimpleTooltip>
        )}
      </div>

      {pickerOpen && pickerRange && (
        <DateTimeRangePicker
          from={pickerRange.from}
          to={pickerRange.to}
          onApply={(f, to) => { h.setCustomRange(f, to); setShowPicker(false) }} />
      )}

      {tiles}

      {clampedFrom && (
        <AlertBanner tone="warning" className="my-0 py-2 text-xs">{t('hist.clampedNotice', formatDateSec(clampedFrom))}</AlertBanner>
      )}

      {/* Saklama şeffaflığı: veri hangi tarihten beri tutuluyor, elde en yeni kayıt hangisi.
          Süre Ayarlar → Veri Saklama'dan değişince bu satır anında güncellenir. Nötr ton (bilgi, uyarı değil). */}
      {h.oldestAt && (
        <p data-slot="hist-retention" className="m-0 rounded-md bg-muted/50 px-2.5 py-1.5 text-xs text-muted-foreground">
          {t('hist.retentionInfo', formatDateSec(h.oldestAt), h.retentionDays ?? '—',
             h.newestAt ? formatDateSec(h.newestAt) : '—')}
        </p>
      )}

      {/* Kesinti zaman çizelgesi (2026-09-12, #12): alarm açılış→çözüm segmentleri, süre + Alarm Geçmişi bağlantısı */}
      {renderAbove && renderAbove({ preset: h.preset, range: h.range })}
      {timeline && <OutageTimeline alerts={h.alerts} range={h.range} />}
      <DensityStrip buckets={h.buckets} zoomed={!fixedMode && h.preset === 'custom'}
        onZoom={(fromIso, toIso) => {
          if (fixedMode) { onRangeChange?.(new Date(fromIso + 'Z'), new Date(toIso + 'Z')); return }
          h.setCustomRange(new Date(fromIso + 'Z'), new Date(toIso + 'Z')); setShowPicker(false)
        }}
        onReset={() => h.setPreset(defaultPreset)} />

      {/* HATA DALI: useCheckHistory `error` state'ini üretiyordu ama burada HİÇ okunmuyordu →
          500/403/timeout'ta kullanıcı "Kayıt yok" görüyor ve monitörün hiç kontrol edilmediğini
          sanıyordu. Gerçek arıza, veri yokluğu gibi görünüyordu (9 izleme türünü birden etkiler). */}
      {h.loading && h.items.length === 0 ? (
        <LoadingBlock label={t('modal.loading')} />
      ) : h.error && h.items.length === 0 ? (
        <AlertBanner tone="danger" title={t('hist.loadError')} role="alert"
          /* onClick={h.reload} DEĞİL: h.reload = load(silent = false) ve React SyntheticEvent
             argüman olarak gidince silent truthy oluyor, `if (!silent) setLoading(true)` atlanıyor
             ve "Yeniden dene"ye basınca ekranda HİÇBİR ŞEY değişmiyordu (spinner yok, hata bandı
             aynı) — kullanıcı düğmenin bozuk olduğunu sanıp basmaya devam ediyor, her basış
             gerçek bir istek atıyordu. */
          actions={<Button variant="secondary" size="sm" onClick={() => h.reload()}>{t('hist.retry')}</Button>}>
          {String(h.error)}
        </AlertBanner>
      ) : h.items.length === 0 ? (
        /* LoadingBlock DEĞİL: o koşulsuz spinner + role="status" basıyor, yani gerçekten
           veri olmayan monitörde ekranda SONSUZA KADAR dönen bir spinner + "Kayıt yok" duruyordu. */
        <StatusBlock tone="neutral" icon={Inbox} title={t('hist.noData')} className="rounded-lg border border-dashed" />
      ) : (
        <div data-slot="hist-list" className="flex min-w-0 flex-col gap-2">
          {isMobile ? (
            <ul data-slot="hist-rows" data-view="cards" className="m-0 flex list-none flex-col gap-2 p-0">{cardBody}</ul>
          ) : (
            <div data-slot="hist-rows" data-view="table" className="overflow-hidden rounded-lg border bg-card">
              <Table className="text-xs">
                <TableHeader className="bg-muted/50">
                  <TableRow className="hover:bg-transparent">
                    {columns.map((c, i) => <TableHead key={i} className={TH}>{c}</TableHead>)}
                  </TableRow>
                </TableHeader>
                <TableBody>{tableBody}</TableBody>
              </Table>
            </div>
          )}
          {/* Standart sunucu çubuğu (useCheckHistory → useServerPagination, modal ön ayarı: compact) */}
          <PaginationBar {...h.bar} />
        </div>
      )}
    </div>
  )
}
