import { ArrowLeftRight, ArrowRight, CirclePlus, Database, Repeat, ShieldAlert, ShieldCheck, Snail } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import NocBadge from '../noc/forms/NocBadge.jsx'
import MonitorCardMeta from '../MonitorCardMeta.jsx'
import { TimeAgo, relTime } from '../admin/monitorchanges/changeParts.jsx'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardMetrics,
  MonitorMetric, MonitorCardFooter, MonitorAlarmIcon, MonitorCardRich, MonitorPendingText, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import { Alert, AlertDescription, AlertTitle } from '@/components/shadcn/alert'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { LONG_VALUE, formatTtl, mxParts, responseTone, shortValue, splitValues, unexpectedValues } from './dnsValue.js'

/** Kartta gösterilen en çok değer sayısı; kalanlar "+N daha" dokun-gör balonunda (tam liste detay penceresinde). */
const MAX_SHOWN = 3

/**
 * Değer satırındaki kopyala düğmesi: satırın üzerine gelince/odakta görünür (kart üzerindeyken soluk ipucu), dokunmatikte
 * (hover yok) hep görünür ve 40 px dokunma hedefi.
 */
const VALUE_COPY = cn(
  'shrink-0 text-muted-foreground opacity-0 transition-opacity motion-reduce:transition-none',
  'group-hover/mcard:opacity-40 group-hover/val:opacity-100 focus-visible:opacity-100',
  'pointer-coarse:size-10 pointer-coarse:opacity-100',
)

/** Kart içindeki dokun-gör çipinin tetiği: örtünün üstünde, dokunmatikte 40 px yüksek. */
const CHIP_TRIGGER = cn(CARD_LAYER, 'rounded-sm pointer-coarse:min-h-10')

/**
 * DNS izleme KARTI — ortak kart ailesinin (monitoring/MonitorCard) DNS'e özgü yüzü (2026-09-27 yeniden tasarım).
 *
 * <p><b>Kartın kalbi değer panelidir:</b> DNS'te asıl soru "çözümlenen değer ne, değişti mi?". Değerler (A/AAAA/MX/TXT/NS
 * birden çok olabilir) tek tek, eş aralıklı yazıyla listelenir; ilk {@link MAX_SHOWN} tanesi görünür, kalanı "+N daha"
 * çipinde. Uzun TXT değeri iki satırda kırpılır, tamamı dokun-gör balonunda (telefonda da açılır). Her değer ham hâliyle
 * kopyalanabilir (beklenen-değer kilidine aynen yapıştırılabilsin). Durum dili:
 * <ul>
 *   <li><b>Değişti</b> (`changed`) — amber çağrı: ne zaman tespit edildi + (sunucu `previous_value` gönderirse) önceki →
 *       güncel. Çağrı örtünün ALTINDA kalır: tıklamak detayı açar, farkın tamamı Kontrol Geçmişi'nde.</li>
 *   <li><b>Rotasyon</b> (`rotated`) — nötr çip, "kesinti değil": round-robin kümesi değişti, alarm konusu değil.</li>
 *   <li><b>Beklenmeyen değer</b> — beklenen liste (kilit) doluyken listede olmayan değer: satır ve alt çizgi yıkıcı tonda
 *       (backend `unexpectedValues` ile aynı kural: alt küme/rotasyon sapma sayılmaz). Kilit tutuyorsa yeşil çip.</li>
 * </ul>
 *
 * <p><b>Kaynak rozeti:</b> DNS izlemeleri İKİ kaynaklıdır — envanterden türetilen (takım envanterden, "silmek" izlemeyi
 * durdurur) ve DNS sayfasından eklenen bağımsız izleme. Rozet her kartta hangisi olduğunu söyler, açıklaması dokun-gör.
 *
 * <p><b>Sayfanın payı:</b> yetkiye/seçime/eylemlere bağlı parçalar sayfada kurulur ve yuva olarak gelir — `select`
 * (toplu seçim kutusu), `meta` (MonitorCardMeta), `spark` (MonitorSpark), `actions` (MonitorCardActions, türev satırın
 * "izlemeyi durdur" adıyla). Durum sözlüğü de sayfanındır (`status` + `statusBadge`; detay penceresiyle ortak).
 * `running` = sayfanın isRunning(id): hiç sonucu olmayan kartta "İlk kontrol bekleniyor" yerine "İlk kontrol yapılıyor…".
 * Kartta sol renk şeridi YOK — durum rozetle, aktif alarm kartın tüm kenarıyla (MonitorCard).
 *
 * <p><b>Yoğunluk (2026-09-27, Kompakt / Zengin):</b> `density="compact"` kartı taranabilir özete indirir — üst satır,
 * alan adı + (varsa) ad, TEK satırlık değer özeti ({@link DnsCompactSummary}: ilk değer · "+N daha" · "Değer değişti"
 * işareti), sorun varsa tek satır neden (beklenmeyen değer / yavaş çözümleme; tamamı dokun-gör), kaynak rozeti + YALNIZ
 * takım rozeti (grup/vekil yok), alt çubuk. Değer paneli, TTL/yanıt ölçüleri ve mini trend {@link MonitorCardRich}
 * içindedir (Kompakt'ta DOM'a girmez). Zengin = bugünkü tam kart, sıra ve içerik değişmedi.
 *
 * Test kancaları: `data-slot` = dns-record-type · dns-source (`data-source`) · dns-values (`data-count`) · dns-value
 * (`data-unexpected`) · dns-more-values · dns-values-empty · dns-changed · dns-rotation · dns-expected-ok · dns-mismatch ·
 * dns-response (`data-tone`) · Kompakt: dns-compact (`data-count`) · dns-compact-value · dns-compact-more ·
 * dns-compact-changed · dns-compact-reason (`data-reason` mismatch|slow).
 */
