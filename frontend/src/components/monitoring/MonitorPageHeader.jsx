import { useEffect, useRef, useState } from 'react'
import { BookOpen, Layers, Link2, MoreHorizontal, Plus, RefreshCw, Timer } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import CheckAllButton from '../check/CheckAllButton.jsx'
import CopyLinkButton, { useCopyLink } from '../ui/CopyLinkButton.jsx'
import MonitorGuideButton, { MonitorGuideDialog, hasMonitorGuide } from '../ui/MonitorGuideButton.jsx'
import { TAB_META } from '../palette/paletteModel.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'

/**
 * İzleme sayfalarının ORTAK başlığı + eylem çubuğu (2026-09-27, kullanıcı isteği: "her sayfanın sağ üstündeki
 * Yenile / Şimdi Kontrol Et / Nasıl doldurulur / Yeni Monitör — shadcn ile profesyonel, her sayfada benzer, mobil
 * uyumlu"). Dokuz izleme türü + Uptime aynı bileşeni kullanır (kapı: monitorCardStandard.test.js).
 * `ui/PageHeader` üzerine kurulu: ikon (kenar çubuğundaki sekme ikonu, `TAB_META`), başlık, alt başlık, meta çipleri.
 *
 * <p><b>Meta çipleri:</b> "N izleme" · (varsa) "M arızalı" (yıkıcı ton) · "42 sn sonra yenilenir" (her saniye değişir
 * → `aria-live="off"`, tabular-nums: rakamlar zıplamaz).
 *
 * <p><b>Eylem sırası</b> (soldan sağa az → çok önemli, BİRİNCİL en sağda — PageHeader sözleşmesi):
 * Yenile (outline; lg altında yalnız ikon + ipucu) · Şimdi Kontrol Et (N) (koşarken "12/40") · `extraActions`
 * (ör. Alan Adı'nın Dışa aktar menüsü) · Bağlantıyı kopyala + Nasıl doldurulur (ikincil küme) · Yeni Monitör (birincil).
 * <b>Telefonda</b> (&lt;768 px, `useIsMobile`) ikincil küme tek bir "Diğer işlemler" menüsüne (40 px) toplanır,
 * düğmeler 40 px yüksekliğe çıkar ve Yeni Monitör kendi satırında tam genişlik olur. İkincil kümenin iki ayrı çizimi
 * (satır içi / menü) CSS ile değil JS ile seçilir: ikisi birden DOM'da olsaydı aynı erişilebilir ad iki kez görünürdü.
 *
 * Props:
 *  - type: izleme türü = sekme kimliği (`http`, `ping`, … `uptime`) — ikon ve kılavuz buradan
 *  - icon: ikon (verilmezse TAB_META[type])
 *  - title (düğüm), subtitle
 *  - count: listelenen izleme sayısı (yüklenirken null → çip yok); countUnit 'monitors' | 'sites'
 *  - down: arızalı sayısı (0/null → çip yok)
 *  - refreshIn: otomatik yenilemeye kalan saniye (null → çip yok)
 *  - refreshEvery + refreshResetKey (isteğe bağlı, `refreshIn` yerine — Ek 3/10, 2026-09-28): geri sayımı ÇİPİN KENDİSİ
 *    sayar (saniyelik state yalnız çipte); `refreshResetKey` her başarılı yüklemede değişir → sayaç sıfırlanır; gizli
 *    sekmede durur. Sayfanın `secondsSince` state'i sayfayı — ve açık detay penceresinin geçmiş/recharts ağacını — her
 *    saniye yeniden çiziyordu (Uptime).
 *  - onRefresh, refreshing
 *  - check: { count, running, done, total, onOpen } → CheckAllButton (count 0 → düğme yok)
 *  - canWrite + onNew + newLabel → birincil "Yeni Monitör" (`data-tour="mon-new"`)
 *  - extraActions: ek eylem düğümü (Şimdi Kontrol Et'in sağında)
 *  - showActions: false → meta + eylemler çizilmez (Sentetik izleme Şablonlar görünümü)
 *  - children: başlığın altına tam genişlik (ör. görünüm anahtarı)
 * Test kancaları: PageHeader'ınkiler + data-slot="monitor-header-tools|monitor-count|monitor-down|monitor-refresh".
 */
/**
 * Kendi sayan yenileme çipi (Ek 3/10): saniyelik state YALNIZ burada — başlık ve sayfa her saniye çizilmez. Görünüm
 * `refreshIn` çipiyle birebir aynı (data-slot="monitor-refresh").
 */
function RefreshCountdown({ every, resetKey }) {
  const t = useT()
  const [since, setSince] = useState(0)
  useEffect(() => { setSince(0) }, [resetKey])
  useVisibleInterval(() => setSince((s) => s + 1), 1000, false)
  return (
    <Badge variant="outline" data-slot="monitor-refresh" aria-live="off"
      className="gap-1 font-normal text-muted-foreground tabular-nums">
      <Timer aria-hidden="true" />{t('mon.hdr.refreshIn', Math.max(0, every - since))}
    </Badge>
  )
}

