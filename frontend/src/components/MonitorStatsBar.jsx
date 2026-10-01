import { useId, useLayoutEffect, useRef, useState } from 'react'
import { useT } from '../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * "Genel Bakış" StatsPanel deseninin veri-agnostik, paylaşılan hâli — izleme sayfalarının (ve Alarm Geçmişi /
 * Sistem Sağlığı) üstündeki tıklanabilir sayım kartları.
 *
 * Props:
 *  - items: [{ key, Icon, label, value, cls, hint?, sub?, tip?, onClick? }]
 *      (cls = ton: total|valid|warning|high|critical|error|alert|expired|paused|weak|certissue;
 *       sub = etiketin altında görünür kısa satır; tip = erişilebilir ad/title yerine; onClick = süzgeç değil eylem kartı)
 *  - activeFilter: string|null  (aktif kart anahtarı)
 *  - onStatClick: (key) => void (toggle filtre)
 *
 * `hint`: kartın NE SAYDIĞINI açıklayan bir cümle. Etiketler kısa olmak zorunda ve kısa etiket yanlış okunabiliyor —
 * "Sahiplenilmemiş" kartı sahada "takımı yok" diye anlaşıldı, oysa "açık alarmı henüz kimse sahiplenmemiş" demek.
 *
 * <p><b>shadcn (2026-09-26).</b> Eskiden `role="button"` taşıyan elle kurulmuş div'lerdi (`.stats-panel` /
 * `.stat-item-*` App.css ailesi: üstte renkli şerit, sabit 6 sütun — telefonda sığmıyordu). Artık her kart shadcn
 * Button (`aria-pressed`); ton şerit değil değer/ikon rengi + hafif zemin. İpucu (`hint`) yalnız fareye bırakılmaz:
 * ekran okuyucuya açıklama olarak bağlıdır, görenler için `title`. Test kancaları:
 * `data-slot="stats-panel|stat-item|stat-value|stat-label"`, `data-tone`, `aria-pressed`, `data-cols`.
 *
 * <p><b>Dengeli, alanı dolduran yerleşim (2026-09-27, kullanıcı isteği).</b> Eski `auto-fit` ızgarası Pano'nun 11
 * kartını 1440'ta 7 + 4 diziyordu: ikinci satırda üç boş hücre kalıyor, kartlar alanı doldurmuyordu. Şimdi kap
 * ölçülür ({@link ResizeObserver}), sığan en çok sütun bulunur, satır sayısı ona göre belirlenir ve sütun sayısı
 * satırlara EŞİT dağıtılır ({@link balancedColumns}: 11 kart → 6 + 5, 8 kart → 4 + 4); kartlar `flex-grow` ile
 * satırı tam doldurur (son satırın kartları en fazla bir kart payı kadar geniş). Kart genişleyince içerik yatay
 * düzene geçer (`@container` sorgusu: ikon solda, değer/etiket sağda) — boş beyazlık yerine daha büyük ikon ve
 * okunur etiket. Ölçüm yokken (jsdom, ilk boyama) sınıf tabanlı taban: telefonda 2 sütun, ≥640 px'te ≥140 px kartlar.
 *
 * <p><b>Sıkı görünüm (`dense`, 2026-10-01 — İzleme Panosu akordiyonu).</b> Kart yüksekliği ~56 px: ikon solda, değer +
 * etiket sağda HER genişlikte yatay; en dar kart {@link DENSE_MIN_TILE_PX}. `className` panel kabına eklenir (akordiyon
 * içinde çerçeve/zemin/boşluk kaldırmak için). Varsayılan görünüm değişmez.
 */