export default function DnsMonitorCard({
  monitor: m, status = 'unknown', statusBadge, alarmLabel, onOpen, select, meta, spark, actions, density = 'rich', running = false,
}) {
  const t = useT()
  const compact = density === 'compact'
  const values = splitValues(m.value)
  const expected = splitValues(m.expected_value)
  const unexpected = unexpectedValues(m.expected_value, values)
  const ttl = formatTtl(m.ttl, t)
  const tone = responseTone(m.response_ms, m.slow_threshold_ms)
  const name = m.name && m.name !== m.domain ? m.name : null

  return (
    <MonitorCard density={density} running={running} status={status} alarm={!!m.active_alarm} inactive={!m.active} data-record-type={m.record_type || undefined}>
      <MonitorCardHeader>
        <MonitorCardTop className={compact ? 'mb-2' : undefined} end={<>
          <Badge variant="outline" data-slot="dns-record-type"
            className="rounded-sm border-primary/30 bg-primary/5 px-1.5 font-mono text-[11px] font-bold tracking-wide text-primary dark:bg-primary/15">
            <span className="sr-only">{t('dns.recordType')}: </span>{m.record_type}
          </Badge>
          <CopyLinkButton iconOnly url={monitorDeepLink('dns', m.id)} targetName={m.domain} variant="ghost" size="icon-xs" className={CARD_COPY} />
        </>}>
          {select}
          {statusBadge}
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          <span className={CARD_LAYER}><MaintenanceBadge target={m.domain} /></span>
        </MonitorCardTop>
        {/* Başlık = alan adı (eş aralıklı; gerçek düğme, örtüsü kartı kaplar) + alan adını kopyala */}
        <div className={cn('flex min-w-0 items-center gap-1', name ? 'mb-0.5' : compact ? 'mb-2' : 'mb-2.5')}>
          <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.domain)} title={m.domain}
            className="mb-0 w-auto min-w-0 flex-1 font-mono text-[14px] tracking-normal">
            {m.domain}
          </MonitorCardTitle>
          <CopyButton value={m.domain} label={t('dns.cardCopy', m.domain)} copiedLabel={t('dns.cardCopied')}
            variant="ghost" buttonSize="icon-xs" size={12} className={CARD_COPY} />
        </div>
        {name && <p data-slot="dns-name" className={cn('truncate text-xs text-muted-foreground', compact ? 'mb-2' : 'mb-2.5')} title={name}>{name}</p>}
      </MonitorCardHeader>

      <MonitorCardContent>
        {compact && <DnsCompactSummary m={m} values={values} expected={expected} unexpected={unexpected} tone={tone} />}

        {/* Kaynak rozeti İKİ görünümde de (DNS iki kaynaklı: envanter türevi / bağımsız). Kompakt'ta yanında YALNIZ takım
            rozeti — grup ve vekil çipleri Zengin'e kalır. */}
        <div className={cn(CARD_LAYER, 'flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1', compact ? 'mb-2' : 'mb-2.5')}>
          <SourceBadge standalone={!!m.standalone} />
          {compact ? <TeamOnly m={m} /> : meta}
          {/* 7/24 rozeti yalnız Zengin'de (satır Kompakt ile ortak → MonitorCardRich, display:contents) */}
          {m.noc_notify && <MonitorCardRich className="contents"><NocBadge rowLabel={m.domain} /></MonitorCardRich>}
        </div>

        <MonitorCardRich>
          <ValuePanel m={m} values={values} expected={expected} unexpected={unexpected} />

          {(ttl || m.response_ms != null) && (
            <MonitorCardMetrics>
              {ttl && (
                <MonitorMetric value={ttl} label={t('dns.ttl')}
                  hint={`${t('dns.cardTtlSeconds', m.ttl)} · ${t('dns.ttlExplain')}`} />
              )}
              {m.response_ms != null && (
                <MonitorMetric label={t('dns.responseMs')}
                  valueClassName={tone === 'slow' ? 'text-amber-700 dark:text-amber-400' : undefined}
                  hint={m.slow_threshold_ms != null
                    ? (tone === 'slow' ? t('dns.cardSlowAbove', m.slow_threshold_ms) : t('dns.cardSlowThreshold', m.slow_threshold_ms))
                    : undefined}
                  value={
                    <span data-slot="dns-response" data-tone={tone || undefined} className="inline-flex items-center gap-1">
                      {tone === 'slow' && <Snail aria-hidden="true" className="size-3.5" />}
                      {m.response_ms} ms
                      {tone === 'slow' && <span className="sr-only"> — {t('dns.testSlow')}</span>}
                    </span>
                  } />
              )}
            </MonitorCardMetrics>
          )}

          {spark && <div className={CARD_LAYER}>{spark}</div>}
        </MonitorCardRich>
      </MonitorCardContent>

      {/* mt-auto: ızgara satırındaki kartlar aynı boya uzar — alt çubuk kısa kartta da en altta hizalı kalır (Port/Sayfa ile aynı). */}
      <MonitorCardFooter actions={actions || null} className="mt-auto">
        {m.checked_at ? <TimeAgo at={m.checked_at} t={t} className={CARD_LAYER} /> : ''}
      </MonitorCardFooter>
    </MonitorCard>
  )
}

