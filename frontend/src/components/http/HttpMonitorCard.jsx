import { Gauge } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import { Badge } from '@/components/shadcn/badge'
import { tagsOf } from '../../utils/monitorFilters.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import MonitorSpark from '../ui/MonitorSpark.jsx'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter, MonitorCardRich,
  MonitorAlarmIcon, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import { CardCompactReason, CardCompactSub, PingCheckedAt, PingTags } from '../ping/PingCardParts.jsx'
import { KeywordProxyChip } from '../keyword/KeywordCardParts.jsx'
import { cn } from '@/lib/utils'
import {
  HttpCompactMetric, HttpMetricTiles, HttpReason, HttpRequestRow, HttpUrlText, httpReasonIcon, httpReasonText,
} from './HttpCardParts.jsx'
import { httpFailureReason, metaRow, proxyMode, statusVerdict } from './httpCardModel.js'

/**
 * HTTP / Web Sitesi izleme KARTI (2026-09-27 yeniden tasarım — shadcn, mobil duyarlı). Paylaşılan MonitorCard ailesinden
 * kurulur (stretched button, sol şerit YOK, duraklatılmış/alarm görünümü MonitorCard'da). Okuma sırası:
 *   durum satırı (seçim · durum · alarm · bakım · bağlantı) → URL (host vurgulu, yol soluk, https gizli, düz http amber,
 *   kopyala) + isteğe bağlı ad → İSTEK SATIRI (yöntem · varsayılandan farklı ayarlar · sıklık) → KAHRAMAN ÖLÇÜLER
 *   (HTTP durumu + yanıt süresi, tonlu) → kapalıysa NEDEN → mini trend + 30 gün erişilebilirlik → takım/grup/vekil yolu ·
 *   zorlanmış vekil kipi · etiketler → son kontrol (göreli, tam zaman ipucunda) + eylemler.
 * Kapalı (alarmsız, etkin) kart TÜM kenarı kırmızı tonla çizilir; alarm varsa MonitorCard'ın dış çizgisi geçerlidir.
 *
 * <p>Sayfaya ait kablolama YUVA olarak gelir (Anahtar Kelime / Ping / Sayfa Hızı kartlarıyla aynı sözleşme): `select`
 * (toplu seçim kutusu — seçim kümesi sayfada), `meta` (MonitorCardMeta), `actions` (MonitorCardActions — yetki kapıları
 * ve işleyiciler sayfada), `status` + `badge` (sayfanın durum sözlüğü; detay penceresiyle ortak). Kart yalnız sunumdan
 * sorumludur. Sayfa `meta`'yı {@link metaRow} ile kurar: kip zorlanmışsa yol rozeti yerine kip çipi görünür.
 *
 * <p><b>Yoğunluk (2026-09-27).</b> `density` = 'rich' (varsayılan — yukarıdaki tam kart) | 'compact': durum satırı ve
 * URL AYNI; altında TEK ikincil satır (ad + yalnız takım rozeti), TEK ana ölçü satırı (HTTP kodu + yanıt süresi),
 * kapalıysa TEK satır neden (tamamı dokun-gör balonunda) ve alt çubuk (zaman + aynı eylemler). İstek satırı (yöntem ·
 * ayar çipleri · sıklık), ölçü kutuları, trend/SLA, grup/vekil/etiket yalnız Zengin'de ({@link MonitorCardRich}).
 *
 * <p>Test kancaları: kök `data-slot="card"` + `data-result` (ok|mismatch|error|pending) + `data-density`; istek satırı,
 * ölçü kutuları ve neden satırı için bkz. HttpCardParts (`http-request`, `http-metric`, `http-reason`, `http-pending`,
 * kompakt `http-compact`), ad `http-name`.
 *
 * @param {object} monitor  GET /monitoring/http satırı (snake_case)
 * @param {object} spark    useSparklines('http')[id] — 24 sa saatlik süre kovaları (trend + süre kutusunun 24 sa tabanı)
 * @param {object} sla      useSla('http').data[id]
 * @param {'rich'|'compact'} density  kart yoğunluğu (sayfa: useCardDensity('http'))
 * @param {boolean} running  kontrol ŞU AN koşuyor (sayfanın isRunning(id)) — hiç sonucu yoksa "İlk kontrol yapılıyor…"
 * @param {Function} onOpen detay penceresini aç (başlık düğmesi — kartın tamamını örter)
 */
