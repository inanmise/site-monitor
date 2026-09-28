import { AlertTriangle, Info } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { formatPercent } from '../../i18n/dateLocale.js'
import { relativeTime } from '../admin/audit/auditFormat.js'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import NocBadge from '../noc/forms/NocBadge.jsx'
import MonitorSpark from '../ui/MonitorSpark.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import MonitorCardMeta from '../MonitorCardMeta.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter,
  MonitorCardRich, MonitorCardPending, MonitorAlarmIcon, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import { breachWeek, metersFor, overBudgetMeters, partsText, urlParts } from './pageSpeedCardModel.js'

/**
 * Sayfa Hızı izleme KARTI (2026-09-27 yeniden tasarım — shadcn, mobil duyarlı).
 *
 * <p>Kartın kalbi <b>performans paneli</b>: dört ölçü (yükleme, TTFB, boyut, istek) birer <b>bütçe ölçeri</b> —
 * büyük, okunur değer ("1.8 s", "340 ms", "1.2 MB", "84 req") + değer/bütçe çubuğu (shadcn Progress → `ui/Progress`
 * `ProgressBar`; el yapımı yüzde çubuğu YASAK, kapı progress-guard) + "Bütçe 2.5 s · %72" alt satırı. Ton: bütçenin
 * %80'ine kadar yeşil, %80–100 amber, aşınca kırmızı (bkz. pageSpeedCardModel.meterTone). Bütçesi olmayan ölçü
 * yalnız değeri gösterir. Eskiden aşılan eşikler ölçülerin altında ayrı bir rozet duvarıydı; artık aşan ölçer
 * kendisi vurgulanır (ton + uyarı simgesi + ekran okuyucuya "Bütçe aşıldı").
 *
 * <p>Paylaşılan standart parçalar SAYFADAN gelir (`selection` = toplu seçim kutusu, `meta` = MonitorCardMeta,
 * `actions` = MonitorCardActions): yetki kapıları ve olay işleyicileri sayfanın; kapı monitorCardStandard bu
 * parçaları sayfa kaynağında arar. Kart yalnız düzeni ve sayfa hızına özgü sunumu taşır.
 *
 * <p>Yerleşim: ölçerler telefonda ve dar kartta 2×2; kart 36rem'i geçince (tek sütunlu geniş ızgara) tek satırda
 * dörtlü (`@container`). Sol renk şeridi YOK (kalıcı kural) — durum rozetle; kesinti TÜM kenarla, aktif alarm
 * MonitorCard'ın tam dış çizgisiyle, duraklatılmış kart MonitorCard'ın kesik kenarıyla.
 *
 * <p>Test kancaları: `data-slot="pspd-meters"`, ölçer `data-slot="budget-meter"` + `data-metric` + `data-tone`
 * (ok|warn|crit|none) + `data-over`, değer `data-slot="meter-value"`, bütçe satırı `data-slot="meter-budget"`,
 * haftalık çip `data-slot="breach-week"` + `data-trend` (worse|better|same), hata satırı `data-slot="pspd-error"`.
 *
 * <p><b>Yoğunluk (2026-09-27, `density`).</b> Zengin (varsayılan) = yukarıdaki tam kart. Kompakt = 50 kartlık ızgarada
 * göz gezdirmelik özet: durum satırı + URL + TEK ana ölçü (yükleme süresi, bütçesine göre tonlu, ince bütçe çubuğu) +
 * gerekiyorsa TEK satırlık neden (kesintide hata metni, eşik aşımında aşan diğer ölçüler; tam metin dokun-gör açıklamada)
 * + yalnız takım rozeti + alt çubuk. Dört ölçer, mini trend/SLA, haftalık çip ve grup/vekil rozetleri yalnız Zengin'de
 * (MonitorCardRich — Kompakt'ta DOM'a girmez). Kompakt kancaları: `data-slot="pspd-primary"` (+ `data-tone`),
 * `pspd-primary-value`, `pspd-primary-budget`, neden `pspd-reason` (+ `data-reason` error|budget).
 *
 * <p><b>Hiç ölçüm yoksa (2026-09-28)</b> iki yoğunlukta da bekleme satırı `pspd-pending` (ölçerlerin "—" değerleri tek başına
 * boş bir kart gibi okunuyordu); `running` (sayfanın isRunning(id)) doluyken "İlk kontrol yapılıyor…" + dönen gösterge.
 */
