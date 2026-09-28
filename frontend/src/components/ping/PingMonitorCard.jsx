import { useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import { tagsOf } from '../../utils/monitorFilters.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import MonitorSpark from '../ui/MonitorSpark.jsx'
import MonitorCardMeta from '../MonitorCardMeta.jsx'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter, MonitorCardRich,
  MonitorStatusBadge, MonitorAlarmIcon, MonitorCardPending, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import { cn } from '@/lib/utils'
import PingProtocolChips from './PingProtocolChips.jsx'
import {
  CardCompactReason, CardCompactSub, PingCheckedAt, PingCompactMetric, PingFailureReason, PingLatencyRange, PingMetricTiles,
  PingTags, pingReasonIcon, pingReasonText,
} from './PingCardParts.jsx'
import { failureReason, latencyBaseline, pingStatusKey, pingStatusLabelKey } from './pingCardModel.js'

/**
 * Ping İzleme KARTI (2026-09-27 yeniden tasarım) — paylaşılan MonitorCard ailesinden kurulur (stretched button, sol şerit
 * YOK, duraklatılmış/alarm görünümü MonitorCard'da). Okuma sırası:
 *   durum satırı (seçim · durum · alarm · bakım · bağlantı) → host (eş aralıklı yazı, kopyala) + isteğe bağlı ad →
 *   yapılandırma çipleri (ICMP · IPv4/IPv6 · paket · sıklık) → ANAHTAR ÖLÇÜLER (RTT + paket kaybı, tonlu) →
 *   kapalıysa NEDEN satırı → 24 sa gecikme özeti + mini trend + 30 gün erişilebilirlik → takım/grup/etiket →
 *   son kontrol (göreli) + eylemler.
 * Kapalı (alarmsız, etkin) kart TÜM kenarı kırmızı tonla çizilir; alarm varsa MonitorCard'ın dış çizgisi geçerlidir.
 *
 * <p><b>Yoğunluk (2026-09-27).</b> `density` = 'rich' (varsayılan — yukarıdaki tam kart) | 'compact': durum satırı ve
 * host AYNI; altında TEK ikincil satır (ad + yalnız takım rozeti), TEK ana ölçü (RTT, yavaşsa rozet, kısmi kayıpta ek),
 * kapalıysa TEK satır neden (tamamı dokun-gör balonunda) ve alt çubuk (zaman + aynı eylemler). Çipler, ölçü kutuları,
 * 24 sa özeti, trend/SLA, grup/etiket yalnız Zengin'de ({@link MonitorCardRich} — Kompakt'ta DOM'a girmez).
 *
 * <p>Sayfaya ait kablolama YUVA olarak gelir: `select` (toplu seçim kutusu — seçim kümesi sayfada) ve `actions`
 * (MonitorCardActions — yetki kapıları ve işleyiciler sayfada). Kart yalnız sunumdan sorumludur.
 *
 * @param {object} monitor  GET /monitoring/ping satırı (snake_case)
 * @param {object} spark    useSparklines('ping')[id] — 24 sa saatlik gecikme kovaları
 * @param {object} sla      useSla('ping').data[id]
 * @param {'rich'|'compact'} density  kart yoğunluğu (sayfa: useCardDensity('ping'))
 * @param {boolean} running  kontrol ŞU AN koşuyor (sayfanın isRunning(id)) — hiç sonucu yoksa "İlk kontrol yapılıyor…"
 * @param {Function} onOpen detay penceresini aç (başlık düğmesi — kartın tamamını örter)
 */
export default function PingMonitorCard({ monitor: m, spark, sla, slaTarget, slaDays, density = 'rich', running = false, canEdit = false, onOpen, select, actions }) {
  const t = useT()
  const status = pingStatusKey(m)
  const alarm = !!m.active_alarm
  const paused = m.active === false
  const compact = density === 'compact'
  const baseline = useMemo(() => latencyBaseline(spark), [spark])
  const reason = failureReason(m)
  // Hiç kontrol yok: ölçü kutuları/kompakt ölçü çizilmez (boş "—" kutuları) → yerine bekleme satırı; ilk kontrol
  // koşarken "İlk kontrol yapılıyor…" (kart boş alan bırakmaz — 2026-09-28).
  const never = !m.checked_at
  const name = String(m.name || '').trim()
  const subName = name && name !== m.host ? name : null
  const hasMeta = !!(m.team_name || m.group_name || m.proxy_effective || tagsOf(m).length)
  const alarmLabel = `${t('ping.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
  return (
    <MonitorCard density={density} running={running} status={status} alarm={alarm} inactive={paused}
      noc={{ type: 'PING', monitor: m, rowLabel: m.host, canEdit }}
      className={cn(status === 'down' && !alarm && !paused && 'border-destructive/45 dark:border-destructive/60')}>
      <MonitorCardHeader>
        <MonitorCardTop end={
          <CopyLinkButton iconOnly url={monitorDeepLink('ping', m.id)} targetName={m.host} variant="ghost" size="icon-xs" className={CARD_COPY} />
        }>
          {select || null}
          <MonitorStatusBadge status={status}>{t(pingStatusLabelKey(m))}</MonitorStatusBadge>
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          <span className={CARD_LAYER}><MaintenanceBadge target={m.host} /></span>
        </MonitorCardTop>
        {/* Host: eş aralıklı (IP / IPv6 okunur), uzunsa kırpılır — tam metin başlığın title'ında ve detay penceresinde.
            Kopyala düğmesi host'un hemen yanında (terminale yapıştırmak için; köşedeki düğme BAĞLANTI kopyalar). */}
        <div className="flex min-w-0 items-center gap-1">
          <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.host)} title={m.host}
            className="mb-0 w-auto max-w-full min-w-0 shrink font-mono text-[14.5px] tracking-normal">{m.host}</MonitorCardTitle>
          <CopyButton value={m.host} label={t('a11y.rowAction', m.host, t('ping.card.copyHost'))} copiedLabel={t('ping.card.hostCopied')}
            variant="ghost" buttonSize="icon-xs" size={12} className={CARD_COPY} />
        </div>
        {compact ? <CardCompactSub name={subName} nameSlot="ping-name" monitor={m} />
          : subName && <p data-slot="ping-name" className="mt-0.5 truncate text-xs text-muted-foreground">{subName}</p>}
        <MonitorCardRich><PingProtocolChips monitor={m} className="mt-1.5 mb-2.5" /></MonitorCardRich>
      </MonitorCardHeader>
      <MonitorCardContent className={cn((compact || !hasMeta) && 'pb-2')}>
        {compact && (
          <>
            <PingCompactMetric monitor={m} baseline={baseline} />
            {never && <MonitorCardPending slot="ping-pending" compact />}
            {reason && (
              <CardCompactReason slot="ping-reason" kind={reason.kind} icon={pingReasonIcon(reason.kind)} text={pingReasonText(reason, t)}
                rowLabel={m.host} tone={reason.kind === 'na' ? 'warn' : 'bad'} />
            )}
          </>
        )}
        <MonitorCardRich>
          {never && <MonitorCardPending slot="ping-pending" />}
          <PingMetricTiles monitor={m} baseline={baseline} />
          <PingFailureReason reason={reason} />
          <PingLatencyRange baseline={baseline} />
          <div className={CARD_LAYER}><MonitorSpark rowLabel={m.host} spark={spark} sla={sla} slaTarget={slaTarget} slaDays={slaDays} /></div>
          {hasMeta && (
            <div className={cn(CARD_LAYER, 'mt-2 mb-2.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5')}>
              <MonitorCardMeta monitor={m} />
              <PingTags monitor={m} />
            </div>
          )}
        </MonitorCardRich>
      </MonitorCardContent>
      {/* mt-auto: ızgara satırındaki kartlar aynı boya uzar — alt çubuk kısa kartta da en altta hizalı kalır. */}
      <MonitorCardFooter actions={actions || null} className="mt-auto">
        <PingCheckedAt at={m.checked_at} />
      </MonitorCardFooter>
    </MonitorCard>
  )
}
