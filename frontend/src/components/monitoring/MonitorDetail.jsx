import { useCallback, useEffect, useRef, useState } from 'react'
import {
  BellRing, BookOpen, ChartLine, FileWarning, GitBranch, Globe, History, Layers, ScrollText, Stethoscope,
} from 'lucide-react'
import { api } from '../../api/client'
import { readUrlParam } from '../../hooks/useUrlQuerySync.js'
import ModalShell from '../ui/ModalShell.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import NocStatus from '../noc/NocStatus.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Separator } from '@/components/shadcn/separator'
import { Tabs, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

/**
 * İzleme DETAY penceresi ailesi — dokuz izleme sayfasının detay modalı (eski `.upt-modal*`) ortak
 * shadcn parçalarla: kabuk `ui/ModalShell` (Dialog), sekmeler shadcn Tabs, özet/istek kutusu Card.
 *
 * <p>Kabuk davranışı ModalShell'den gelir: odak tuzağı + iade, Escape (üstte açık bir iç pencere —
 * ör. HTTP tanı penceresi, düzenleme formu — varsa yalnız o kapanır), örtüye tıklama kapatır. Eski
 * `useEscapeKey` kancası bu yüzden artık gerekmez. `actions` verildiğinde kapatma düğmesi başlıktaki
 * eylem grubunun (MonitorModalActions) X'idir — kabuğun kendi X'i gizlenir, iki kapat düğmesi olmaz.
 */

// Detay penceresinde de SOL RENK KENARI YOK (kullanıcı kuralı 2026-09-26) — durum başlıktaki rozetle.

/**
 * `subtitle`: başlığın hemen altındaki ikincil satır (Ping/Port uç nokta çipi, DNS "DNS Detayları"). Başlık
 * satırına sıkıştırılınca telefonda gizleniyordu (`hidden sm:inline`); artık her genişlikte kendi satırında,
 * pencere adına (aria-labelledby) karışmadan gövdenin başında durur.
 *
 * `noc` (2026-09-28; eski `nocNotify` rozetinin yerine): `{ type, monitor, canEdit }` — KARTLA AYNI 7/24 göstergesi
 * (noc/NocStatus, Zengin hap): açık / açık · iletilmiyor / kapalı, dokun-gör açıklama + "7/24 ayarını düzenle" ya da
 * "7/24 Kapsamı'nda gör". Kart ile pencere böylece hiç ayrışmaz. Pencere adına (DialogTitle) KARIŞMAZ, başlık satırında
 * eylem grubunun solunda durur.
 */
export function MonitorDetailModal({ open = true, onClose, status = 'unknown', badge, title, subtitle, actions, noc = null, className, children }) {
  return (
    <ModalShell
      open={open}
      onClose={onClose}
      // Eylem grubu (MonitorModalActions) kendi X'ini taşır → kabuğun X'i gizlenir. Eylem grubu
      // olmayan detay penceresi (Uptime) kabuğun i18n'li X'ini kullanır.
      hideClose={Boolean(actions)}
      size="lg"
      // SABİT BOYUT (2026-09-28, kullanıcı: "Domain kaydı → Alarm geçmişi sekmesine geçince pencere küçülüyor,
      // değişiyor, titriyor"): kutu yüksekliği sekme içeriğine göre değişiyor, dikey ortalı pencere her değişimde
      // yeniden konumlanıyordu (yükleniyor → liste geçişinde iki kez). Artık geniş ekranda sabit yükseklik, başlık +
      // eylemler sabit, YALNIZ gövde kayar (`scrollBody`); kaydırma çubuğu için yer ayrılır (genişlik oynamaz).
      scrollBody
      data-status={status}
      // Telefonda başlık satırı SARAR: eylem grubu (dokunmatikte 40 px düğmeler) başlığı sıfıra ezmesin, kendi
      // satırına sağa yaslı insin. Geniş ekranda eski düzen (tek satır, uzun başlık kırpılır).
      // `grid-cols-[minmax(0,1fr)]`: Dialog kutusu bir ızgara; `auto` sütun en uzun kırılmaz metne (uzun başlık) göre
      // genişleyip pencereyi telefonda ekrandan taşırıyordu (Sentetik detayı 439 px, 2026-09-26 responsive ölçümü).
      // GENİŞLİK (2026-09-30, kullanıcı: "sentetik kartına tıklayınca üst sekmeler taşıyor, yatay kaydırma açılıyor"):
      // 7 sekme (ikon + sayaç) 960 px'e sığmıyordu → geniş ekranda 1140 px (ModalShell `xl` ile aynı ölçü); sekme
      // listesi de artık SARAR (DetailTabs), hiçbir genişlikte yatay kaydırma yok.
      className={cn('grid-cols-[minmax(0,1fr)] sm:max-w-[min(1140px,calc(100%-2rem))] [&>[data-slot=dialog-header]]:flex-wrap sm:[&>[data-slot=dialog-header]]:flex-nowrap',
        'sm:h-[min(88vh,calc(100dvh-2rem))] sm:w-full [&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable]', className)}
      title={<>{badge}<span className="min-w-0 truncate text-lg font-bold tracking-[-.02em]">{title}</span></>}
      headerExtra={(actions || noc) ? <>
        {noc && <NocStatus {...noc} />}
        {actions && <div className="ml-auto flex shrink-0 items-center">{actions}</div>}
      </> : null}
    >
      {subtitle && <div data-slot="monitor-detail-subtitle" className="-mt-1 mb-2 min-w-0 text-sm text-muted-foreground">{subtitle}</div>}
      {children}
    </ModalShell>
  )
}

/** Bölüm ayracı (eski .upt-modal-divider). */
export function DetailDivider({ className }) {
  return <Separator className={cn('my-[18px]', className)} />
}

/** Özet satırı: [{ value, label, hint?, time? }] — `hint` ipucudur (eski title). */
export function DetailSummary({ items, className }) {
  return (
    <div data-slot="detail-summary" className={cn('flex flex-wrap gap-7', className)}>
      {/* `key` React'e aittir: nesneyi olduğu gibi yaymak onu prop olarak da geçirip uyarı üretiyordu → ayrıştır. */}
      {items.filter(Boolean).map(({ key, ...it }, i) => <DetailMetric key={key ?? i} {...it} />)}
    </div>
  )
}

export function DetailMetric({ value, label, hint, time = false, valueClassName }) {
  const body = (
    <div className={cn('flex flex-col gap-[3px]', hint && 'cursor-help')}>
      <span className={cn('leading-tight font-bold text-foreground', time ? 'text-xs font-semibold' : 'text-[15px]', valueClassName)}>{value}</span>
      <span className="text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
    </div>
  )
  return hint ? <SimpleTooltip content={hint}>{body}</SimpleTooltip> : body
}

/**
 * "İstek ayarları" kutusu (eski .kw-reqinfo) — başlık + anahtar/değer satırları.
 * rows: [[anahtar, değer], …]; boş (falsy) satırlar atlanır.
 */
export function DetailInfoCard({ title, rows, className }) {
  return (
    <Card data-slot="detail-info" className={cn('mb-1 gap-2 rounded-lg bg-muted/30 px-3.5 py-3 shadow-none', className)}>
      <CardHeader className="px-0">
        <CardTitle className="text-[10px] font-bold tracking-[.05em] text-muted-foreground uppercase">{title}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-2 px-0">
        {rows.filter(Boolean).map(([k, v], i) => (
          <div key={i} className="flex items-start gap-2.5 text-[13px]">
            <span className="min-w-[110px] font-semibold text-muted-foreground">{k}</span>
            <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{v}</span>
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

/** Açık/Kapalı değeri (eski .kw-on / .kw-off). */
export function OnOff({ on, onText, offText }) {
  return <span className={on ? 'font-semibold text-success' : 'text-muted-foreground'}>{on ? onText : offText}</span>
}

/**
 * Sekme değeri → varsayılan ikon. Dokuz sayfa aynı sekme adlarını kullanır (control/alerts/chart/notes/changes
 * + tür-özel registration/issues/resources/versions/diag); sayfanın ikon geçmesi gerekmez, isterse
 * `[değer, etiket, { icon }]` ile ezer.
 */
const TAB_ICONS = {
  control: History, alerts: BellRing, chart: ChartLine, notes: BookOpen, changes: ScrollText,
  registration: Globe, issues: FileWarning, resources: Layers, versions: GitBranch, diag: Stethoscope,
}

/**
 * Sekme sayaçları (2026-09-27): "Değişiklikler (n)" ve "Rehber & Notlar (n)" için iki hafif istek — değişiklik
 * listesi `size=1` ile yalnız `total` zarfını, not listesi zaten küçük yanıtı getirir. İkisi de en-iyi-çaba:
 * hata/404 (yabancı takım) sayaç göstermez, sekme yine açılır ve kendi hatasını kendisi gösterir.
 * `openAlerts` sayfadan gelir (kartın `active_alarm` bayrağı — ek istek yok).
 *
 * @param {{ kind?: string, monitorId?: number|string, notesType?: string, notesTarget?: string, openAlerts?: number }|null} spec
 */
// Sayaç sondası: yalnız zarftaki `total` okunur, satırlar çizilmez (önizleme listesi DEĞİL — sunucu size ≥ 1 ister).
const COUNT_PROBE_SIZE = 1

export function useDetailTabCounts(spec) {
  const [counts, setCounts] = useState({})
  const key = spec ? `${spec.kind ?? ''}|${spec.monitorId ?? ''}|${spec.notesType ?? ''}|${spec.notesTarget ?? ''}` : ''
  useEffect(() => {
    if (!spec || spec.monitorId == null) { setCounts({}); return undefined }
    let alive = true
    const next = {}
    const jobs = []
    if (spec.kind) {
      jobs.push(Promise.resolve()
        .then(() => api.monitoring.getChanges(spec.kind, spec.monitorId, { page: 0, size: COUNT_PROBE_SIZE }))
        .then((r) => { const n = Number(r?.data?.total); if (r?.success && Number.isFinite(n)) next.changes = n })
        .catch(() => {}))
    }
    if (spec.notesType && spec.notesTarget) {
      jobs.push(Promise.resolve()
        .then(() => api.monitoring.getMonitorNotes(spec.notesType, spec.notesTarget))
        .then((r) => { if (r?.success && Array.isArray(r.data?.notes)) next.notes = r.data.notes.length })
        .catch(() => {}))
    }
    Promise.all(jobs).then(() => { if (alive) setCounts({ ...next }) })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  const alerts = Number(spec?.openAlerts)
  return Number.isFinite(alerts) && alerts > 0 ? { ...counts, alerts } : counts
}

/**
 * Derin bağlantının sekmesi (`?monitor=<id>&mtab=changes` — İzleme Değişiklikleri konsolu böyle bağlar).
 *
 * <p>Sayfa MOUNT'unda okunur ve ilk açılışta BİR KEZ tüketilir: sayfanın URL senkronu (`useUrlQuerySync`)
 * pencere kapalıyken `mtab`'ı 300 ms sonra URL'den siler; izleme listesi ondan geç gelirse açılış anında
 * param çoktan gitmiş oluyordu ve bağlantı hep ilk sekmeye düşüyordu. Sonraki açılışlar `fallback` alır.
 *
 * @returns {() => string} openDetail içinde çağrılır: `setDetailTab(deepLinkTab())`
 */
export function useDeepLinkTab(fallback = 'control') {
  const ref = useRef(readUrlParam('mtab', null))
  return useCallback(() => {
    const v = ref.current
    ref.current = null
    return v || fallback
  }, [fallback])
}

/**
 * Detay sekmeleri — shadcn Tabs (çizgi varyantı). `tabs`: [[değer, etiket, { icon?, count? }?], …]. Etkin
 * olmayan TabsContent DOM'dan çıkar (eski koşullu çizimle aynı). Sayfa `TabsContent`'leri çocuk olarak verir.
 * jsdom'da tetik `mousedown` ile değişir (SHADCN.md §8.3).
 *
 * <p>2026-09-27: her sekmede ikon (TAB_ICONS) + sayaç rozeti (`counts[değer]` > 0 ise; `countsFor` verilirse
 * değişiklik/not sayıları uçtan gelir — useDetailTabCounts). Açık alarm sayacı kırmızı tonlu. Telefonda liste
 * yatay kayar ve iki kenarı soluklaşır (daha sekme olduğunu söyler); dokunmatikte 48 px liste (≥ 40 px hedef).
 * Sekme listesinde olmayan bir değer (bozuk `mtab` derin bağlantısı) ilk sekmeye düşer — boş gövde kalmaz.
 */
export function DetailTabs({ value, onValueChange, tabs, counts, countsFor = null, className, children }) {
  const fetched = useDetailTabCounts(countsFor)
  const all = { ...fetched, ...(counts || {}) }
  const items = tabs.filter(Boolean)
  const tabKeys = items.map(([k]) => k).join('|')
  useEffect(() => {
    if (value && items.length && !items.some(([k]) => k === value)) onValueChange?.(items[0][0])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, tabKeys])
  return (
    <Tabs value={value} onValueChange={onValueChange} className={cn('mt-4 gap-3', className)}>
      {/* 5–7 sekme dar pencereye sığmaz: liste SARAR (2026-09-30, kullanıcı kararı — yatay kaydırma "kötü görüntü").
          Eskiden yatay kayıyor ve kenarları soluklaşıyordu; şimdi satır satır kırılır, yükseklik içeriğe göre büyür,
          hiçbir genişlikte kaydırma çubuğu çıkmaz. Dokunmatikte ≥ 40 px tetik yüksekliği (pointer-coarse). */}
      <TabsList variant="line" data-slot="detail-tabs-list"
        className={cn('h-auto w-full flex-wrap justify-start gap-x-1 gap-y-1 border-b pb-0',
          'group-data-[orientation=horizontal]/tabs:pointer-coarse:min-h-12')}>
        {items.map(([k, label, opts]) => {
          const Icon = opts?.icon ?? TAB_ICONS[k]
          const n = opts?.count ?? all[k]
          const show = Number.isFinite(Number(n)) && Number(n) > 0
          return (
            <TabsTrigger key={k} value={k} data-count={show ? Number(n) : undefined}
              className="flex-none gap-1.5 px-3 group-data-[orientation=horizontal]/tabs:after:bottom-[-4px] pointer-coarse:min-h-10">
              {Icon && <Icon aria-hidden="true" className="size-4 opacity-80" />}
              {label}
              {show && (
                <Badge variant={k === 'alerts' ? 'destructive' : 'secondary'} data-slot="tab-count"
                  className="h-4 min-w-4 rounded-full px-1.5 py-0 text-[10.5px] leading-4 font-bold tabular-nums">
                  {Number(n).toLocaleString()}
                </Badge>
              )}
            </TabsTrigger>
          )
        })}
      </TabsList>
      {children}
    </Tabs>
  )
}