export default function PageSpeedMonitorCard({
  monitor: m, status = 'unknown', badge, onOpen,
  selection, meta, actions,
  spark, sla, slaTarget, slaDays, week7, week14,
  density = 'rich', running = false,
}) {
  const t = useT()
  const meters = metersFor(m)
  const down = status === 'down'
  const compact = density === 'compact'
  const alarmLabel = `${t('pspd.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
  const rel = m.last_check ? relativeTime(m.last_check, t) : null
  return (
    <MonitorCard status={status} density={density} running={running} alarm={!!m.active_alarm} inactive={!m.active}
      // Kesinti (alarm henüz açılmamış olsa da): TÜM kenar kırmızı tonda — sol şerit değil (kalıcı kural).
      // Kompakt: dikey ritim bir kademe sıkı (dokunma alanları değişmez).
      className={cn(down && !m.active_alarm && m.active && 'border-destructive/45', compact && 'pt-3 sm:pt-3.5')}>
      <MonitorCardHeader>
        <MonitorCardTop className={compact ? 'mb-2' : undefined} end={
          <CopyLinkButton iconOnly url={monitorDeepLink('pagespeed', m.id)} targetName={m.url} variant="ghost" size="icon-xs" className={CARD_COPY} />
        }>
          {selection}
          {badge}
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          <span className={CARD_LAYER}><MaintenanceBadge target={m.url} /></span>
        </MonitorCardTop>
        <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.url)} title={m.url}
          className="mb-2 font-mono text-[13.5px] font-medium tracking-normal">
          <UrlText url={m.url} />
        </MonitorCardTitle>
      </MonitorCardHeader>

      <MonitorCardContent>
        {compact && <CompactSummary monitor={m} meters={meters} down={down} />}
        <MonitorCardRich>
          {(meta || m.noc_notify) && (
            <div className={cn(CARD_LAYER, 'mb-3 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5')}>
              {meta}
              {m.noc_notify && <NocBadge rowLabel={m.url} />}
            </div>
          )}
          {down && m.error && (
            <p data-slot="pspd-error" className="mb-2.5 flex min-w-0 items-start gap-1.5 text-xs leading-snug text-destructive">
              <AlertTriangle aria-hidden="true" className="mt-px size-3.5 shrink-0" />
              <span className="line-clamp-2 min-w-0 break-words">{m.error}</span>
            </p>
          )}
          {!m.last_check && <MonitorCardPending slot="pspd-pending" />}
          <div className="@container mb-2.5">
            <div data-slot="pspd-meters" role="group" aria-label={t('pspd.card.meters')}
              className="grid grid-cols-2 gap-2 @xl:grid-cols-4">
              {meters.map((mt) => <BudgetMeter key={mt.key} meter={mt} />)}
            </div>
          </div>
          <div className={CARD_LAYER}>
            <MonitorSpark rowLabel={m.url} spark={spark} sla={sla} slaTarget={slaTarget} slaDays={slaDays} />
          </div>
          {/* Eşik üstü karşılaştırması (2026-09-12, #15): bu hafta / geçen hafta (14 gün − 7 gün) */}
          <BreachWeekChip w7={week7} w14={week14} />
        </MonitorCardRich>
      </MonitorCardContent>

      {/* mt-auto: ızgara satırındaki kartlar aynı boya uzar — alt çubuk kısa kartta da en altta hizalı kalır (Sentetik kartıyla aynı). */}
      <MonitorCardFooter actions={actions || null} className="mt-auto">
        {m.last_check ? (
          // Göreli zaman görünür; kesin damga ipucunda (ve detay penceresinin özetinde). Örtünün üstünde → ipucu açılır.
          <time dateTime={m.last_check} title={formatDateSec(m.last_check)} className={CARD_LAYER}>
            {rel || formatDateSec(m.last_check)}
          </time>
        ) : ''}
      </MonitorCardFooter>
    </MonitorCard>
  )
}

/** Başlık URL'i: şema yalnız https DEĞİLSE (soluk), host vurgulu, yol + sorgu soluk; kırpma MonitorCardTitle'da. */
function UrlText({ url }) {
  const { scheme, host, path } = urlParts(url)
  return (
    <>
      {scheme && <span className="font-normal text-muted-foreground">{scheme}</span>}
      <span className="font-semibold text-foreground">{host}</span>
      {path && <span className="font-normal text-muted-foreground">{path}</span>}
    </>
  )
}

/**
 * Kompakt gövde: ana ölçü (yükleme) → gerekiyorsa tek satır neden → yalnız takım rozeti. Takım rozeti paylaşılan
 * MonitorCardMeta'dan (aynı görünüm ve test kancaları `monitor-card-meta` / `meta-team`), grup ve vekil alanları
 * verilmeden — onlar Zengin'in meta satırında.
 */
function CompactSummary({ monitor: m, meters, down }) {
  const t = useT()
  const reqUnit = t('pspd.card.reqUnit')
  let reason = null
  if (down && m.error) {
    reason = { kind: 'error', text: String(m.error) }
  } else {
    const over = overBudgetMeters(meters)
    if (over.length) {
      // İstek sayısında birim yazılmaz: etiket zaten "İstek" ("İstek 120 (eşik 100)", "istek" tekrarı yok).
      const unitFor = (mt) => (mt.key === 'requests' ? '' : reqUnit)
      const items = over.map((mt) => t('pspd.card.overItem', t(mt.labelKey), partsText(mt.display, unitFor(mt)), partsText(mt.budgetDisplay, unitFor(mt))))
      reason = { kind: 'budget', text: t('pspd.card.overReason', items.join(' · ')) }
    }
  }
  return (
    <>
      {m.last_check ? <PrimaryMeter meter={meters[0]} /> : <MonitorCardPending slot="pspd-pending" compact className="mt-0 mb-2.5" />}
      {reason && <CompactReason reason={reason} />}
      {m.team_name && (
        <div className={cn(CARD_LAYER, 'mb-2')}>
          <MonitorCardMeta monitor={{ team_id: m.team_id, team_name: m.team_name }} />
        </div>
      )}
    </>
  )
}

/**
 * Kompakt kartın TEK ana ölçüsü — yükleme süresi: etiket + okunur değer (bütçe tonunda) + sağda "Eşik 2.5 s · %72" +
 * altında ince bütçe çubuğu (ölçerlerle aynı ton dili; bütçe yoksa çubuk ve bütçe metni yok). Etkileşimsiz: tıklama
 * kartın örtüsüne düşer, detayı açar.
 */
function PrimaryMeter({ meter }) {
  const t = useT()
  const reqUnit = t('pspd.card.reqUnit')
  const d = meter.display
  const tone = meter.tone
  return (
    <div data-slot="pspd-primary" data-metric={meter.key} data-tone={tone ?? 'none'} className="mb-2.5 min-w-0">
      <div className="flex min-w-0 items-baseline justify-between gap-x-2">
        <span className="flex min-w-0 items-baseline gap-2">
          <span className="shrink-0 text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{t(meter.labelKey)}</span>
          <span data-slot="pspd-primary-value"
            className={cn('min-w-0 truncate text-base leading-tight font-semibold tracking-tight text-foreground tabular-nums', VALUE[tone])}>
            {d ? (
              <>
                {d.prefix && <>{d.prefix} </>}{d.num}{' '}
                <span className="text-xs font-medium tracking-normal text-muted-foreground">{d.unit ?? reqUnit}</span>
              </>
            ) : '—'}
          </span>
          {tone === 'crit' && <AlertTriangle aria-hidden="true" className="size-3.5 shrink-0 self-center text-destructive" />}
        </span>
        {meter.budget != null && (
          <span data-slot="pspd-primary-budget" className="shrink-0 text-[10.5px] leading-tight text-muted-foreground tabular-nums">
            {t('pspd.card.budget', partsText(meter.budgetDisplay, reqUnit))}
            {meter.percent != null && <> · <span className={PCT[tone]}>{formatPercent(meter.percent)}</span></>}
          </span>
        )}
      </div>
      {meter.budget != null && meter.ratio != null && (
        <ProgressBar size="sm" className="mt-1.5 h-1" decorative tone={tone ?? undefined}
          value={Math.min(meter.value, meter.budget)} max={meter.budget} />
      )}
      {tone && <span className="sr-only">{t(SR_TONE[tone])}</span>}
    </div>
  )
}

/**
 * Kompakt kartın TEK satırlık nedeni — kırpılır; tam metin dokun-gör açıklamada (HintPopover: fare, klavye VE dokunmatik;
 * yalnız-hover ipucu telefonda açılmazdı). Düğmenin erişilebilir adı tam metindir (kırpma yalnız görsel). Dokunmatikte
 * 32 px taban ::after ile dikeyde 40 px'e genişler.
 */
function CompactReason({ reason }) {
  return (
    <HintPopover content={reason.text}
      // has-[>svg]:px-0: shadcn Button'ın ikonlu düğme dolgusu (has-[>svg]:px-3) p-0'a rağmen kalır → satır içeri kayardı.
      triggerClassName={cn(CARD_LAYER, 'mb-2 flex w-full min-w-0 justify-start gap-1.5 rounded-md text-left text-xs leading-snug font-medium has-[>svg]:px-0',
        'text-destructive hover:text-destructive dark:hover:text-destructive',
        'pointer-coarse:after:absolute pointer-coarse:after:inset-x-0 pointer-coarse:after:-inset-y-1')}>
      <AlertTriangle aria-hidden="true" className="size-3.5 shrink-0" />
      <span data-slot="pspd-reason" data-reason={reason.kind} className="min-w-0 truncate">{reason.text}</span>
    </HintPopover>
  )
}

const TILE = {
  crit: 'border-destructive/40 bg-destructive/5 dark:bg-destructive/10',
  warn: 'border-amber-500/45 bg-amber-500/5 dark:bg-amber-500/10',
}
const VALUE = { crit: 'text-destructive', warn: 'text-amber-700 dark:text-amber-400' }
const PCT = { crit: 'font-semibold text-destructive', warn: 'font-semibold text-amber-700 dark:text-amber-400' }
const SR_TONE = { crit: 'pspd.card.overBudget', warn: 'pspd.card.nearBudget', ok: 'pspd.card.withinBudget' }

/** Tek bütçe ölçeri (bkz. dosya başı). Etkileşimsiz: tıklama kartın örtüsüne düşer, detayı açar. */
function BudgetMeter({ meter }) {
  const t = useT()
  const reqUnit = t('pspd.card.reqUnit')
  const label = t(meter.labelKey)
  const d = meter.display
  const tone = meter.tone
  return (
    <div data-slot="budget-meter" data-metric={meter.key} data-tone={tone ?? 'none'} data-over={tone === 'crit' ? 'true' : undefined}
      className={cn('flex min-w-0 flex-col rounded-lg border bg-muted/40 px-2.5 py-2 dark:bg-muted/25', TILE[tone])}>
      <div className="flex min-w-0 items-center justify-between gap-1">
        <span className="min-w-0 truncate text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{label}</span>
        {tone === 'crit' && <AlertTriangle aria-hidden="true" className="size-3.5 shrink-0 text-destructive" />}
      </div>
      <div className="mt-1 flex min-w-0 items-center gap-1">
        <span data-slot="meter-value"
          className={cn('min-w-0 truncate text-lg leading-tight font-semibold tracking-tight text-foreground tabular-nums', VALUE[tone])}>
          {d ? (
            <>
              {d.prefix && <>{d.prefix} </>}{d.num}{' '}
              <span className="text-xs font-medium tracking-normal text-muted-foreground">{d.unit ?? reqUnit}</span>
            </>
          ) : '—'}
        </span>
        {meter.lowerBound && d && (
          // "≥" neden? — dokunmatikte de açılan açıklama (HintPopover); ikon 20 px, dokunmatikte ::after ile 40 px alan.
          <HintPopover content={meter.key === 'size' ? t('pspd.truncatedHint') : t('pspd.cappedWarn')}
            aria-label={t('pspd.card.lowerBound', label)}
            triggerClassName={cn(CARD_LAYER, 'size-5 shrink-0 text-muted-foreground hover:text-foreground pointer-coarse:min-h-0 pointer-coarse:after:absolute pointer-coarse:after:-inset-2.5')}>
            <Info aria-hidden="true" className="size-3.5" />
          </HintPopover>
        )}
      </div>
      {meter.budget != null && (
        <div className="mt-auto pt-1.5">
          {meter.ratio != null && (
            <ProgressBar size="sm" className="h-1.5" decorative tone={tone ?? undefined}
              value={Math.min(meter.value, meter.budget)} max={meter.budget} />
          )}
          <div data-slot="meter-budget" className="mt-1 flex min-w-0 items-baseline justify-between gap-1.5 text-[10.5px] leading-tight text-muted-foreground tabular-nums">
            <span className="min-w-0 truncate">{t('pspd.card.budget', partsText(meter.budgetDisplay, reqUnit))}</span>
            {meter.percent != null && <span className={cn('shrink-0', PCT[tone])}>{formatPercent(meter.percent)}</span>}
          </div>
        </div>
      )}
      {meter.budget == null && (
        <div data-slot="meter-budget" className="mt-auto pt-1.5 text-[10.5px] leading-tight text-muted-foreground">{t('pspd.card.noBudget')}</div>
      )}
      {tone && <span className="sr-only">{t(SR_TONE[tone])}</span>}
    </div>
  )
}

const TREND_TONE = {
  worse: 'border-destructive/35 text-destructive',
  better: 'border-success/40 text-success',
  same: 'text-muted-foreground',
}

/**
 * Haftalık eşik üstü çipi: "Bu hafta eşik aşımı: 6 · ▲4 geçen haftaya göre". Açıklama (ne sayılıyor, iki haftanın
 * ham sayıları) dokunmatikte de açılan HintPopover'da — eski satır yalnız fareyle açılan ipucu taşıyordu.
 */
function BreachWeekChip({ w7, w14 }) {
  const t = useT()
  const w = breachWeek(w7, w14)
  if (!w) return null
  const abs = Math.abs(w.delta)
  const count = w.thisWeek === 0 ? t('pspd.card.weekNone') : t('pspd.card.weekCount', w.thisWeek)
  // Görünen fark oklu ve kısa ("▲4 geçen haftaya göre"); ekran okuyucu adı düz cümle ("geçen haftadan 4 fazla").
  const shown = w.trend === 'same' ? t('pspd.card.weekSame') : t('pspd.card.vsLastWeek', `${w.trend === 'worse' ? '▲' : '▼'}${abs}`)
  const spoken = w.trend === 'same' ? shown : t(w.trend === 'worse' ? 'pspd.card.weekMore' : 'pspd.card.weekFewer', abs)
  return (
    <HintPopover content={`${t('pspd.breachWeekTip')}\n${t('pspd.breachWeek', w.thisWeek, w.lastWeek)}`}
      aria-label={`${count}, ${spoken}`}
      // mb-2: dokunmatikteki 40 px alan (::after ±10 px) alt çubuğun eylem düğmeleriyle ÇAKIŞMASIN (Playwright ölçümü).
      triggerClassName={cn(CARD_LAYER, 'mt-1.5 mb-2 max-w-full rounded-full pointer-coarse:min-h-0 pointer-coarse:after:absolute pointer-coarse:after:-inset-y-2.5 pointer-coarse:after:inset-x-0')}>
      <Badge variant="outline" data-slot="breach-week" data-trend={w.trend}
        className={cn('max-w-full gap-1.5 px-2.5 py-0.5 text-[11px] font-medium tabular-nums', TREND_TONE[w.trend])}>
        <span className="truncate">{count}</span>
        <span aria-hidden="true" className="text-muted-foreground">·</span>
        <span className={cn('truncate', w.trend === 'same' && 'text-muted-foreground')}>{shown}</span>
      </Badge>
    </HintPopover>
  )
}
