import { useT } from '../../i18n/index.jsx'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import { tagsOf } from '../../utils/monitorFilters.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import MonitorSpark from '../ui/MonitorSpark.jsx'
import MonitorCardMeta from '../MonitorCardMeta.jsx'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter,
  MonitorAlarmIcon, MonitorCardRich, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import { cn } from '@/lib/utils'
import {
  PageCheckedAt, PageCompactSummary, PageIntegrityResult, PageMetricTiles, PageScopeChips, PageTags, PageUrlText,
} from './PageCardParts.jsx'

/**
 * Sayfa Bütünlüğü izleme KARTI (2026-09-27 yeniden tasarım — shadcn, mobil duyarlı). Paylaşılan MonitorCard ailesinden
 * kurulur (stretched button, sol şerit YOK, duraklatılmış/alarm görünümü MonitorCard'da). Okuma sırası:
 *   durum satırı (seçim · durum · alarm · bakım · bağlantı) → URL (host vurgulu, yol soluk, kopyala) + isteğe bağlı ad →
 *   NE DENETLENİYOR çipleri (kip · derinlik/sayfa · sıklık · üçüncü taraf · hariç tutma) → BÜTÜNLÜK SONUCU paneli
 *   (sorunsuz kaynak oranı + çubuk + kırık/zaman aşımı/mixed çipleri; sayfa yüklenemediyse neden) → HTTP durumu + tarama
 *   süresi kutuları → mini trend + 30 gün erişilebilirlik → takım/grup/vekil/etiket → son kontrol (göreli) + eylemler.
 * Kesinti (sayfa yüklenemedi; alarmsız, etkin) kart TÜM kenarı kırmızı tonla çizilir; alarm varsa MonitorCard'ın dış
 * çizgisi geçerlidir. Sorunların listesi detay penceresinin (başlık → kartın tamamı) varsayılan "Sorunlar" sekmesinde.
 *
 * <p>Sayfaya ait kablolama YUVA olarak gelir (Ping kartıyla aynı sözleşme): `select` (toplu seçim kutusu), `actions`
 * (MonitorCardActions — yetki kapıları ve işleyiciler sayfada), `status` + `badge` (sayfanın durum sözlüğü: OK→up,
 * DEGRADED→warn, DOWN→down, CONFIG_ERROR/bilinmiyor→unknown; detay penceresiyle aynı kaynak).
 *
 * <p><b>Yoğunluk (2026-09-27, Kompakt / Zengin):</b> `density="compact"` kartı taranabilir özete indirir — durum satırı,
 * URL + (varsa) ad, bütünlük HÜKMÜ tek satırda ({@link PageCompactSummary}: sorunsuz oran + en ağır sorun çipi; sayfa
 * yüklenemediyse neden tek satır, tamamı dokun-gör), YALNIZ takım rozeti, alt çubuk. Kapsam çipleri, sonuç paneli (oran
 * çubuğu + tüm çipler), HTTP/süre kutuları, trend, grup/vekil ve etiketler {@link MonitorCardRich} içinde ya da yalnız
 * Zengin'de (Kompakt'ta DOM'a girmez). Zengin = bugünkü tam kart, sıra ve içerik değişmedi.
 *
 * @param {object} monitor  GET /monitoring/page satırı (snake_case)
 * @param {object} spark    useSparklines('page')[id] — 24 sa saatlik süre kovaları
 * @param {object} sla      useSla('page').data[id]
 * @param {boolean} running  kontrol ŞU AN koşuyor (sayfanın isRunning(id)) — hiç sonucu yoksa "İlk kontrol yapılıyor…"
 * @param {Function} onOpen detay penceresini aç (başlık düğmesi — kartın tamamını örter)
 */
