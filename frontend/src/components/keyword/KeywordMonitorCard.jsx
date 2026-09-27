import { useT } from '../../i18n/index.jsx'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import { tagsOf } from '../../utils/monitorFilters.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import NocBadge from '../noc/forms/NocBadge.jsx'
import MonitorSpark from '../ui/MonitorSpark.jsx'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter, MonitorCardRich,
  MonitorAlarmIcon, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import { CardCompactReason, CardCompactSub, PingCheckedAt, PingTags } from '../ping/PingCardParts.jsx'
import { cn } from '@/lib/utils'
import {
  KeywordCompactResult, KeywordMetricTiles, KeywordProxyChip, KeywordRulePanel, KeywordUrlText, keywordReasonIcon, keywordReasonText,
} from './KeywordCardParts.jsx'
import { failureReason, metaRow, proxyMode, verdictOf } from './keywordCardModel.js'

/**
 * Anahtar Kelime izleme KARTI (2026-09-27 yeniden tasarım — shadcn, mobil duyarlı). Paylaşılan MonitorCard ailesinden
 * kurulur (stretched button, sol şerit YOK, duraklatılmış/alarm görünümü MonitorCard'da). Okuma sırası:
 *   durum satırı (seçim · durum · alarm · bakım · bağlantı) → URL (host vurgulu, yol soluk, https gizli, kopyala) +
 *   isteğe bağlı ad → KURAL PANELİ (kural rozetleri · aranan metin · son sonuç + eşleşme sayısı · ilk eşleşmenin vurgulu
 *   çevresi ya da kapalıysa NEDEN) → HTTP durumu + yanıt süresi kutuları → mini trend + 30 gün erişilebilirlik →
 *   takım/grup/vekil yolu · zorlanmış vekil kipi · etiketler → son kontrol (göreli, tam zaman ipucunda) + eylemler.
 * Kapalı (alarmsız, etkin) kart TÜM kenarı kırmızı tonla çizilir; alarm varsa MonitorCard'ın dış çizgisi geçerlidir.
 *
 * <p><b>Yoğunluk (2026-09-27).</b> `density` = 'rich' (varsayılan — yukarıdaki tam kart) | 'compact': durum satırı ve
 * URL AYNI; altında TEK ikincil satır (ad + yalnız takım rozeti), TEK sonuç satırı (Bulundu/Bulunamadı … · aranan
 * metin · yanıt süresi), kapalıysa TEK satır neden (tamamı dokun-gör balonunda) ve alt çubuk (zaman + aynı eylemler).
 * Kural paneli (kural rozetleri, eşleşme sayısı, ilk eşleşme), HTTP/süre kutuları, trend/SLA, grup/vekil/etiket yalnız
 * Zengin'de ({@link MonitorCardRich} — Kompakt'ta DOM'a girmez).
 *
 * <p>Sayfaya ait kablolama YUVA olarak gelir (Ping / Sayfa Hızı kartlarıyla aynı sözleşme): `select` (toplu seçim
 * kutusu — seçim kümesi sayfada), `meta` (MonitorCardMeta), `actions` (MonitorCardActions — yetki kapıları ve
 * işleyiciler sayfada), `status` + `badge` (sayfanın durum sözlüğü; detay penceresiyle ortak). Kart yalnız sunumdan
 * sorumludur. Sayfa `meta`'yı {@link metaRow} ile kurar: kip zorlanmışsa yol rozeti yerine kip çipi görünür.
 *
 * @param {object} monitor  GET /monitoring/keyword satırı (snake_case)
 * @param {object} spark    useSparklines('keyword')[id] — 24 sa saatlik süre kovaları
 * @param {object} sla      useSla('keyword').data[id]
 * @param {'rich'|'compact'} density  kart yoğunluğu (sayfa: useCardDensity('keyword'))
 * @param {Function} onOpen detay penceresini aç (başlık düğmesi — kartın tamamını örter)
 */
export default function KeywordMonitorCard({
  monitor: m, status = 'unknown', badge, onOpen, select, meta, actions, spark, sla, slaTarget, slaDays, density = 'rich',
}) {
  const t = useT()
  const alarm = !!m.active_alarm
  const paused = !m.active
  const verdict = verdictOf(m)
  const reason = failureReason(m)
  const compact = density === 'compact'
  const name = String(m.name || '').trim()
  const subName = name && name !== m.url ? name : null
  const hasMeta = !!(m.team_name || m.group_name || metaRow(m).proxy_effective || proxyMode(m) || tagsOf(m).length || m.noc_notify)
  const alarmLabel = `${t('keyword.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
  return (
    <MonitorCard density={density} status={status} alarm={alarm} inactive={paused} data-result={verdict.kind}
      className={cn(status === 'down' && !alarm && !paused && 'border-destructive/45 dark:border-destructive/60')}>
      <MonitorCardHeader>
        <MonitorCardTop end={
          <CopyLinkButton iconOnly url={monitorDeepLink('keyword', m.id)} targetName={m.url} variant="ghost" size="icon-xs" className={CARD_COPY} />
        }>
          {select || null}
          {badge}
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          <span className={CARD_LAYER}><MaintenanceBadge target={m.url} /></span>
        </MonitorCardTop>
        {/* URL: host vurgulu, yol soluk, uzunsa kırpılır — tam metin başlığın title'ında ve detay penceresinde.
            Kopyala düğmesi URL'in hemen yanında (köşedeki düğme izlemenin BAĞLANTISINI kopyalar). */}
        <div className="flex min-w-0 items-center gap-1">
          <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.url)} title={m.url}
            className="mb-0 w-auto max-w-full min-w-0 shrink font-mono text-[13.5px] font-medium tracking-normal">
            <KeywordUrlText url={m.url} />
          </MonitorCardTitle>
          <CopyButton value={m.url} label={t('a11y.rowAction', m.url, t('keyword.card.copyUrl'))} copiedLabel={t('keyword.card.urlCopied')}
            variant="ghost" buttonSize="icon-xs" size={12} className={CARD_COPY} />
        </div>
        {compact ? <CardCompactSub name={subName} nameSlot="keyword-name" monitor={m} />
          : subName && <p data-slot="keyword-name" className="mt-0.5 truncate text-xs text-muted-foreground" title={subName}>{subName}</p>}
      </MonitorCardHeader>
      <MonitorCardContent className={cn(!compact && 'mt-2.5', (compact || !hasMeta) && 'pb-2')}>
        {compact && (
          <>
            <KeywordCompactResult monitor={m} verdict={verdict} reason={reason} />
            {reason && (
              <CardCompactReason slot="keyword-reason" kind={reason.kind} icon={keywordReasonIcon(reason.kind)} text={keywordReasonText(reason, t)} rowLabel={m.url} />
            )}
          </>
        )}
        <MonitorCardRich>
          <KeywordRulePanel monitor={m} verdict={verdict} reason={reason} rowLabel={m.url} />
          <KeywordMetricTiles monitor={m} verdict={verdict} reason={reason} />
          <div className={CARD_LAYER}><MonitorSpark rowLabel={m.url} spark={spark} sla={sla} slaTarget={slaTarget} slaDays={slaDays} /></div>
          {hasMeta && (
            // Dokunmatikte satır arası 10 px: sarınca alt satırdaki vekil çipinin 40 px'lik tetiği üstteki takım rozetinin
            // dokunma alanını kesiyordu (Playwright 390 isabet testi).
            <div className={cn(CARD_LAYER, 'mt-2 mb-2.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5 pointer-coarse:gap-y-2.5')}>
              {meta}
              {m.noc_notify && <NocBadge rowLabel={m.url} />}
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