/** Kompakt'ta takım: YALNIZ takım rozeti (ortak MonitorCardMeta'nın takım öğesi; grup/vekil alanı verilmez). */
function TeamOnly({ m }) {
  if (!m.team_name) return null
  return <MonitorCardMeta monitor={{ team_id: m.team_id, team_name: m.team_name }} />
}

/**
 * Kompakt DEĞER ÖZETİ — tek satır: ilk çözümlenen değer (eş aralıklı, kırpılır; beklenmeyense yıkıcı ton) · "+N daha"
 * sayacı · "Değer değişti" işareti. Değer yoksa "Yanıt yok" / "İlk kontrol bekleniyor". Sorun varsa altında TEK satır
 * neden (beklenmeyen değer, yoksa izlemenin eşiğini aşan yavaş çözümleme) — kırpılır, tamamı dokun-gör balonunda.
 * Değer satırı örtünün ALTINDA kalır: dokunmak detayı açar (tam liste orada ve Zengin görünümde).
 */
function DnsCompactSummary({ m, values, expected, unexpected, tone }) {
  const t = useT()
  const first = values[0]
  const more = values.length - 1
  const mx = first != null && m.record_type === 'MX' ? mxParts(first) : null
  const failed = values.length === 0 && !!m.checked_at
  const firstBad = first != null && unexpected.includes(first)
  let reason = null
  if (unexpected.length > 0) {
    reason = {
      kind: 'mismatch',
      text: t('dns.cardMismatch', unexpected.map((v) => shortValue(v)).join(', ')),
      full: `${t('dns.cardMismatch', unexpected.join(', '))}\n${t('dns.cardExpectedList', expected.join(', '))}`,
    }
  } else if (tone === 'slow') {
    const text = t('dns.cardSlowReason', m.response_ms, m.slow_threshold_ms)
    reason = { kind: 'slow', text, full: text }
  }
  return (
    <div data-slot="dns-compact" data-count={values.length}
      className="mb-2 min-w-0 rounded-md border bg-muted/40 px-2.5 py-1.5 dark:bg-muted/20">
      <p className="flex min-w-0 items-center gap-1.5 text-[12.5px] leading-5">
        {first != null ? (
          <>
            <span data-slot="dns-compact-value" data-unexpected={firstBad ? 'true' : undefined}
              className={cn('min-w-0 truncate font-mono', firstBad ? 'font-semibold text-destructive' : 'text-foreground')}>
              {mx ? <><span className="mr-1.5 text-muted-foreground tabular-nums">{mx.priority}</span>{mx.host}</> : first}
              {firstBad && <span className="sr-only"> — {t('dns.testUnexpected')}</span>}
            </span>
            {more > 0 && (
              <span data-slot="dns-compact-more" className="shrink-0 text-[11px] font-semibold text-muted-foreground tabular-nums">
                {t('dns.cardMoreValues', more)}
              </span>
            )}
          </>
        ) : (
          <span data-slot="dns-compact-empty" data-failed={failed ? 'true' : undefined}
            className={cn('min-w-0 truncate', failed && m.active_alarm ? 'font-medium text-destructive' : 'text-muted-foreground italic')}>
            {failed ? t('dns.cardNoAnswer') : <MonitorPendingText idle={t('dns.cardAwaiting')} icon={null} />}
          </span>
        )}
        {m.changed && (
          <Badge variant="warning" data-slot="dns-compact-changed" className="ml-auto h-5 rounded-sm px-1.5 text-[10.5px] font-semibold">
            <ArrowLeftRight aria-hidden="true" />{t('dns.cardChanged')}
          </Badge>
        )}
      </p>
      {reason && (
        <HintPopover content={reason.full} className="max-h-64 overflow-y-auto [overflow-wrap:anywhere]"
          triggerClassName={cn(CARD_LAYER, 'mt-1 flex w-full min-w-0 items-center justify-start gap-1.5 rounded-sm text-left text-xs font-medium has-[>svg]:px-0 pointer-coarse:min-h-10',
            reason.kind === 'slow'
              ? 'text-amber-700 hover:text-amber-700 dark:text-amber-400 dark:hover:text-amber-400'
              : 'text-destructive hover:text-destructive dark:hover:text-destructive')}>
          {reason.kind === 'slow' ? <Snail aria-hidden="true" className="size-3.5 shrink-0" /> : <ShieldAlert aria-hidden="true" className="size-3.5 shrink-0" />}
          <span data-slot="dns-compact-reason" data-reason={reason.kind} className="min-w-0 truncate">{reason.text}</span>
        </HintPopover>
      )}
    </div>
  )
}