export default function HttpMonitorCard({
  monitor: m, status = 'unknown', badge, onOpen, select, meta, actions, spark, sla, slaTarget, slaDays, density = 'rich', running = false, canEdit = false,
}) {
  const t = useT()
  const alarm = !!m.active_alarm
  const paused = !m.active
  const verdict = statusVerdict(m)
  const reason = httpFailureReason(m)
  const compact = density === 'compact'
  const name = String(m.name || '').trim()
  const subName = name && name !== m.url ? name : null
  const hasMeta = !!(m.team_name || m.group_name || metaRow(m).proxy_effective || proxyMode(m) || tagsOf(m).length)
  const alarmLabel = `${t('http.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
  return (
    <MonitorCard density={density} running={running} status={status} alarm={alarm} inactive={paused} data-result={verdict.kind}
      noc={{ type: 'HTTP', monitor: m, rowLabel: m.url, canEdit }}
      className={cn(status === 'down' && !alarm && !paused && 'border-destructive/45 dark:border-destructive/60')}>
      <MonitorCardHeader>
        <MonitorCardTop end={
          <CopyLinkButton iconOnly url={monitorDeepLink('http', m.id)} targetName={m.url} variant="ghost" size="icon-xs" className={CARD_COPY} />
        }>
          {select || null}
          {badge}
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          {/* Açık HTTP_SLOW (2026-10-01, opt-in yavaşlık alarmı): kesinti değil — amber rozet, kırmızı alarm ikonundan ayrı */}
          {m.slow_alarm && (
            <Badge variant="outline" data-slot="http-slow-alarm"
              className="h-5 gap-1 rounded-md border-amber-500/40 bg-amber-500/10 px-1.5 text-[10.5px] font-semibold text-amber-800 dark:text-amber-300">
              <Gauge aria-hidden="true" className="size-3" />{t('http.card.slowAlarm')}
            </Badge>
          )}
          <span className={CARD_LAYER}><MaintenanceBadge target={m.url} /></span>
        </MonitorCardTop>
        {/* URL: host vurgulu, yol soluk, uzunsa kırpılır — tam metin başlığın title'ında ve detay penceresinde.
            Kopyala düğmesi URL'in hemen yanında (köşedeki düğme izlemenin BAĞLANTISINI kopyalar). */}
        <div className="flex min-w-0 items-center gap-1">
          <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.url)} title={m.url}
            className="mb-0 w-auto max-w-full min-w-0 shrink font-mono text-[13.5px] font-medium tracking-normal">
            <HttpUrlText url={m.url} />
          </MonitorCardTitle>
          <CopyButton value={m.url} label={t('a11y.rowAction', m.url, t('keyword.card.copyUrl'))} copiedLabel={t('keyword.card.urlCopied')}
            variant="ghost" buttonSize="icon-xs" size={12} className={CARD_COPY} />
        </div>
        {compact ? <CardCompactSub name={subName} nameSlot="http-name" monitor={m} />
          : subName && <p data-slot="http-name" className="mt-0.5 truncate text-xs text-muted-foreground" title={subName}>{subName}</p>}
      </MonitorCardHeader>
      <MonitorCardContent className={cn(!compact && 'mt-2.5', (compact || !hasMeta) && 'pb-2')}>
        {compact && (
          <>
            <HttpCompactMetric monitor={m} verdict={verdict} reason={reason} />
            {reason && (
              <CardCompactReason slot="http-reason" kind={reason.kind} icon={httpReasonIcon(reason.kind)} text={httpReasonText(reason, t)} rowLabel={m.url} />
            )}
          </>
        )}
        <MonitorCardRich>
          <HttpRequestRow monitor={m} rowLabel={m.url} />
          <HttpMetricTiles monitor={m} verdict={verdict} reason={reason} spark={spark} />
          <HttpReason reason={reason} />
          <div className={CARD_LAYER}><MonitorSpark rowLabel={m.url} spark={spark} sla={sla} slaTarget={slaTarget} slaDays={slaDays} /></div>
          {hasMeta && (
            // Dokunmatikte satır arası 10 px: sarınca alt satırdaki vekil çipinin 40 px'lik tetiği üstteki takım rozetinin
            // dokunma alanını kesiyordu (Playwright 390 isabet testi).
            <div className={cn(CARD_LAYER, 'mt-2 mb-2.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5 pointer-coarse:gap-y-2.5')}>
              {meta}
              <KeywordProxyChip monitor={m} rowLabel={m.url} />
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