const TONE = {
  total:    { text: 'text-foreground', bg: 'bg-muted/40' },
  valid:    { text: 'text-success', bg: 'bg-success/10 dark:bg-success/15' },
  warning:  { text: 'text-amber-600 dark:text-amber-400', bg: 'bg-amber-500/10' },
  high:     { text: 'text-orange-600 dark:text-orange-400', bg: 'bg-orange-500/10' },
  alert:    { text: 'text-orange-600 dark:text-orange-400', bg: 'bg-orange-500/10' },
  critical: { text: 'text-red-600 dark:text-red-400', bg: 'bg-red-500/10' },
  error:    { text: 'text-red-600 dark:text-red-400', bg: 'bg-red-500/[0.07]' },
  expired:  { text: 'text-red-700 dark:text-red-400', bg: 'bg-red-600/10' },
  paused:   { text: 'text-muted-foreground', bg: 'bg-muted' },
  weak:     { text: 'text-purple-600 dark:text-purple-400', bg: 'bg-purple-500/10' },
  certissue:{ text: 'text-rose-700 dark:text-rose-400', bg: 'bg-rose-500/10' },
}

/** Bir kartın sığabileceği en dar genişlik (px) — etiket iki satıra sarsa da okunur kalır. */
export const MIN_TILE_PX = 140
/** Sıkı görünümde (`dense`) bir kartın en dar genişliği (px). */
export const DENSE_MIN_TILE_PX = 128

/**
 * Kap genişliğine göre satırlara EŞİT dağıtılmış sütun sayısı.
 *
 * Önce sığan en çok sütun (`fit`) bulunur; kart sayısı ondan azsa hepsi tek satırdır. Fazlaysa gereken satır
 * sayısı `ceil(count / fit)`, sütun sayısı da `ceil(count / rows)` — böylece 11 kart 7 + 4 değil 6 + 5 dizilir.
 * Genişlik ölçülemediyse (0) 0 döner: çağıran sınıf tabanlı yerleşime düşer.
 */
export function balancedColumns(width, gap, count, minTile = MIN_TILE_PX) {
  if (!(width > 0) || !(count > 0)) return 0
  const fit = Math.max(1, Math.floor((width + gap) / (minTile + gap)))
  const cols = Math.min(fit, count)
  const rows = Math.ceil(count / cols)
  return Math.ceil(count / rows)
}

