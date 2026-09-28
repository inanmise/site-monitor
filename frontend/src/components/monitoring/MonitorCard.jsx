import { createContext, forwardRef, useContext } from 'react'
import { AlertTriangle, Clock, Hourglass, Pause } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Card, CardContent, CardFooter, CardHeader } from '@/components/shadcn/card'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Spinner } from '../ui/Progress.jsx'
import NocStatus from '../noc/NocStatus.jsx'
import { cn } from '@/lib/utils'

/**
 * İzleme KARTI ailesi — dokuz izleme sayfasının ızgarasındaki kart, shadcn Card üzerinde TEK kopya.
 * (Eski `.upt-card*` App.css ailesinin yerine; ızgara kabı `.upt-grid` yerleşim kabı olarak kalır.)
 *
 * <p><b>Etkileşim — "stretched button" deseni (BUG_REGRESYON_2026-09-25 R18).</b> Kart artık
 * `role="button"` DEĞİL: ARIA'da düğmenin çocukları sunumsaldır, oysa kartın içinde onay kutusu,
 * bağlantı kopyalama, eylem düğmeleri ve takım rozeti var (axe `nested-interactive`; VoiceOver içteki
 * kontrolleri düzleştirebiliyor). Kartın BAŞLIĞI gerçek bir düğmedir ({@link MonitorCardTitle}); onun
 * `::after` katmanı kartın tamamını örter, böylece fare kartın herhangi bir yerine tıklayınca yine
 * detay açılır, klavye tek bir Tab durağında (başlık) Enter/Space ile açar. İçerideki etkileşimli ya
 * da üzerine gelince bilgi veren bölgeler bu örtünün ÜSTÜNE çıkar ({@link CARD_LAYER}) — onlara
 * tıklamak detayı açmaz (eski `stopPropagation` sarmalayıcılarının karşılığı).
 *
 * <p><b>Mobil (2026-09-26).</b> Telefonda kart ~290 px'e iner: iç boşluk `px-4` (geniş ekranda `px-5`), üst satır
 * ve alt çubuk SARAR (eylemler sığmazsa kendi satırına iner), dokunmatik işaretçide (`pointer-coarse`) kopyala
 * düğmesi ve seçim kutusu 40 px dokunma alanı alır, kopyala düğmesi soluk kalmaz (hover yok).
 *
 * <p>Test kancaları: kök `data-slot="card"` + `data-status` (up|down|warn|unknown) + `data-alarm` + `data-inactive` + `data-running`;
 * başlık düğmesi `[data-monitor-open]` (shadcn Button; adı `mon.openDetailFor`).
 */

/** Örtünün (başlık düğmesinin ::after'ı) üstünde kalması gereken bölge — etkileşimli çocuklar için. */
export const CARD_LAYER = 'relative z-10'

/**
 * Toplu seçim kutusu görünümü — sayfalar shadcn Checkbox'ı DOĞRUDAN yazar (kapı `rowAccessibleNames`
 * kuralı 4, `checked={x.has(…)}` + satır argümanlı ad ister ve kutuyu kaynakta arar). Dokunmatikte kutu
 * görsel olarak 16 px kalır, ::after ile 40 px'lik dokunma alanı açılır.
 */
export const CARD_CHECK = 'relative z-10 mr-1 pointer-coarse:after:absolute pointer-coarse:after:-inset-3.5'

/**
 * Kart köşesindeki "bağlantıyı kopyala" (eski .upt-card-copy): soluk; kartın üzerine gelince/odakta tam.
 * Dokunmatikte hover olmadığı için hep tam opak ve 40 px.
 */
export const CARD_COPY = 'relative z-10 opacity-45 transition-opacity group-hover/mcard:opacity-100 focus-visible:opacity-100 motion-reduce:transition-none pointer-coarse:size-10 pointer-coarse:opacity-100'

// Durum sözlüğü paylaşılan: up | down | warn | unknown. Kartta SOL RENK ŞERİDİ YOK (kullanıcı kuralı
// 2026-09-26, kalıcı: "kartların sol tarafındaki renklendirmeleri hiçbir zaman yapmayalım") — durum rozetle
// (metin + renk) ve data-status ile taşınır.
const STATUSES = new Set(['up', 'down', 'warn', 'unknown'])

/**
 * Kart bağlamı — alt bileşenler (MonitorCardFooter, MonitorCardActions, MonitorStatusBadge) kartın DURAKLATILMIŞ
 * olduğunu sayfa ayrıca prop geçmeden bilir: "Duraklatıldı" rozeti ve hızlı "Sürdür" düğmesi böylece HER izleme
 * sayfasında varsayılan (kullanıcı isteği 2026-09-26).
 */
