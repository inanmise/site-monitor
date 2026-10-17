import { CircleCheck } from 'lucide-react'
import { api } from '../../api/client'
import { navigateTo } from '../../utils/navigate.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Kişisel Panom kutularının ORTAK sunum parçaları ve paylaşılan getiriciler (2026-10-13: 7/24 Komuta kutuları da
 * kullandığı için widgets.jsx'ten ayrıldı — davranış aynı).
 */

/** Açık alarm özeti — "Açık alarmlar" ve "Durum lambası" aynı ucu okur (sunucuda 15 sn bellek). */
export const fetchOpenAlerts = () => api.me.openAlerts(false)
/** Durum sayfası — hizmetler, bakım pencereleri ve açık olaylar kutuları aynı ucu okur (sunucuda 30 sn bellek). */
export const fetchStatus = () => api.statusPage.get(false)
/** İzleme Panosu özeti — izleme sağlığı, tür ısı haritası ve takım × tür matrisi aynı ucu okur (sunucuda 30 sn bellek). */
export const fetchOverview = () => api.monitoring.getOverview(24, false)
/** Sertifika istatistikleri — sertifika bitişleri kutusu ve sertifika renkli panelleri aynı ucu okur. */
export const fetchStats = () => api.getStats()
/**
 * Kanal teknik hata raporları genel bakışı (R14, 2026-10-17) — "dönem raporu eksik kanal" paneli okur; mevcut uç, kapsam
 * sunucuda. 403 = modül bu kullanıcının takımlarına kapalı (ya da okuma izni yok) → `visible:false` (kutu gri "yetki yok",
 * ASLA yeşil); başka bir hata olduğu gibi döner (kutu hata durumunda kalır — kısmi / okunamayan veriden renk çıkmaz).
 */
export const fetchChannelReports = async () => {
  const res = await api.techErrors.overview(null)
  if (res && res.success === false && (res.status === 403 || res.errorInfo?.status === 403)) return { success: true, data: { visible: false } }
  return res
}

// ── Ortak sunum parçaları ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Kutu içi bağlantı (2026-10-13, kullanıcı isteği: "Kişisel Panom'dan hızlıca tıklayıp geçebilmeliyim"): sayılar, kutucuklar
 * ve satırlar ilgili SÜZÜLMÜŞ ekranı ya da kaydın kendisini açar. Düzenleme kipinde (sürükle / boyut) ve TV / duvar ekranında
 * tıklanmaz — kazara gezinme olmasın; o zaman düz metin çizilir (eski görünüm).
 */
export const linkTo = (frame, tab, params) => (frame?.edit || frame?.tv || !tab ? undefined : () => navigateTo(tab, params))

/**
 * Düğmeye `data-slot` YALNIZ verildiyse yazılır: `data-slot={undefined}` shadcn Button'ın varsayılan `data-slot="button"`
 * değerini siler; projenin kenarlık sıfırlaması `[data-slot]` seçicisine bağlı olduğundan düğme tarayıcının 2 px kabarık
 * kenarlığıyla çizilirdi (2026-10-13, e2e ekran görüntüsünde bulundu).
 */
export const slotAttr = (slot) => (slot ? { 'data-slot': slot } : {})

/** Tıklanabilir öğenin ortak sınıfı: shadcn Button'ın yükseklik / hizalama / satır kırma varsayılanları kutu içine uyarlanır. */
export const LINK_RESET = 'h-auto min-w-0 justify-start gap-0 whitespace-normal text-left font-normal shadow-none'

/** Büyük sayı + etiket. */
export function Metric({ value, label, tone = 'neutral', tv, slot, onClick, hint }) {
  const ink = { danger: 'text-destructive', warning: 'text-amber-700 dark:text-amber-300', success: 'text-success', neutral: 'text-foreground', muted: 'text-muted-foreground' }[tone]
  const body = (
    <>
      <span className={cn('font-semibold tabular-nums leading-none', tv ? 'text-4xl' : 'text-3xl', ink)}>{value}</span>
      <span className={cn('mt-1 text-muted-foreground [overflow-wrap:anywhere]', tv ? 'text-base' : 'text-sm')}>{label}</span>
    </>
  )
  if (!onClick) return <div className="flex min-w-0 flex-col" data-slot={slot}>{body}</div>
  return (
    <Button type="button" variant="ghost" onClick={onClick} title={hint} {...slotAttr(slot)} data-link="true"
      className={cn(LINK_RESET, '-m-1.5 flex-col items-start rounded-lg p-1.5 hover:bg-muted/70 pointer-coarse:min-h-10')}>
      {body}
    </Button>
  )
}