export default function MonitorStatsBar({ items, activeFilter, onStatClick, dense = false, className }) {
  const t = useT()
  const uid = useId()
  const panelRef = useRef(null)
  const count = items?.length ?? 0
  const minTile = dense ? DENSE_MIN_TILE_PX : MIN_TILE_PX
  // { cols, basis } — ölçülmüş yerleşim; null iken sınıf tabanlı taban geçerli.
  const [layout, setLayout] = useState(null)

  useLayoutEffect(() => {
    const el = panelRef.current
    if (!el || count === 0 || typeof ResizeObserver === 'undefined') return undefined
    const measure = () => {
      const cs = getComputedStyle(el)
      const width = el.clientWidth - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0)
      const gap = parseFloat(cs.columnGap) || 0
      const cols = balancedColumns(width, gap, count, minTile)
      // Taban `floor` ile: kesirli pay altı kartı beş sütuna sarabilirdi; kalan pikselleri flex-grow dağıtır.
      const next = cols > 0 ? { cols, basis: Math.floor((width - (cols - 1) * gap) / cols) } : null
      // HİSTEREZİS (2026-09-30, kullanıcı: "istatistiklere tıklayınca titreşim"): bölüm açılınca sayfa uzuyor, dikey
      // kaydırma çubuğu belirip paneli birkaç piksel daraltıyor, ölçüm yeni taban üretiyor, kartlar yeniden akıp
      // yüksekliği değiştiriyor, çubuk kaybolup geri geliyordu — ResizeObserver ↔ yerleşim döngüsü. Sütun sayısı
      // aynıyken 8 px'in altındaki taban farkı yok sayılır (flex-grow kalanı zaten dağıtır); html'de scrollbar-gutter
      // stable (App.css) döngünün diğer yarısını keser.
      setLayout((prev) => {
        if (prev?.cols === next?.cols && prev?.basis === next?.basis) return prev
        if (prev && next && prev.cols === next.cols && Math.abs(prev.basis - next.basis) < 8) return prev
        return next
      })
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [count, minTile])

  if (count === 0) return null
  return (
    <div ref={panelRef} data-slot="stats-panel" data-cols={layout?.cols} data-dense={dense || undefined}
      className={cn('mb-4 flex flex-wrap gap-2 rounded-[10px] border bg-card p-2 shadow-xs sm:gap-3 sm:p-3', dense && 'sm:gap-2', className)}>
      {items.map((item) => {
        // item.onClick: süzgeç değil EYLEM kartı (ör. Pano "CA çeşitliliği" → pencere) — aria-pressed taşımaz
        const isFilter = !item.onClick
        const isActive = isFilter && activeFilter === item.key
        const tone = TONE[item.cls] ?? TONE.total
        const tip = item.tip ?? (isActive ? t('mondash.clearTip') : t('mondash.filterTip', item.label))
        const hintId = item.hint ? `${uid}-hint-${item.key}` : undefined
        return (
          <Button key={item.key} type="button" variant="ghost"
            data-slot="stat-item" data-key={item.key} data-tone={item.cls} aria-pressed={isFilter ? isActive : undefined}
            aria-label={tip} aria-describedby={hintId}
            title={[tip, item.hint].filter(Boolean).join(' — ')}
            onClick={item.onClick ?? (() => onStatClick(item.key))}
            style={layout ? { flexBasis: `${layout.basis}px` } : undefined}
            className={cn(
              dense
                ? '@container relative h-auto min-h-14 min-w-0 grow basis-[calc(50%-4px)] justify-start rounded-lg border px-3 py-2 whitespace-normal sm:basis-[128px]'
                : '@container relative h-auto min-h-24 min-w-0 grow basis-[calc(50%-4px)] rounded-lg border px-2 py-3 whitespace-normal sm:basis-[140px] sm:py-4',
              tone.bg, 'hover:bg-accent/60 dark:hover:bg-accent/40',
              isActive && 'border-primary ring-2 ring-primary/40',
            )}>
            {/* Kart ≥ 220 px olunca yatay: ikon solda, sayı + etiket sağda (geniş ekranda boş beyazlık yerine). Sıkı
                görünümde her genişlikte yatay. */}
            <span className={cn('flex w-full min-w-0',
              dense ? 'flex-row items-center gap-2.5' : 'flex-col items-center gap-1 @[220px]:flex-row @[220px]:justify-center @[220px]:gap-3')}>
              <item.Icon aria-hidden="true" className={cn('shrink-0', dense ? 'size-5' : 'size-6 sm:size-7 @[220px]:size-9', tone.text)} />
              <span className={cn('flex min-w-0 flex-col', dense ? 'items-start gap-0.5' : 'items-center gap-1 @[220px]:items-start')}>
                <span data-slot="stat-value" className={cn('leading-none font-extrabold tracking-[-.02em] tabular-nums', dense ? 'text-xl' : 'text-2xl sm:text-3xl', tone.text)}>
                  {item.value ?? 0}
                </span>
                <span data-slot="stat-label" className={cn('leading-tight font-semibold text-muted-foreground', dense ? 'text-left text-[11px]' : 'text-center text-xs @[220px]:text-left')}>{item.label}</span>
                {item.sub && <span data-slot="stat-sub" className={cn('max-w-full leading-tight break-words text-muted-foreground', dense ? 'text-left text-[11px]' : 'text-center text-[11.5px] @[220px]:text-left')}>{item.sub}</span>}
              </span>
            </span>
            {item.hint && <span id={hintId} className="sr-only">{item.hint}</span>}
            {isActive && <span aria-hidden="true" data-slot="stat-active-dot" className="absolute top-2 right-2 size-2 rounded-full bg-primary" />}
          </Button>
        )
      })}
    </div>
  )
}
