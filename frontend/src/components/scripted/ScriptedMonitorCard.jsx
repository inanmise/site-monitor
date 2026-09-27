import { useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import { tagsOf } from '../../utils/monitorFilters.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import NocBadge from '../noc/forms/NocBadge.jsx'
import MonitorSpark from '../ui/MonitorSpark.jsx'
import MonitorCardMeta from '../MonitorCardMeta.jsx'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter,
  MonitorCardRich, MonitorAlarmIcon, MonitorCardTag, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import { cn } from '@/lib/utils'
import {
  ScenarioChips, ScenarioTarget, ScriptedCompactRun, ScriptedLastRun, ScriptedMetricTiles, ScriptedRunResult, ScriptedTags,
} from './ScriptedCardParts.jsx'
import { isBrowserScript, scenarioTarget } from './scriptedCardModel.js'

/**
 * Sentetik İzleme KARTI (2026-09-27 yeniden tasarım — shadcn, mobil duyarlı). Paylaşılan MonitorCard ailesinden kurulur
 * (stretched button, sol şerit YOK, duraklatılmış/alarm görünümü MonitorCard'da). Okuma sırası:
 *   durum satırı (seçim · durum · alarm · bakım · koşucu etiketi · bağlantı) → senaryo adı (en fazla iki satır) + HEDEF
 *   (soluk host, kopyala) → künye çipleri (sistem kapattı / hiç başarılı olmadı uyarıları · sürüm · sıklık) →
 *   SON KOŞU paneli (doğrulama sayaçları + oran çubuğu, çıkış etiketi, neden satırı) → süre + ortalama istek kutuları →
 *   mini trend + 30 gün erişilebilirlik → takım/grup/etiket → son koşu (göreli) + eylemler.
 * Başarısız (alarmsız, etkin) kart TÜM kenarı kırmızı tonla çizilir; alarm varsa MonitorCard'ın dış çizgisi geçerlidir.
 *
 * <p>Sayfaya ait kablolama YUVA olarak gelir (Ping kartıyla aynı sözleşme): `select` (toplu seçim kutusu — seçim kümesi
 * sayfada), `actions` (MonitorCardActions — yetki kapıları ve işleyiciler sayfada), `status` + `badge` (sayfanın durum
 * sözlüğü: PASS→up, FAIL/ERROR/TIMEOUT→down, NO_CHECKS→warn; detay penceresiyle aynı kaynak). Kart yalnız sunumdan sorumludur.
 *
 * @param {object} monitor  GET /monitoring/scripted satırı (snake_case)
 * @param {object} spark    useSparklines('scripted')[id] — 24 sa saatlik süre kovaları
 * @param {object} sla      useSla('scripted').data[id]
 * @param {Function} onOpen detay penceresini aç (başlık düğmesi — kartın tamamını örter)
 * @param {'compact'|'rich'} density kart yoğunluğu (2026-09-27, sayfanın `useCardDensity('scripted')`'i). Zengin (varsayılan)
 *   = yukarıdaki tam kart. Kompakt = durum satırı (koşucu etiketi yok) + TEK satır ad + hedef + yalnız UYARI rozetleri
 *   (sistem kapattı / hiç başarılı olmadı) + tek satırlık son koşu özeti (doğrulama sayacı · süre) + gerekiyorsa tek satır
 *   neden + yalnız takım rozeti + alt çubuk. Sonuç paneli, ölçü kutuları, mini trend/SLA, künye çipleri (sürüm, sıklık),
 *   grup ve etiketler yalnız Zengin'de (MonitorCardRich — Kompakt'ta DOM'a girmez).
 */
export default function ScriptedMonitorCard({
  monitor: m, status = 'unknown', badge, onOpen, select, actions, spark, sla, slaTarget, slaDays, density = 'rich',
}) {
  const t = useT()
  const target = useMemo(() => scenarioTarget(m), [m])
  const browser = useMemo(() => isBrowserScript(m.script), [m.script])
  const alarm = !!m.active_alarm
  const paused = m.active === false
  const compact = density === 'compact'
  const hasMeta = !!(m.team_name || m.group_name || tagsOf(m).length || m.noc_notify)
  const alarmLabel = `${t('scripted.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
  return (
    <MonitorCard status={status} density={density} alarm={alarm} inactive={paused}
      className={cn(status === 'down' && !alarm && !paused && 'border-destructive/45 dark:border-destructive/60', compact && 'pt-3 sm:pt-3.5')}>
      <MonitorCardHeader>
        <MonitorCardTop className={compact ? 'mb-2' : undefined} end={<>
          {!compact && <MonitorCardTag data-runner={browser ? 'browser' : 'k6'}>{browser ? `k6 · ${t('scripted.card.browser')}` : 'k6'}</MonitorCardTag>}
          <CopyLinkButton iconOnly url={monitorDeepLink('scripted', m.id)} targetName={m.name} variant="ghost" size="icon-xs" className={CARD_COPY} />
        </>}>
          {select || null}
          {badge}
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          <span className={CARD_LAYER}><MaintenanceBadge target={m.name} /></span>
        </MonitorCardTop>
        {/* Senaryo adları uzun olur ("Ödeme akışı — sepete ekle, adres, …"): Zengin'de tek satırda kırpmak yerine EN FAZLA
            İKİ satır; Kompakt'ta tek satır (ızgara ritmi). Tam ad başlığın title'ında ve detay penceresinin başlığında. */}
        <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.name)} title={m.name}
          className={compact ? 'mb-0' : 'mb-0 [&>span]:line-clamp-2 [&>span]:break-words [&>span]:whitespace-normal'}>{m.name}</MonitorCardTitle>
        <ScenarioTarget target={target} rowLabel={m.name} />
        {compact
          ? <ScenarioChips monitor={m} warningsOnly className="mt-2" />
          : <MonitorCardRich><ScenarioChips monitor={m} className="mt-2 mb-2.5" /></MonitorCardRich>}
      </MonitorCardHeader>
      {/* Kompakt: dokunmatikte hedef satırı 40 px kopyala düğmesiyle zaten uzun → üst boşluk daralır. */}
      <MonitorCardContent className={cn(compact ? 'pt-2.5 pointer-coarse:pt-1' : !hasMeta && 'pb-2')}>
        {compact && <ScriptedCompactRun monitor={m} status={status} />}
        {compact && m.team_name && (
          <div className={cn(CARD_LAYER, 'mb-2')}>
            <MonitorCardMeta monitor={{ team_id: m.team_id, team_name: m.team_name }} />
          </div>
        )}
        <MonitorCardRich>
          <ScriptedRunResult monitor={m} status={status} />
          <ScriptedMetricTiles monitor={m} />
          <div className={CARD_LAYER}><MonitorSpark rowLabel={m.name} spark={spark} sla={sla} slaTarget={slaTarget} slaDays={slaDays} /></div>
          {hasMeta && (
            <div className={cn(CARD_LAYER, 'mt-2 mb-2.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5')}>
              <MonitorCardMeta monitor={m} />
              {m.noc_notify && <NocBadge rowLabel={m.name} />}
              <ScriptedTags monitor={m} />
            </div>
          )}
        </MonitorCardRich>
      </MonitorCardContent>
      {/* mt-auto: ızgara satırındaki kartlar aynı boya uzar — alt çubuk kısa kartta da en altta hizalı kalır. */}
      <MonitorCardFooter actions={actions || null} className="mt-auto">
        <ScriptedLastRun at={m.checked_at} />
      </MonitorCardFooter>
    </MonitorCard>
  )
}