export default function MonitorPageHeader({
  type, icon, title, subtitle,
  count = null, countUnit = 'monitors', down = null, refreshIn = null, refreshEvery = null, refreshResetKey,
  onRefresh, refreshing = false,
  check = null,
  canWrite = false, onNew, newLabel,
  extraActions = null,
  showActions = true,
  className,
  children,
}) {
  const t = useT()
  const phone = useIsMobile()
  const copyLink = useCopyLink()
  const [guideOpen, setGuideOpen] = useState(false)
  const openedFromMenu = useRef(false)
  const Icon = icon || TAB_META[type]?.Icon
  const guide = hasMonitorGuide(type)

  const countText = count == null ? null
    : countUnit === 'sites'
      ? (count === 1 ? t('mon.hdr.sitesOne') : t('mon.hdr.sites', count))
      : (count === 1 ? t('mon.hdr.countOne') : t('mon.hdr.count', count))

  const meta = showActions && (countText || down > 0 || refreshIn != null || refreshEvery != null) ? (
    <>
      {countText && (
        <Badge variant="outline" data-slot="monitor-count" className="gap-1 font-normal tabular-nums">
          <Layers aria-hidden="true" />{countText}
        </Badge>
      )}
      {down > 0 && (
        <Badge variant="outline" data-slot="monitor-down"
          className="gap-1.5 border-destructive/30 bg-destructive/10 font-semibold text-destructive tabular-nums">
          <span aria-hidden="true" className="size-1.5 rounded-full bg-destructive" />{t('mon.hdr.down', down)}
        </Badge>
      )}
      {refreshIn == null && refreshEvery != null && <RefreshCountdown every={refreshEvery} resetKey={refreshResetKey} />}
      {refreshIn != null && (
        <Badge variant="outline" data-slot="monitor-refresh" aria-live="off"
          className="gap-1 font-normal text-muted-foreground tabular-nums">
          <Timer aria-hidden="true" />{t('mon.hdr.refreshIn', Math.max(0, refreshIn))}
        </Badge>
      )}
    </>
  ) : null

  // Telefonda 40 px dokunma hedefi, ≥640 px'te shadcn varsayılanı (36 px) — Pano başlığıyla aynı boy.
  const H = 'h-10 sm:h-9'
  const SQ = 'size-10 sm:size-9'

  const secondaryInline = (
    <>
      <CopyLinkButton iconOnly variant="outline" size="icon" className={SQ} />
      {guide && <MonitorGuideButton type={type} variant="outline" size="default" className={H} labelClassName="hidden xl:inline" />}
    </>
  )

  // Telefonda ikincil küme: tek "Diğer işlemler" menüsü. Kılavuz yoksa (Uptime) menü tek öğe için açılmaz — düğme kalır.
  const secondaryMenu = guide ? (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" size="icon" className="size-10 shrink-0" data-tour="mon-guide"
          aria-label={t('mon.hdr.more')} title={t('mon.hdr.more')}>
          <MoreHorizontal aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" collisionPadding={8} className="z-(--z-menu) min-w-52"
        onCloseAutoFocus={(e) => {
          // Kılavuz penceresi açıldıysa odak pencerede kalsın; Radix onu tetiğe geri çekmesin (KebabMenu kalıbı).
          if (openedFromMenu.current) { openedFromMenu.current = false; e.preventDefault() }
        }}>
        <DropdownMenuItem className="min-h-10" onSelect={() => { copyLink() }}>
          <Link2 aria-hidden="true" />{t('share.copyLink')}
        </DropdownMenuItem>
        <DropdownMenuItem className="min-h-10" onSelect={() => { openedFromMenu.current = true; setGuideOpen(true) }}>
          <BookOpen aria-hidden="true" className="text-primary" />{t('guideForm.btn')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  ) : (
    <CopyLinkButton iconOnly variant="outline" size="icon" className="size-10 shrink-0" />
  )

  const actions = showActions ? (
    <>
      {/* Araç kümesi TEK çocuk: telefonda PageHeader çocukları satırı eşit paylaştırır (flex-1) — küme bir satırı
          doldurur, içinde Şimdi Kontrol Et esner, ikon düğmeler 40 px kalır. ≥640 px'te küme sarabilir (max-w-full). */}
      <div data-slot="monitor-header-tools"
        className="flex min-w-0 max-w-full items-center gap-2 sm:flex-wrap sm:justify-end">
        {onRefresh && (
          <SimpleTooltip content={t('mon.hdr.refreshTip')}>
            <Button type="button" variant="outline" onClick={onRefresh} aria-label={t('app.refresh')}
              aria-busy={refreshing || undefined} className={cn(H, 'shrink-0')}>
              <RefreshCw aria-hidden="true" className={cn(refreshing && 'animate-spin motion-reduce:animate-none')} />
              <span className="hidden lg:inline">{t('app.refresh')}</span>
            </Button>
          </SimpleTooltip>
        )}
        {check && (
          <CheckAllButton count={check.count} running={check.running} done={check.done ?? 0} total={check.total ?? 0}
            onClick={check.onOpen} size="default" className={cn(H, 'max-sm:flex-1')} />
        )}
        {extraActions}
        {phone ? secondaryMenu : secondaryInline}
      </div>
      {canWrite && onNew && (
        // Birincil eylem EN SAĞDA; telefonda kendi satırında tam genişlik (min-w-full satırı zorla kırar).
        <Button type="button" onClick={onNew} data-tour="mon-new" className={cn(H, 'max-sm:min-w-full')}>
          <Plus aria-hidden="true" />{newLabel}
        </Button>
      )}
    </>
  ) : null

  return (
    <>
      <PageHeader icon={Icon} title={title} description={subtitle} meta={meta} actions={actions}
        className={className} data-monitor-type={type}>
        {children}
      </PageHeader>
      {phone && guide && <MonitorGuideDialog type={type} open={guideOpen} onClose={() => setGuideOpen(false)} />}
    </>
  )
}