/** Kutucuk tonları. */
export const TILE_TONE = {
  danger: 'bg-destructive/10 text-destructive',
  warning: 'bg-amber-500/10 text-amber-800 dark:text-amber-300',
  success: 'bg-success/10 text-success',
  muted: 'bg-muted text-foreground',
}
/** Tıklanabilir kutucukta üzerine gelince ton KORUNUR (Button'ın gri vurgusu ezilir); statik kutucukta vurgu yok. */
export const TILE_HOVER = {
  danger: 'hover:bg-destructive/20 dark:hover:bg-destructive/20 hover:text-destructive',
  warning: 'hover:bg-amber-500/20 dark:hover:bg-amber-500/20 hover:text-amber-800 dark:hover:text-amber-300',
  success: 'hover:bg-success/20 dark:hover:bg-success/20 hover:text-success',
  muted: 'hover:bg-muted/60 dark:hover:bg-muted/60 hover:text-foreground',
}

/** Sayılı küçük kutucuk (ızgara). `onClick` verilirse süzülmüş listeyi açan düğme olur. */
export function Tile({ value, label, tone = 'muted', tv, slot, onClick, hint }) {
  const t0 = TILE_TONE[tone] ? tone : 'muted'
  const cls = cn('flex min-w-0 flex-col rounded-lg px-3 py-2', TILE_TONE[t0])
  const body = (
    <>
      <span className={cn('font-semibold tabular-nums leading-tight', tv ? 'text-2xl' : 'text-xl')}>{value}</span>
      <span className={cn('text-xs font-medium break-words hyphens-auto opacity-90', tv && 'text-sm')}>{label}</span>
    </>
  )
  if (!onClick) return <div className={cls} data-slot={slot} data-tone={tone}>{body}</div>
  return (
    <Button type="button" variant="ghost" onClick={onClick} title={hint} {...slotAttr(slot)} data-tone={tone} data-link="true"
      className={cn(LINK_RESET, cls, TILE_HOVER[t0], 'items-start hover:ring-1 hover:ring-border')}>
      {body}
    </Button>
  )
}

export function Empty({ icon: Icon = CircleCheck, text, tone = 'success', tv }) {
  return (
    <p className={cn('m-0 flex items-center gap-2', tone === 'success' ? 'text-success' : 'text-muted-foreground', tv ? 'text-lg' : 'text-sm')} data-slot="mydash-empty">
      <Icon className="size-4 shrink-0" aria-hidden="true" />
      <span className="[overflow-wrap:anywhere]">{text}</span>
    </p>
  )
}

export function List({ children, slot }) {
  return <ul className="m-0 flex list-none flex-col divide-y divide-border p-0" data-slot={slot}>{children}</ul>
}

/**
 * Liste satırı: solda rozet/ikon, ortada ad (tam genişlik, sarar) ve altında ayrıntı, sağda kısa sayı rozetleri. Ad ile
 * ayrıntı AYNI esnek satırda yarışmaz — uzun takım adı adı birkaç piksele sıkıştırıp harf harf sardırıyordu (TV e2e).
 */
export function Row({ lead, title, meta, trail, slot, onClick, hint }) {
  // <button> yalnız satır içi (phrasing) içerik alır — tıklanabilir satırda <div>/<p> yerine blok <span>
  const Box = onClick ? 'span' : 'div'
  const Line = onClick ? 'span' : 'p'
  const inner = (
    <>
      {lead && <span className="shrink-0 pt-px">{lead}</span>}
      <Box className="block min-w-0 flex-1">
        <Line className="m-0 block font-medium [overflow-wrap:anywhere]">{title}</Line>
        {meta && <Line className="m-0 block text-xs text-muted-foreground [overflow-wrap:anywhere]">{meta}</Line>}
      </Box>
      {trail && <span className="flex max-w-[55%] shrink-0 flex-wrap justify-end gap-1">{trail}</span>}
    </>
  )
  if (!onClick) return <li className="flex min-w-0 items-start gap-2 py-2" data-slot={slot}>{inner}</li>
  // Tıklanabilir satır: tüm satır tek düğme (en az 40 px), kenarlara taşan vurgu satır çizgisiyle hizalı
  return (
    <li className="min-w-0" data-slot={slot}>
      <Button type="button" variant="ghost" onClick={onClick} title={hint} data-link="true"
        className={cn(LINK_RESET, '-mx-2 my-0.5 flex w-[calc(100%+1rem)] min-h-10 items-start gap-2 rounded-md px-2 py-1.5 hover:bg-muted/70')}>
        {inner}
      </Button>
    </li>
  )
}

export function More({ n, t }) {
  if (!n) return null
  return <p className="m-0 text-xs text-muted-foreground">{t('mydash.more', n)}</p>
}

export const LEVEL_TONE = { CRITICAL: 'danger', HIGH: 'danger', WARNING: 'warning', MEDIUM: 'warning' }
// TV'de liste kısa tutulur (satır yüksekliği sabit, duvar ekranında kaydırılamaz); sayfada boyuta göre
export const listLimit = (size, tv, base) => (tv ? (size === 'sm' ? Math.max(2, base - 2) : base) : size === 'lg' ? base + 3 : size === 'sm' ? 0 : base)

export function levelKey(level) {
  const l = String(level || '').toUpperCase()
  if (l === 'CRITICAL') return 'critical'
  if (l === 'HIGH') return 'high'
  return 'warning'
}
