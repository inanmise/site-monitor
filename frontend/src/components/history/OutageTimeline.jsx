import { useT } from '../../i18n/index.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import { formatDateSec } from '../../api/client'
import { navigateTo } from '../../utils/navigate.js'
import { isOutageAlert } from '../../utils/alertKinds.js'
import { alertNavParams } from '../admin/alerts/alertHistoryModel.js'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Kesinti zaman çizelgesi (2026-09-12, zenginleştirme #12): seçili aralık üzerinde alarm açılış→çözüm
 * segmentleri. Yeşil zemin = alarm yok; kırmızı/turuncu segment = açık alarm süresi (çözülmemişse aralık
 * sonuna dek). Üzerine gelince süre + tür (shadcn Tooltip); tıklayınca Alarm Geçmişi'nde o olay.
 * Grafik kütüphanesi yok; segment/çentikler shadcn Button (konum/genişlik satır içi — orandan hesaplanır).
 * `alerts`: [{ id, alert_type, alert_level, created_at, resolved, resolved_at }], `range`: { from, to } (UTC ISO, Z'siz).
 * Test kancaları: kök `data-slot="outage-timeline"`, segment `data-seg` (+ `data-open`), çentik `data-mark`.
 *
 * <p>2026-09-27: süre biçimi ve kesinti özeti dışa açıldı (`formatDuration`, `summarizeOutages`) — Kontrol
 * Geçmişi'nin "Kesinti" kutucuğu aynı hesabı kullanır; birimler artık i18n'den (eski sabit "dk/sa/g" İngilizce
 * arayüzde Türkçe kalıyordu).
 *
 * <p>DOKUNMATİK (2026-09-28, RESPONSIVE.md §4 — dokunma hedefi ≥ 40 px): segmentler (14 px yükseklik, en dar 3 px) ve
 * uyarı çentikleri (4×20 px) ETKİLEŞİMLİ (Alarm Geçmişi'ne gider) ama dokunmatikte hedef olamayacak kadar küçük; ayrıntı
 * (tür · seviye · başlangıç → bitiş · süre) yalnız ipucundaydı — dokunmatikte ipucu açılmaz. Öğe başına genişletilmiş
 * alan komşu çentik/segmentle ÇAKIŞIR (yanlış alarm açılır). Bu yüzden `pointer: coarse`'da çubuğun üstünü 40 px'lik TEK
 * bir katman kaplar (`ui/HintPopover` etkileşimli kipi): dokununca aralıktaki kesintiler ve uyarılar AYRINTILARIYLA
 * listelenir, her satır 40 px'lik düğme ve Alarm Geçmişi'nde o olayı açar. Fareli ekranda katman `display:none` —
 * masaüstü görünümü ve davranışı AYNI. Başlıktaki uyarı sayısının açıklaması da artık dokun-gör (`HintPopover`; eskiden
 * yalnız fareyle üzerine gelince, odaklanamayan bir `span`'daydı). Kancalar: `data-slot="outage-timeline-touch"`,
 * `outage-timeline-list`.
 */
export function ms(iso) { if (!iso) return NaN; return Date.parse(iso.endsWith('Z') ? iso : iso + 'Z') }

/** Dakika → "45 dk" / "2 sa 10 dk" / "3 g 4 sa". `t` verilmezse Türkçe kısaltmalar (saf birim testleri). */
export function formatDuration(minutes, t) {
  const u = (k, tr) => (t ? t(`otl.unit${k}`) : tr)
  const m = Math.max(0, Number(minutes) || 0)
  if (m < 60) return `${Math.max(1, Math.round(m))} ${u('Min', 'dk')}`
  const h = Math.floor(m / 60), r = Math.round(m % 60)
  if (h < 24) return r ? `${h} ${u('Hour', 'sa')} ${r} ${u('Min', 'dk')}` : `${h} ${u('Hour', 'sa')}`
  const d = Math.floor(h / 24)
  return `${d} ${u('Day', 'g')} ${h % 24} ${u('Hour', 'sa')}`
}

/**
 * Aralıktaki KESİNTİ alarmlarının özeti: segmentler (konum/genişlik yüzdesi), toplam kesinti dakikası,
 * erişilebilirlik yüzdesi. Uyarı türleri (HTTP_SSL, *_SLOW…) kesinti değildir — `marks` olarak ayrı döner.
 */
export function summarizeOutages(alerts = [], range) {
  if (!range?.from || !range?.to) return null
  const from = ms(range.from), to = ms(range.to)
  if (!(to > from)) return null
  const span = to - from
  const outages = alerts.filter((a) => isOutageAlert(a.alert_type))
  const advisories = alerts.filter((a) => !isOutageAlert(a.alert_type))
  const marks = advisories.map((a) => { const at = ms(a.created_at); if (!(at >= from && at <= to)) return null; return { a, left: ((at - from) / span) * 100, open: !(a.resolved && a.resolved_at) } }).filter(Boolean)
  const segs = outages.map((a) => {
    const s = Math.max(from, ms(a.created_at) || from)
    const e = a.resolved && a.resolved_at ? Math.min(to, ms(a.resolved_at)) : to
    if (!(e > s)) return null
    return { a, left: ((s - from) / span) * 100, width: Math.max(0.4, ((e - s) / span) * 100), minutes: (e - s) / 60000, open: !(a.resolved && a.resolved_at) }
  }).filter(Boolean)
  const downMinutes = segs.reduce((n, s) => n + s.minutes, 0)
  const rangeMinutes = span / 60000
  const availability = 100 - Math.min(100, (downMinutes / rangeMinutes) * 100)
  return { segs, marks, downMinutes, rangeMinutes, availability }
}

// Açık (çözülmemiş) segment çizgili — eski .otl-seg.is-open.
const STRIPES = 'bg-[repeating-linear-gradient(45deg,transparent_0_4px,rgba(255,255,255,.35)_4px_8px)]'
const SEG_TONE = { WARNING: 'bg-amber-600 hover:bg-amber-600', HIGH: 'bg-orange-600 hover:bg-orange-600' }
// `pointer-coarse:pointer-events-none`: dokunmatikte segment / çentik HEDEF ADAYI bile olmaz — çubuk ucundaki segment (en az 3 px)
// ya da çentik (−2 px) çubuktan 1–2 px taşıyor ve Chromium'un dokunma ayarlaması katmanın dışında açıkta kalan o parçayı seçip
// Alarm Geçmişi'ni açıyordu (Playwright 390×844, 2026-09-28). Klavye odağı etkilenmez; dokunmatikte yol katman + liste.
const BAR = 'absolute rounded-none border-0 p-0 hover:opacity-90 focus-visible:ring-0 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-foreground pointer-coarse:pointer-events-none'

const levelOf = (a) => (a.alert_level ? ` · ${a.alert_level}` : '')

/** Alarm Geçmişi'nde o alarmı açar — `alert` + tür + (çözülmüşse) `view=closed`: alertNavParams (E2, 2026-09-28e). */
const openAlert = (a) => navigateTo('alerthistory', alertNavParams(a))

/**
 * Dokunmatik liste — Popover içeriği (yalnız AÇIKKEN çizilir). Kesintiler ve uyarılar ayrı başlıkla; her satır 40 px'lik
 * düğme (tür · seviye + zaman aralığı · süre — ipucunun metni GÖRÜNÜR) ve Alarm Geçmişi'nde o olayı açar.
 */
function TimelineTouchList({ segs, marks, fmtDur, close, t }) {
  const open = (a) => { openAlert(a); close() }
  const row = (key, a, when, isOpen) => (
    <li key={key}>
      <Button type="button" variant="ghost" data-slot="outage-timeline-item"
        className="h-auto min-h-10 w-full flex-col items-start justify-center gap-0.5 px-2 py-1.5 text-left text-xs font-normal whitespace-normal"
        onClick={() => open(a)}>
        <span className="flex w-full min-w-0 flex-wrap items-center gap-1.5 font-semibold text-foreground">
          <span className="min-w-0 [overflow-wrap:anywhere]">{`${a.alert_type}${levelOf(a)}`}</span>
          {isOpen && <Badge variant="outline" className="h-5 px-1.5 text-[10.5px] font-semibold">{t('otl.stillOpen')}</Badge>}
        </span>
        <span className="text-muted-foreground tabular-nums">{when}</span>
      </Button>
    </li>
  )
  const HEAD = 'm-0 text-[11px] font-bold tracking-[.04em] text-muted-foreground uppercase'
  const LIST = 'm-0 flex list-none flex-col gap-1 p-0'
  return (
    <div data-slot="outage-timeline-list" className="flex max-h-72 min-w-0 flex-col gap-2 overflow-y-auto">
      <p className="m-0 text-sm font-semibold text-foreground">{t('otl.touchTitle')}</p>
      {segs.length > 0 && (
        <section className="flex min-w-0 flex-col gap-1">
          <p className={HEAD}>{t('otl.touchOutages')}</p>
          <ul className={LIST}>
            {segs.map((s, i) => row(`s-${s.a.id}-${i}`, s.a,
              `${formatDateSec(s.a.created_at)} → ${s.open ? t('otl.stillOpen') : formatDateSec(s.a.resolved_at)} · ${fmtDur(s.minutes)}`, s.open))}
          </ul>
        </section>
      )}
      {marks.length > 0 && (
        <section className="flex min-w-0 flex-col gap-1">
          <p className={HEAD}>{t('otl.touchAdvisories')}</p>
          <ul className={LIST}>
            {marks.map((m, i) => row(`m-${m.a.id}-${i}`, m.a, formatDateSec(m.a.created_at), m.open))}
          </ul>
          <p className="m-0 text-muted-foreground">{t('otl.advisoryHint')}</p>
        </section>
      )}
      <p className="m-0 text-muted-foreground">{t('otl.touchHint')}</p>
    </div>
  )
}

export default function OutageTimeline({ alerts = [], range }) {
  const t = useT()
  const sum = summarizeOutages(alerts, range)
  if (!sum) return null
  const { segs, marks, downMinutes, availability } = sum
  const fmtDur = (m) => formatDuration(m, t)

  return (
    <div data-slot="outage-timeline" className="rounded-lg border bg-card px-3 py-2.5 shadow-none" role="group" aria-label={t('otl.aria')}>
      <div className="mb-1.5 flex flex-wrap items-center justify-between gap-x-2 gap-y-1 text-[.78em]">
        <span className="font-bold tracking-[.04em] text-muted-foreground uppercase">{t('otl.title')}</span>
        <span className={cn('font-semibold', segs.length ? 'text-destructive' : 'text-success')}>
          {segs.length ? t('otl.summary', segs.length, fmtDur(downMinutes), availability.toLocaleString(dateLocale(), { minimumFractionDigits: 2, maximumFractionDigits: 2 })) : t('otl.none')}
        </span>
        {marks.length > 0 && (
          // Dokun-gör açıklama (fare, klavye, dokunmatik). `relative z-10`: çubuğun dokunmatik katmanı (üstte 13 px taşar)
          // bu düğmenin altında kalsın. Yazı boyu satırdan (Button'ın text-sm'i değil).
          <HintPopover content={t('otl.advisoryHint')}
            triggerClassName="relative z-10 ml-2.5 rounded-sm text-[length:inherit] pointer-coarse:min-h-10">
            <span className="text-[.86em] font-semibold text-amber-700 dark:text-amber-400">{t('otl.advisories', marks.length)}</span>
          </HintPopover>
        )}
      </div>
      {/* Konum kabı: dokunmatik katman çubuğun ortasına hizalı 40 px (−13 px üst … +27 px alt: çubuk + tarih satırı); yatayda
          4 px taşar — uçtaki segment/çentik çubuktan 1–2 px dışarı çıkabiliyor. */}
      <div className="relative">
        <div className="relative h-3.5 rounded-[7px] bg-success/30">
          {segs.map((s, i) => (
            <SimpleTooltip key={`${s.a.id}-${i}`}
              content={`${s.a.alert_type}${s.a.alert_level ? ` · ${s.a.alert_level}` : ''} · ${formatDateSec(s.a.created_at)} → ${s.open ? t('otl.stillOpen') : formatDateSec(s.a.resolved_at)} · ${fmtDur(s.minutes)}`}>
              <Button type="button" variant="ghost" data-seg="true" data-open={s.open ? 'true' : undefined}
                data-level={String(s.a.alert_level || '').toLowerCase() || undefined}
                className={cn(BAR, 'top-0 h-full min-w-[3px] bg-destructive hover:bg-destructive', SEG_TONE[s.a.alert_level], s.open && STRIPES)}
                style={{ left: `${s.left}%`, width: `${s.width}%` }}
                aria-label={`${s.a.alert_type} ${fmtDur(s.minutes)}`}
                onClick={() => openAlert(s.a)} />
            </SimpleTooltip>
          ))}
          {marks.map((m, i) => (
            <SimpleTooltip key={`m-${m.a.id}-${i}`}
              content={`${t('otl.advisory')}: ${m.a.alert_type}${m.a.alert_level ? ` · ${m.a.alert_level}` : ''} · ${formatDateSec(m.a.created_at)}${m.open ? ` · ${t('otl.stillOpen')}` : ''}`}>
              <Button type="button" variant="ghost" data-mark="true" data-open={m.open ? 'true' : undefined}
                className={cn(BAR, '-top-[3px] -ml-0.5 h-5 w-1 min-w-0 rounded-[2px] bg-amber-600 opacity-90 hover:bg-amber-600',
                  m.open && 'shadow-[0_0_0_2px_rgba(217,119,6,.3)]')}
                style={{ left: `${m.left}%` }}
                aria-label={`${t('otl.advisory')} ${m.a.alert_type}`}
                onClick={() => openAlert(m.a)} />
            </SimpleTooltip>
          ))}
        </div>
        <div className="mt-1 flex justify-between gap-2 text-[.7em] text-muted-foreground"><span>{formatDateSec(range.from)}</span><span>{formatDateSec(range.to)}</span></div>
        {(segs.length > 0 || marks.length > 0) && (
          <HintPopover interactive side="bottom" align="start" data-slot="outage-timeline-touch"
            aria-label={t('otl.touchOpen', segs.length + marks.length)} contentLabel={t('otl.touchTitle')}
            triggerClassName="absolute -inset-x-1 -top-[13px] hidden h-10 rounded-md pointer-coarse:block"
            className="w-[min(22rem,calc(100vw-1rem))] p-2"
            content={({ close }) => <TimelineTouchList segs={segs} marks={marks} fmtDur={fmtDur} close={close} t={t} />} />
        )}
      </div>
    </div>
  )
}