const MonitorCardContext = createContext({ inactive: false, density: 'rich', running: false, noc: null })
export const useMonitorCard = () => useContext(MonitorCardContext)

/**
 * Kart yoğunluğu (2026-09-27): `density` = 'rich' (varsayılan, tam kart) | 'compact' (temel bilgiler). Kök
 * `data-density` taşır; alt parçalar `useMonitorCard().density` ile okur. Yalnız Zengin'de görünecek bölümler
 * {@link MonitorCardRich} içine konur. Sayfa tarafı: `useCardDensity(sayfa)` + `ui/CardDensityToggle`.
 *
 * <p>`running` (2026-09-28): bu izleme ŞU AN kontrol ediliyor (sayfanın `useRunningChecks().isRunning(id)` — eylem
 * düğmesine verilen değerin AYNISI). Kök `data-running` taşır; hiç sonucu olmayan kartın bekleme satırı
 * ({@link MonitorPendingText}) bununla "İlk kontrol yapılıyor…" der.
 *
 * <p>`noc` (2026-09-28, kullanıcı isteği: "her izleme için 7/24 ekibine iletiliyor mu / açık mı kapalı mı kartta
 * görünsün"): `{ type, monitor, rowLabel, canEdit }` — verilirse {@link MonitorCardTop} 7/24 göstergesini
 * (noc/NocStatus) durum satırının SAĞ grubunun BAŞINA kendiliğinden çizer: dokuz türde AYNI yer, iki yoğunlukta da
 * (Zengin = hap, Kompakt = ikon + nokta). Kart dosyaları göstergeyi elle yerleştirmez (kapı: monitorCardStandard).
 */
export function MonitorCard({ status = 'unknown', alarm = false, inactive = false, density = 'rich', running = false, noc = null, className, children, ...rest }) {
  const key = STATUSES.has(status) ? status : 'unknown'
  const dens = density === 'compact' ? 'compact' : 'rich'
  return (
    <MonitorCardContext.Provider value={{ inactive, density: dens, running: !!running, noc }}>
    <Card
      data-status={key}
      data-alarm={alarm ? 'true' : undefined}
      data-inactive={inactive ? 'true' : undefined}
      data-running={running ? 'true' : undefined}
      data-density={dens}
      className={cn(
        'group/mcard relative min-w-0 gap-0 overflow-hidden rounded-xl px-4 pt-4 pb-3 shadow-none sm:px-5 sm:pt-[18px] sm:pb-3.5',
        'transition-[translate,box-shadow,border-color] duration-200 hover:-translate-y-[3px] hover:border-primary hover:shadow-lg',
        'motion-reduce:transition-none motion-reduce:hover:translate-y-0',
        // Aktif (çözülmemiş) alarm: TÜM kart kenarı (sol şerit değil) — alarm, monitör tekrar 'up' okusa
        // bile toparlanma penceresinde açık kalabilir.
        alarm && 'bg-destructive/5 outline-[1.5px] outline-offset-[-1.5px] outline-destructive/55 dark:bg-destructive/10',
        // Duraklatılmış: kesik kenar + İÇERİK soluk; alt çubuk (rozet + Sürdür) TAM opak kalır — eskiden bütün
        // kart opacity-55'ti, çocuk ebeveynden opak olamayacağı için Sürdür düğmesi de soluk çıkardı.
        // Soluklaşma başlık/içerik KABINA verilir (tek tek çocuklara değil): opaklık yığın bağlamı açar; başlık
        // kabı soluklaşınca kutu/kopyala (z-10) ile başlık düğmesinin ::after örtüsü AYNI bağlamda kalır, kutu
        // örtünün üstünde tıklanabilir kalır. Üst satırı tek başına soluklaştırmak onu örtünün ALTINA iterdi.
        inactive && 'border-dashed bg-muted/30 [&_[data-slot=card-content]]:opacity-60 [&_[data-slot=card-header]]:opacity-75',
        className,
      )}
      {...rest}
    >
      {children}
    </Card>
    </MonitorCardContext.Provider>
  )
}

/**
 * Yalnız Zengin görünümde çizilen bölüm (trend, SLA, ayrıntı döşemeleri …). Kompakt'ta DOM'a hiç girmez (gizlenmez):
 * 50 kartlık ızgarada Kompakt gerçekten hafif kalır ve ekran okuyucu gizli içeriği okumaz.
 */
