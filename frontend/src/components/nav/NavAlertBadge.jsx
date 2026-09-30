import { CheckCircle2, ArrowRight, OctagonAlert, TriangleAlert, CircleAlert } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { formatIncidentTime } from '../../utils/incidentMeta.js'
import { alertTypeLabel } from '../../utils/alertTypeMeta.js'
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/shadcn/hover-card'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'

/**
 * İzleme menüsü — aktif alarm rozeti (2026-09-30, kullanıcı isteği).
 *
 * <p>Her izleme sekmesinin yanında o türün AÇIK alarm sayısı (kullanıcının görüş kapsamı). Ton en yüksek seviyeden:
 * kritik → kırmızı, yüksek → turuncu, uyarı → amber. Rozet bir DÜĞMEdir: tıklayınca Alarm Geçmişi'nin açık görünümü
 * o türe süzülmüş açılır. Üzerine gelince (fare) yana açılan özet kartı: seviye kırılımı, sahiplenilmemiş sayısı ve
 * en yeni beş alarm. Dokunmatikte üzerine gelme yok — dokunuş doğrudan alarmlara götürür (kart içeriği menüye
 * gidilince zaten görünür). Nested button OLMAZ: rozet satır düğmesinin KARDEŞİ olarak çizilir (NavMain).
 */

/** Sekme id → özet nesnesi ({count, unacked, levels, items}) — boş/eksik → null. */
export function tabSummary(byTab, id) {
  const s = byTab?.[id]
  if (!s || !(Number(s.count) > 0)) return null
  return s
}

/** En yüksek seviye anahtarı — ton ve erişilebilirlik metni için. */
export function topLevel(summary) {
  const lv = summary?.levels || {}
  if (Number(lv.critical) > 0) return 'critical'
  if (Number(lv.high) > 0) return 'high'
  if (Number(lv.warning) > 0) return 'warning'
  return 'other'
}

const TONE = {
  critical: 'border-transparent bg-destructive text-white',
  high:     'border-orange-500/40 bg-orange-500/15 text-orange-800 dark:text-orange-200',
  warning:  'border-amber-500/40 bg-amber-500/15 text-amber-800 dark:text-amber-200',
  other:    'border-border bg-muted text-muted-foreground',
}
const LEVEL_ICON = { critical: OctagonAlert, high: TriangleAlert, warning: CircleAlert }
const DOT = {
  critical: 'bg-destructive', high: 'bg-orange-500', warning: 'bg-amber-500', other: 'bg-muted-foreground/60',
}

/** Sayı rozeti (salt görünüm) — bölüm başlığı ve daraltılmış menü de aynı görünümü kullanır. */
export function CountPill({ count, level = 'other', className, ...rest }) {
  if (!(Number(count) > 0)) return null
  return (
    <Badge variant="outline" data-slot="nav-alert-count" data-level={level}
      className={cn('h-5 min-w-5 justify-center rounded-full px-1.5 text-[11px] font-bold tabular-nums', TONE[level] || TONE.other, className)}
      {...rest}>
      {Number(count) > 99 ? '99+' : count}
    </Badge>
  )
}

/** Seviye kırılımı çipleri — yalnız sıfırdan büyük olanlar. */
function LevelChips({ levels }) {
  const t = useT()
  const keys = ['critical', 'high', 'warning'].filter((k) => Number(levels?.[k]) > 0)
  if (keys.length === 0) return null
  return (
    <div className="flex flex-wrap gap-1" data-slot="nav-alert-levels">
      {keys.map((k) => {
        const Icon = LEVEL_ICON[k]
        return (
          <Badge key={k} variant="outline" data-level={k} className={cn('gap-1 text-[10px] font-bold uppercase', TONE[k])}>
            <Icon aria-hidden="true" className="size-3" />{t(`alh.level.${k}`)} · {levels[k]}
          </Badge>
        )
      })}
    </div>
  )
}

/**
 * Rozet + üzerine gelince özet kartı.
 * @param label   izleme türünün adı (ör. "HTTP / Website") — erişilebilirlik metni ve kart başlığı
 * @param onGo    (alertId?) → Alarm Geçmişi'ne git (id verilirse o alarm açılır)
 */
