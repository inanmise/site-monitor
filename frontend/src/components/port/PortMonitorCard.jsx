import { useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import NocBadge from '../noc/forms/NocBadge.jsx'
import MonitorSpark from '../ui/MonitorSpark.jsx'
import MonitorCardMeta from '../MonitorCardMeta.jsx'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter,
  MonitorAlarmIcon, MonitorCardRich, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import { PingCheckedAt, PingTags } from '../ping/PingCardParts.jsx'
import { latencyBaseline } from '../ping/pingCardModel.js'
import { cn } from '@/lib/utils'
import {
  PortCompactEndpoint, PortCompactSummary, PortEndpointChips, PortMetricTiles, PortResultPanel, PortSourceBadge,
} from './PortCardParts.jsx'
import { endpointText, portResult, sourceOf } from './portCardModel.js'

/**
 * Port izleme KARTI (2026-09-27 yeniden tasarım — shadcn, mobil duyarlı). Paylaşılan MonitorCard ailesinden kurulur
 * (stretched button, sol şerit YOK, duraklatılmış/alarm görünümü MonitorCard'da). Okuma sırası:
 *   durum satırı (seçim · Açık/Kapalı · alarm · bakım · bağlantı) → UÇ NOKTA: host (eş aralıklı; IPv6 / uzun ad kırpılır,
 *   tamamı başlığın title'ında ve detayda) + host:port kopyala + isteğe bağlı ad → `:port` · kontrol türü · (HTTP yolu) ·
 *   "usually PostgreSQL" · IP ailesi · sıklık → SONUÇ PANELİ (durum + tek satırlık neden) → bağlantı süresi + 24 sa
 *   ortalaması → mini trend + 30 gün erişilebilirlik → kaynak (Bağımsız / Envanterden) · takım/grup/vekil yolu ·
 *   etiketler → son kontrol (göreli, tam zaman ipucunda) + eylemler.
 * Kapalı (alarmsız, etkin) kart TÜM kenarı kırmızı tonla çizilir; alarm varsa MonitorCard'ın dış çizgisi geçerlidir.
 *
 * <p>Sayfaya ait kablolama YUVA olarak gelir (Ping / DNS / Anahtar Kelime kartlarıyla aynı sözleşme): `select` (toplu
 * seçim kutusu — seçim kümesi sayfada), `meta` (MonitorCardMeta), `actions` (MonitorCardActions — yetki kapıları,
 * işleyiciler ve türev satırın "izlemeyi durdur" adı sayfada), `status` + `badge` + `alarmLabel` (sayfanın durum sözlüğü;
 * detay penceresiyle ortak). Kart yalnız sunumdan sorumludur. Kontrol pod'dan doğrudan yapılır; vekil yalnız izleme
 * açıkça seçtiyse (`proxy_effective` → meta'daki yol rozeti).
 *
 * <p><b>Yoğunluk (2026-09-27, Kompakt / Zengin):</b> `density="compact"` kartı taranabilir özete indirir — durum satırı,
 * host + TEK ikincil satır (`:port` · tür · ad — {@link PortCompactEndpoint}), bağlantı HÜKMÜ + süre
 * ({@link PortCompactSummary}; kötüyse tek satır neden, tamamı dokun-gör), kaynak rozeti + YALNIZ takım rozeti, alt çubuk.
 * Sonuç paneli, süre kutuları ve trend {@link MonitorCardRich} içinde (Kompakt'ta DOM'a girmez); yol / hizmet / aile /
 * sıklık çipleri, grup/vekil ve etiketler de yalnız Zengin'de. Zengin = bugünkü tam kart, sıra ve içerik değişmedi.
 *
 * @param {object} monitor  GET /monitoring/port satırı (snake_case)
 * @param {object} spark    useSparklines('port')[id] — 24 sa saatlik süre kovaları
 * @param {object} sla      useSla('port').data[id]
 * @param {boolean} running  kontrol ŞU AN koşuyor (sayfanın isRunning(id)) — hiç sonucu yoksa "İlk kontrol yapılıyor…"
 * @param {Function} onOpen detay penceresini aç (başlık düğmesi — kartın tamamını örter)
 */
export default function PortMonitorCard({
  monitor: m, status = 'unknown', badge, alarmLabel, onOpen, select, meta, actions, spark, sla, slaTarget, slaDays, density = 'rich', running = false,
}) {
  const t = useT()
  const compact = density === 'compact'
  const alarm = !!m.active_alarm
  const paused = m.active === false
  const result = portResult(m)
  const baseline = useMemo(() => latencyBaseline(spark), [spark])
  const rowLabel = `${m.host}:${m.port}`
  const endpoint = endpointText(m.host, m.port)
  const name = String(m.name || '').trim()
  return (
    <MonitorCard density={density} running={running} status={status} alarm={alarm} inactive={paused} data-result={result.kind} data-protocol={result.proto}
      className={cn(status === 'down' && !alarm && !paused && 'border-destructive/45 dark:border-destructive/60')}>
      <MonitorCardHeader>
        <MonitorCardTop className={compact ? 'mb-2' : undefined} end={
          <CopyLinkButton iconOnly url={monitorDeepLink('port', m.id)} targetName={rowLabel} variant="ghost" size="icon-xs" className={CARD_COPY} />
        }>
          {select || null}
          {badge}
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          <span className={CARD_LAYER}><MaintenanceBadge target={m.host} /></span>
        </MonitorCardTop>
        {/* Host: eş aralıklı (IP / IPv6 okunur), uzunsa kırpılır. Kopyala düğmesi host:port'u kopyalar (IPv6 köşeli
            parantezle — terminale/istemciye aynen yapıştırılır); köşedeki düğme izlemenin BAĞLANTISINI kopyalar. */}
        <div className="flex min-w-0 items-center gap-1">
          <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', rowLabel)} title={endpoint}
            className="mb-0 w-auto max-w-full min-w-0 shrink font-mono text-[14.5px] tracking-normal">{m.host}</MonitorCardTitle>
          <CopyButton value={endpoint} label={t('a11y.rowAction', rowLabel, t('port.card.copyAddress'))} copiedLabel={t('port.card.addressCopied')}
            variant="ghost" buttonSize="icon-xs" size={12} className={CARD_COPY} />
        </div>
        {compact ? (
          // Kompakt: TEK ikincil satır — :port · tür · ad (sarmaz, ad kırpılır)
          <PortCompactEndpoint monitor={m} name={name && name !== m.host ? name : null} className="mt-1 mb-2" />
        ) : (
          <>
            {name && name !== m.host && <p data-slot="port-name" className="mt-0.5 truncate text-xs text-muted-foreground" title={name}>{name}</p>}
            <PortEndpointChips monitor={m} rowLabel={rowLabel} className="mt-1.5 mb-2.5" />
          </>
        )}
      </MonitorCardHeader>
      <MonitorCardContent>
        {compact && <PortCompactSummary monitor={m} result={result} baseline={baseline} />}
        <MonitorCardRich>
          <PortResultPanel monitor={m} result={result} />
          <PortMetricTiles monitor={m} result={result} baseline={baseline} />
          <div className={CARD_LAYER}><MonitorSpark rowLabel={rowLabel} spark={spark} sla={sla} slaTarget={slaTarget} slaDays={slaDays} /></div>
        </MonitorCardRich>
        {/* Kaynak rozeti İKİ görünümde de (Port iki kaynaklı: envanter türevi / bağımsız). Kompakt'ta yanında YALNIZ takım
            rozeti — grup/vekil ve etiketler Zengin'e kalır. */}
        <div className={cn(CARD_LAYER, 'flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5', compact ? 'mb-2' : 'mt-2 mb-2.5')}>
          <PortSourceBadge source={sourceOf(m)} rowLabel={rowLabel} />
          {compact ? (m.team_name && <MonitorCardMeta monitor={{ team_id: m.team_id, team_name: m.team_name }} />) : <>{meta}<PingTags monitor={m} /></>}
          {/* 7/24 rozeti yalnız Zengin'de (satır Kompakt ile ortak → MonitorCardRich, display:contents) */}
          {m.noc_notify && <MonitorCardRich className="contents"><NocBadge rowLabel={rowLabel} /></MonitorCardRich>}
        </div>
      </MonitorCardContent>
      {/* mt-auto: ızgara satırındaki kartlar aynı boya uzar — alt çubuk kısa kartta da en altta hizalı kalır. */}
      <MonitorCardFooter actions={actions || null} className="mt-auto">
        <PingCheckedAt at={m.checked_at} />
      </MonitorCardFooter>
    </MonitorCard>
  )
}