export function MonitorCardRich({ className, children }) {
  const { density } = useMonitorCard()
  if (density !== 'rich' || children == null || children === false) return null
  return <div data-slot="monitor-card-rich" className={cn('min-w-0', className)}>{children}</div>
}

/**
 * HİÇ SONUCU OLMAYAN kartın bekleme metni (2026-09-28, kullanıcı bildirimi: "Test et → başarılı → Kaydet; açılan kartta
 * veriler yansımıyor, boş bir görünüm oluyor"). Kayıttan hemen sonra sayfa kartın kendi "Şimdi kontrol et" yolunu
 * çağırır; o kontrol sürerken (kart bağlamında `running`) kum saati yerine dönen gösterge + "İlk kontrol yapılıyor…",
 * değilse türün KENDİ bekleme metni (`idle` — "İlk kontrol bekleniyor", "İlk koşusu bekleniyor" …) ve ikonu. Kontrol
 * başlatılamadıysa (yetki / ağ) kart böylece boş kalmaz, bekleyen durumu söyler; zamanlayıcı ilk turunda yine koşar.
 *
 * <p>Kartlar kendi yuvasını (`data-slot`), kutusunu ve bekleme metnini KORUR; yalnız ikon + metin buradan gelir → dokuz
 * türde aynı iki durum. Test kancası: koşarken `data-slot="monitor-first-check"`.
 *
 * @param {string} idle            koşmuyorken yazılacak türün bekleme metni
 * @param {Function|null} icon     koşmuyorken ikon (varsayılan kum saati; `null` = ikonsuz — metin tek başına)
 * @param {string} iconClassName   ikon boyutu (kartın satır ölçeğine uysun)
 * @param {number} spinnerSize     dönen göstergenin px boyutu (ikonla aynı)
 */
export function MonitorPendingText({ idle, icon: Icon = Hourglass, iconClassName = 'size-3.5 shrink-0', spinnerSize = 14 }) {
  const t = useT()
  const { running } = useMonitorCard()
  if (running) {
    return (
      <span data-slot="monitor-first-check" className="inline-flex min-w-0 items-center gap-1.5">
        <Spinner size={spinnerSize} inline decorative />
        <span className="min-w-0 truncate">{t('mon.firstCheckRunning')}</span>
      </span>
    )
  }
  return <>{Icon && <Icon aria-hidden="true" className={iconClassName} />}{idle}</>
}

/**
 * Bekleme satırı KUTUSU — kendi bekleme öğesi olmayan kartlar için (Ping, Sayfa Hızı): Zengin'de kesik kenarlı soluk kutu,
 * Kompakt'ta tek sade satır (HTTP kartının "İlk kontrol bekleniyor" satırıyla aynı görünüm). İçerik {@link MonitorPendingText}.
 *
 * @param {string} slot     test kancası (`data-slot`)
 * @param {string} idle     koşmuyorken metin (varsayılan `mon.firstCheckPending`)
 * @param {boolean} compact Kompakt görünüm satırı
 */
export function MonitorCardPending({ slot = 'monitor-pending', idle, compact = false, className }) {
  const t = useT()
  return (
    <p data-slot={slot} data-compact={compact ? 'true' : undefined}
      className={cn(compact
        ? 'mt-2 mb-2 flex min-w-0 items-center gap-1.5 text-xs font-medium text-muted-foreground'
        : 'mb-2.5 flex min-w-0 items-center gap-1.5 rounded-lg border border-dashed bg-muted/40 px-2.5 py-2 text-xs font-medium text-muted-foreground dark:bg-muted/20',
      className)}>
      <MonitorPendingText idle={idle ?? t('mon.firstCheckPending')} />
    </p>
  )
}

/** Üst bölge: durum satırı + başlık. */
export function MonitorCardHeader({ className, children }) {
  return <CardHeader className={cn('block px-0', className)}>{children}</CardHeader>
}

/**
 * Durum satırı: sol grup (kutu, durum rozeti, alarm, bakım) + `end` (yöntem/tür etiketi, bağlantı kopyala).
 * Dar ekranda SARAR: rozetler çoğalınca `end` grubu sağa yaslı ikinci satıra iner, hiçbir şey sıkışıp kesilmez.
 *
 * <p>Kart `noc` taşıyorsa (MonitorCard) 7/24 göstergesi sağ grubun İLK öğesidir — örtünün üstünde (`CARD_LAYER`),
 * tıklaması detayı açmaz; yoğunluğu kart bağlamından alır.
 */