export default function PageMonitorCard({
  monitor: m, status = 'unknown', badge, onOpen, select, actions, spark, sla, slaTarget, slaDays, density = 'rich', running = false, canEdit = false,
}) {
  const t = useT()
  const compact = density === 'compact'
  const alarm = !!m.active_alarm
  // Duraklatılmış = `!active` — sayfanın eski kart yüklemi ve detaydaki Sürdür kapısıyla (`!selected.active`) AYNI.
  const paused = !m.active
  const name = String(m.name || '').trim()
  // Kompakt'ta meta satırı YALNIZ takım rozetidir (grup / vekil / etiketler Zengin'de).
  const hasMeta = compact ? !!m.team_name : !!(m.team_name || m.group_name || m.proxy_effective || tagsOf(m).length)
  const alarmLabel = `${t('page.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
  return (
    <MonitorCard density={density} running={running} status={status} alarm={alarm} inactive={paused}
      noc={{ type: 'PAGE', monitor: m, rowLabel: m.url, canEdit }}
      className={cn(status === 'down' && !alarm && !paused && 'border-destructive/45 dark:border-destructive/60')}>
      {/* Kompakt'ta kapsam çipleri yok → başlık ile özet arasındaki boşluğu başlık bölgesi verir */}
      <MonitorCardHeader className={compact ? 'mb-2' : undefined}>
        <MonitorCardTop className={compact ? 'mb-2' : undefined} end={
          <CopyLinkButton iconOnly url={monitorDeepLink('page', m.id)} targetName={m.url} variant="ghost" size="icon-xs" className={CARD_COPY} />
        }>
          {select || null}
          {badge}
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          <span className={CARD_LAYER}><MaintenanceBadge target={m.url} /></span>
        </MonitorCardTop>
        {/* URL: host vurgulu, yol + sorgu soluk; uzunsa kırpılır — tam adres başlığın title'ında ve detay penceresinde.
            Kopyala düğmesi URL'in hemen yanında (köşedeki düğme izlemenin BAĞLANTISINI kopyalar). */}
        <div className="flex min-w-0 items-center gap-1">
          <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.url)} title={m.url}
            className="mb-0 w-auto max-w-full min-w-0 shrink font-mono text-[13.5px] font-medium tracking-normal">
            <PageUrlText url={m.url} />
          </MonitorCardTitle>
          <CopyButton value={m.url} label={t('a11y.rowAction', m.url, t('page.card.copyUrl'))} copiedLabel={t('page.card.urlCopied')}
            variant="ghost" buttonSize="icon-xs" size={12} className={CARD_COPY} />
        </div>
        {name && name !== m.url && <p data-slot="page-name" className="mt-0.5 truncate text-xs text-muted-foreground">{name}</p>}
        <MonitorCardRich>
          <PageScopeChips monitor={m} className="mt-2 mb-2.5" />
        </MonitorCardRich>
      </MonitorCardHeader>
      <MonitorCardContent className={cn(!hasMeta && 'pb-2')}>
        {compact && <PageCompactSummary monitor={m} />}
        <MonitorCardRich>
          <PageIntegrityResult monitor={m} />
          <PageMetricTiles monitor={m} />
          <div className={CARD_LAYER}><MonitorSpark rowLabel={m.url} spark={spark} sla={sla} slaTarget={slaTarget} slaDays={slaDays} /></div>
        </MonitorCardRich>
        {hasMeta && (
          <div className={cn(CARD_LAYER, 'flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5', compact ? 'mb-2' : 'mt-2 mb-2.5')}>
            {compact
              // Kompakt: YALNIZ takım rozeti (ortak MonitorCardMeta'nın takım öğesi; grup/vekil alanı verilmez)
              ? <MonitorCardMeta monitor={{ team_id: m.team_id, team_name: m.team_name }} />
              : <>
                <MonitorCardMeta monitor={m} />
                <PageTags monitor={m} />
              </>}
          </div>
        )}
      </MonitorCardContent>
      {/* mt-auto: ızgara satırındaki kartlar aynı boya uzar — alt çubuk kısa kartta da en altta hizalı kalır. */}
      <MonitorCardFooter actions={actions || null} className="mt-auto">
        <PageCheckedAt at={m.checked_at} />
      </MonitorCardFooter>
    </MonitorCard>
  )
}