/** Kaynak rozeti: bağımsız (DNS sayfasından eklendi) ya da envanterden türetilmiş — açıklaması dokun-gör balonunda. */
function SourceBadge({ standalone }) {
  const t = useT()
  const Icon = standalone ? CirclePlus : Database
  return (
    <HintPopover content={standalone ? t('dns.standaloneHint') : t('dns.cardFromInventoryHint')} triggerClassName={CHIP_TRIGGER}>
      <Badge variant={standalone ? 'secondary' : 'outline'} data-slot="dns-source" data-source={standalone ? 'standalone' : 'inventory'}
        className={cn('rounded-sm px-1.5 text-[10.5px] font-semibold',
          standalone ? 'bg-primary/10 text-primary dark:bg-primary/20' : 'text-muted-foreground')}>
        <Icon aria-hidden="true" />{standalone ? t('dns.standalone') : t('dns.cardFromInventory')}
      </Badge>
    </HintPopover>
  )
}

/** Değer paneli: başlık (sayı + rotasyon/kilit çipi) · değer listesi · "+N daha" · değişti çağrısı · beklenmeyen değer. */
function ValuePanel({ m, values, expected, unexpected }) {
  const t = useT()
  const shown = values.slice(0, MAX_SHOWN)
  const hidden = values.slice(MAX_SHOWN)
  const mismatch = unexpected.length > 0
  const lockHolds = expected.length > 0 && values.length > 0 && !mismatch
  const rotated = !m.changed && !!m.rotated
  const failed = values.length === 0 && !!m.checked_at
  return (
    <div data-slot="dns-values" data-count={values.length}
      className="mb-3 min-w-0 rounded-lg border bg-muted/40 px-3 pt-2 pb-2.5 dark:bg-muted/20">
      <div className="mb-1 flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1">
        <span className="text-[10px] font-semibold tracking-[.05em] text-muted-foreground uppercase">
          {t('dns.cardValues')}
          {values.length > 1 && <span className="tabular-nums"> · {values.length}</span>}
        </span>
        {(rotated || lockHolds) && (
          <span className="flex flex-wrap items-center gap-1">
            {rotated && (
              <HintPopover content={t('dns.rotationTitle')} align="end" triggerClassName={CHIP_TRIGGER}>
                <Badge variant="outline" data-slot="dns-rotation" className="rounded-sm px-1.5 text-[10.5px] font-semibold text-muted-foreground">
                  <Repeat aria-hidden="true" />{t('dns.cardRotation')}
                </Badge>
              </HintPopover>
            )}
            {lockHolds && (
              <HintPopover content={t('dns.cardExpectedList', expected.join(', '))} align="end" triggerClassName={CHIP_TRIGGER}>
                <Badge variant="outline" data-slot="dns-expected-ok"
                  className="rounded-sm border-success/35 px-1.5 text-[10.5px] font-semibold text-success">
                  <ShieldCheck aria-hidden="true" />{t('dns.cardExpectedOk')}
                </Badge>
              </HintPopover>
            )}
          </span>
        )}
      </div>

      {values.length > 0 ? (
        <ul className="m-0 flex min-w-0 list-none flex-col p-0">
          {shown.map((v, i) => (
            <ValueRow key={`${i}:${v}`} value={v} type={m.record_type} unexpected={unexpected.includes(v)} />
          ))}
        </ul>
      ) : (
        <p data-slot="dns-values-empty" data-failed={failed ? 'true' : undefined}
          className={cn('py-1 text-[12.5px]', failed && m.active_alarm ? 'font-medium text-destructive' : 'text-muted-foreground italic')}>
          {failed ? t('dns.cardNoAnswer') : <MonitorPendingText idle={t('dns.cardAwaiting')} icon={null} />}
        </p>
      )}

      {hidden.length > 0 && (
        <HintPopover content={hidden.join('\n')} className="max-h-64 overflow-y-auto font-mono break-all"
          aria-label={t('a11y.rowAction', m.domain, t('dns.cardMoreValues', hidden.length))}
          triggerClassName={cn(CHIP_TRIGGER, 'mt-1')}>
          <Badge variant="secondary" data-slot="dns-more-values" className="rounded-sm px-1.5 text-[10.5px] font-semibold tabular-nums">
            {t('dns.cardMoreValues', hidden.length)}
          </Badge>
        </HintPopover>
      )}

      {m.changed && <ChangedCallout m={m} values={values} />}

      {mismatch && (
        <HintPopover content={t('dns.cardExpectedList', expected.join(', '))}
          triggerClassName={cn(CARD_LAYER, 'mt-2 flex w-full min-w-0 items-start justify-start gap-1.5 rounded-sm has-[>svg]:px-0 text-left text-[12px] font-semibold whitespace-normal text-destructive hover:text-destructive pointer-coarse:min-h-10 dark:hover:text-destructive')}>
          <ShieldAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
          <span data-slot="dns-mismatch" className="min-w-0 [overflow-wrap:anywhere]">
            {t('dns.cardMismatch', unexpected.map((v) => shortValue(v)).join(', '))}
          </span>
        </HintPopover>
      )}
    </div>
  )
}