export function MonitorCardTop({ end, className, children }) {
  const { noc, density } = useMonitorCard()
  const nocStatus = noc ? <NocStatus {...noc} compact={density === 'compact'} /> : null
  return (
    <div className={cn('mb-2.5 flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1', className)}>
      {children}
      {(end || nocStatus) && <span className={cn(CARD_LAYER, 'ml-auto flex shrink-0 items-center gap-1.5')}>{nocStatus}{end}</span>}
    </div>
  )
}

/**
 * Kartın GERÇEK düğmesi — başlık metni (alan adı / URL / host). `::after` kartın tamamını örter
 * (stretched button); odak halkası da o örtüde çizilir, yani klavyeyle kartın tamamı vurgulanır.
 * Uzun metin kırpılır; tam metin `title` ile yalnız METNİN üzerinde görünür (örtünün değil) — tam metin
 * her türde detay penceresinin başlığında da durur (dokunmatikte oradan okunur).
 */
export function MonitorCardTitle({ onOpen, label, title, className, children }) {
  return (
    <Button
      type="button"
      variant="ghost"
      data-monitor-open="true"
      onClick={onOpen}
      aria-label={label}
      className={cn(
        'mb-3 flex h-auto w-full min-w-0 justify-start rounded-none p-0 text-left text-[15px] leading-tight font-bold tracking-[-.02em] text-foreground',
        'hover:bg-transparent hover:text-foreground dark:hover:bg-transparent',
        'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50',
        className,
      )}
    >
      <span className="min-w-0 truncate" title={title}>{children}</span>
    </Button>
  )
}

/** Orta bölge: meta, mini trend, ölçüler. */
export function MonitorCardContent({ className, children }) {
  return <CardContent className={cn('min-w-0 px-0', className)}>{children}</CardContent>
}

/** Ölçü satırı (eski .upt-card-metrics). Sarınca satırlar arası boşluk sütun boşluğundan dar. */
export function MonitorCardMetrics({ className, children }) {
  return <div data-slot="monitor-metrics" className={cn('mb-3 flex flex-wrap gap-x-5 gap-y-2.5', className)}>{children}</div>
}

/**
 * Tek ölçü: değer + küçük büyük harfli etiket (eski .upt-metric). `hint` verilirse ipucu (DetailMetric ile aynı);
 * ipucu hover/odak ister, bu yüzden ölçü örtünün üstüne çıkar ve klavyeyle odaklanabilir olur.
 */
export function MonitorMetric({ value, label, hint, valueClassName, className }) {
  const body = (
    <div data-slot="monitor-metric" tabIndex={hint ? 0 : undefined}
      className={cn('flex min-w-0 flex-col gap-0.5', hint && cn(CARD_LAYER, 'cursor-help rounded-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50'), className)}>
      <span className={cn('text-[13px] leading-tight font-bold text-foreground tabular-nums', valueClassName)}>{value}</span>
      <span className="text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">{label}</span>
    </div>
  )
  return hint ? <SimpleTooltip content={hint}>{body}</SimpleTooltip> : body
}

/**
 * Alt bölge: son kontrol zamanı + eylemler (eski .upt-card-foot). Eylemler örtünün üstünde. Dar kartta
 * eylemler sığmazsa kendi satırına (sağa yaslı) iner — eskiden zaman damgası "26/09/2026, 12:2…" diye kesiliyordu.
 * Zaman önünde saat simgesi + ekran okuyucuya "Son kontrol:" öneki.
 */
export function MonitorCardFooter({ actions, className, children }) {
  const t = useT()
  const { inactive } = useMonitorCard()
  const hasTime = children != null && children !== '' && children !== false
  return (
    <CardFooter className={cn('min-w-0 flex-wrap justify-between gap-x-2 gap-y-2 border-t px-0 pt-2 text-[10.5px] text-muted-foreground [.border-t]:pt-2', className)}>
      <span className="flex min-w-0 items-center gap-2">
        {inactive && <MonitorPausedBadge />}
        {hasTime && (
          <span data-slot="monitor-card-time" className="flex min-w-0 items-center gap-1">
            <Clock aria-hidden="true" className="size-3 shrink-0" />
            <span className="sr-only">{t('card.lastCheck')} </span>
            <span className="min-w-0 truncate tabular-nums">{children}</span>
          </span>
        )}
      </span>
      {actions && <span className={cn(CARD_LAYER, 'ml-auto shrink-0')}>{actions}</span>}
    </CardFooter>
  )
}