export function OpenAlertBadge({ id, label, summary, onGo, className }) {
  const t = useT()
  const locale = useDateLocale()
  if (!summary) return null
  const level = topLevel(summary)
  const count = Number(summary.count)
  const items = Array.isArray(summary.items) ? summary.items : []
  const unacked = Number(summary.unacked || 0)

  return (
    <HoverCard openDelay={250} closeDelay={120}>
      <HoverCardTrigger asChild>
        <Button type="button" variant="ghost" size="sm" data-slot="nav-alert-badge" data-tab={id} data-level={level}
          aria-label={t('nav.openAlertsFor', label, count)} title={t('nav.openAlerts', count)}
          className={cn('h-7 min-w-7 rounded-full px-0.5 hover:bg-transparent focus-visible:ring-2 pointer-coarse:h-9 pointer-coarse:min-w-9', className)}
          onClick={(e) => { e.stopPropagation(); onGo?.() }}>
          <CountPill count={count} level={level} className="pointer-events-none" />
        </Button>
      </HoverCardTrigger>
      <HoverCardContent side="right" align="start" sideOffset={10} collisionPadding={12}
        className="z-(--z-menu) w-[min(22rem,calc(100vw-2rem))] p-0" data-slot="nav-alert-peek" data-tab={id}>
        <div className="flex items-start justify-between gap-2 px-3.5 pt-3 pb-2">
          <div className="min-w-0">
            <p className="text-xs font-semibold tracking-wider text-muted-foreground uppercase">{t('nav.openAlertsPeek.kicker')}</p>
            <p className="truncate text-sm font-semibold">{label}</p>
          </div>
          <CountPill count={count} level={level} className="mt-0.5 shrink-0" />
        </div>
        <div className="flex flex-wrap items-center gap-1.5 px-3.5 pb-2.5">
          <LevelChips levels={summary.levels} />
          {unacked > 0 && (
            <Badge variant="secondary" data-slot="nav-alert-unacked" className="text-[10px] font-semibold">
              {t('nav.openAlertsPeek.unacked', unacked)}
            </Badge>
          )}
        </div>
        <Separator />
        <ul className="m-0 max-h-64 list-none overflow-y-auto p-1.5" data-slot="nav-alert-items">
          {items.map((a) => (
            <li key={a.id}>
              <Button type="button" variant="ghost" data-slot="nav-alert-item" data-alert-id={a.id}
                className="h-auto w-full min-w-0 items-start justify-start gap-2 px-2 py-1.5 text-left font-normal whitespace-normal"
                onClick={() => onGo?.(a.id)}>
                <span aria-hidden="true" className={cn('mt-1.5 size-2 shrink-0 rounded-full', DOT[topLevel({ levels: { [String(a.alert_level || '').toLowerCase()]: 1 } })])} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium [overflow-wrap:anywhere]">{a.domain}</span>
                  <span className="block truncate text-[11px] text-muted-foreground">
                    {alertTypeLabel(t, a.alert_type)}{a.team_name ? ` · ${a.team_name}` : ''} · {formatIncidentTime(a.created_at, locale)}
                  </span>
                </span>
                {a.acknowledged && <CheckCircle2 role="img" aria-label={t('alh.ev.ack')} className="mt-0.5 size-3.5 shrink-0 text-sky-600 dark:text-sky-300" />}
              </Button>
            </li>
          ))}
          {items.length === 0 && <li className="px-2 py-2 text-xs text-muted-foreground">{t('nav.openAlertsPeek.empty')}</li>}
          {count > items.length && (
            <li className="px-2 pt-1 pb-0.5 text-[11px] text-muted-foreground">{t('nav.openAlertsPeek.more', count - items.length)}</li>
          )}
        </ul>
        <Separator />
        <div className="p-1.5">
          <Button type="button" variant="ghost" size="sm" className="w-full justify-between" data-slot="nav-alert-go" onClick={() => onGo?.()}>
            {t('nav.openAlertsPeek.go')}<ArrowRight aria-hidden="true" />
          </Button>
        </div>
      </HoverCardContent>
    </HoverCard>
  )
}