/** Tek değer satırı: eş aralıklı değer (MX önceliği soluk), uzun değerde tamamı dokun-gör, satırın kopyala düğmesi. */
function ValueRow({ value, type, unexpected }) {
  const t = useT()
  const long = value.length > LONG_VALUE
  const mx = type === 'MX' ? mxParts(value) : null
  const text = (
    <span className={cn('min-w-0 font-mono text-[12.5px] leading-5 break-all line-clamp-2',
      unexpected ? 'font-semibold text-destructive' : 'text-foreground')}>
      {mx ? <><span className="mr-1.5 text-muted-foreground tabular-nums">{mx.priority}</span>{mx.host}</> : value}
      {unexpected && <span className="sr-only"> — {t('dns.testUnexpected')}</span>}
    </span>
  )
  return (
    <li data-slot="dns-value" data-unexpected={unexpected ? 'true' : undefined}
      className={cn(CARD_LAYER, 'group/val -mx-1 flex min-w-0 items-center gap-1 rounded-md px-1 hover:bg-background/70')}>
      {long ? (
        <HintPopover content={value} className="max-h-64 overflow-y-auto font-mono break-all"
          triggerClassName="min-w-0 flex-1 justify-start rounded-sm py-0.5 text-left font-normal whitespace-normal pointer-coarse:min-h-10">
          {text}
        </HintPopover>
      ) : (
        <span className="flex min-w-0 flex-1 py-0.5">{text}</span>
      )}
      <CopyButton value={value} label={t('dns.cardCopy', shortValue(value))} copiedLabel={t('dns.cardCopied')}
        variant="ghost" buttonSize="icon-xs" size={12} className={VALUE_COPY} />
    </li>
  )
}