// Durum rozeti tonları (eski .upt-badge--up/down/unknown/warn) — shadcn Badge + jeton renkleri.
const BADGE = {
  up: { variant: 'secondary', cls: 'bg-success/15 text-success dark:bg-success/20' },
  down: { variant: 'secondary', cls: 'bg-destructive/10 text-destructive dark:bg-destructive/20' },
  warn: { variant: 'warning', cls: '' },
  unknown: { variant: 'outline', cls: 'text-muted-foreground' },
}

/**
 * Durum rozeti: renkli nokta + metin. `status` paylaşılan sözlükten (up|down|warn|unknown). Duraklatılmış kartın
 * içinde rozet SON BİLİNEN durumu gri çizgili gösterir (`data-paused`): yeşil "Çalışıyor" rozeti, kontrol edilmeyen
 * bir izlemenin ayakta olduğunu iddia ediyordu. Detay penceresinde (kart dışı) davranış değişmez.
 */
export function MonitorStatusBadge({ status = 'unknown', className, children }) {
  const { inactive } = useMonitorCard()
  const key = BADGE[status] ? status : 'unknown'
  const b = inactive ? BADGE.unknown : BADGE[key]
  return (
    <Badge variant={b.variant} data-status={key} data-paused={inactive ? 'true' : undefined}
      className={cn('gap-1.5 px-2.5 py-[3px] text-[11px] font-bold tracking-[.03em]', b.cls, className)}>
      <span aria-hidden="true"
        className={cn('size-1.5 shrink-0 rounded-full bg-current', key === 'up' && !inactive && 'animate-pulse motion-reduce:animate-none')} />
      {children}
    </Badge>
  )
}

const ALARM_TONE = {
  CRITICAL: 'border-red-600/30 bg-red-600/10 text-red-700 dark:border-red-400/40 dark:text-red-300',
  HIGH: 'border-orange-600/30 bg-orange-500/10 text-orange-700 dark:border-orange-400/40 dark:text-orange-300',
}
const ALARM_TONE_DEFAULT = 'border-amber-600/30 bg-amber-500/10 text-amber-700 dark:border-amber-400/40 dark:text-amber-300'

/**
 * Aktif alarm işareti — GÖRÜNÜR seviye rozeti (⚠ Kritik / Yüksek / Uyarı). Eskiden yalnız bir üçgen ikon vardı ve
 * seviye yalnız fareyle açılan ipucundaydı (dokunmatikte hiç görünmüyordu). Onaylanmamış alarmda ikon nabız atar.
 * Ekran okuyucu tam metni (`label`: "Aktif alarm — CRITICAL") duyar; alarm yoksa hiçbir şey çizilmez.
 */
export function MonitorAlarmIcon({ monitor, label }) {
  const t = useT()
  if (!monitor?.active_alarm) return null
  const level = monitor.alarm_level
  const levelKey = level ? `notify.level.${level}` : null
  const levelText = levelKey && t(levelKey) !== levelKey ? t(levelKey) : t('mon.alarmShort')
  return (
    <Badge variant="outline" data-slot="monitor-alarm" data-level={level || undefined}
      className={cn(CARD_LAYER, 'gap-1 px-2 py-[3px] text-[11px] font-bold', ALARM_TONE[level] ?? ALARM_TONE_DEFAULT)}>
      <AlertTriangle aria-hidden="true"
        className={cn(!monitor.alarm_acknowledged && 'animate-pulse motion-reduce:animate-none')} />
      <span aria-hidden="true">{levelText}</span>
      <span className="sr-only">{label}</span>
    </Badge>
  )
}

/** Kart üstündeki küçük tür/yöntem etiketi (eski .upt-port-tag: GET, ICMP, A …). Ref alır (asChild tetik olabilir). */
export const MonitorCardTag = forwardRef(function MonitorCardTag({ className, children, ...rest }, ref) {
  return <span ref={ref} data-slot="monitor-card-tag" className={cn('font-mono text-[11px] text-muted-foreground', className)} {...rest}>{children}</span>
})

/** "Duraklatıldı" rozeti — duraklatılmış kartın alt çubuğunda kendiliğinden çizilir (MonitorCardFooter). */
export function MonitorPausedBadge({ className }) {
  const t = useT()
  return (
    <Badge variant="secondary" data-slot="monitor-paused" className={cn('gap-1 text-[10.5px] font-semibold', className)}>
      <Pause aria-hidden="true" className="size-3" /> {t('mon.paused')}
    </Badge>
  )
}