/** "Değer değişti" çağrısı (amber). Örtünün altında kalır (`static`): tıklamak detay penceresini açar. */
function ChangedCallout({ m, values }) {
  const t = useT()
  const prev = splitValues(m.previous_value)
  const list = (arr) => `${arr.slice(0, 2).map((v) => shortValue(v, 28)).join(', ')}${arr.length > 2 ? ` +${arr.length - 2}` : ''}`
  return (
    // role verilmez: 50 kartlık ızgarada 50 canlı bölge (göreli zaman her dakika değişir) ekran okuyucuyu boğardı
    <Alert variant="warning" role={undefined} data-slot="dns-changed"
      className="static mt-2 gap-y-0 px-2.5 py-1.5 text-xs has-[>svg]:gap-x-2 [&>svg]:size-3.5">
      <ArrowLeftRight aria-hidden="true" />
      <AlertTitle className="line-clamp-none font-semibold">
        {t('dns.cardChanged')}
        {m.checked_at && <span className="font-normal"> · {t('dns.cardDetected', relTime(m.checked_at, t))}</span>}
      </AlertTitle>
      <AlertDescription className="text-[11.5px] [overflow-wrap:anywhere]">
        {prev.length > 0 ? (
          <span className="inline-flex flex-wrap items-center gap-x-1.5 font-mono">
            <span className="sr-only">{t('dns.cardWas')}: </span>
            <s className="opacity-75">{list(prev)}</s>
            <ArrowRight aria-hidden="true" className="size-3 shrink-0" />
            <span className="font-semibold">{values.length ? list(values) : '—'}</span>
          </span>
        ) : t('dns.cardCompareHint')}
      </AlertDescription>
    </Alert>
  )
}
