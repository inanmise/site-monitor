import { useT } from '../../i18n/index.jsx'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import { tagsOf } from '../../utils/monitorFilters.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import NocBadge from '../noc/forms/NocBadge.jsx'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter,
  MonitorAlarmIcon, MonitorCardRich, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { PingCheckedAt, PingTags } from '../ping/PingCardParts.jsx'
import { cn } from '@/lib/utils'
import {
  DomainChangedChip, DomainEppChips, DomainHero, DomainPlanChip, DomainProtection, DomainRegistrarLine, DomainSourceTag,
} from './DomainCardParts.jsx'
import { alarmMatchesStatus, expiryTone, lifeOf, planChipOf } from './domainCardModel.js'

/**
 * Uzun alan adı (IDN punycode / uzun etiket) en fazla İKİ satıra kırılır, taşmaz: başlık düğmesinin iç metni `truncate`
 * taşır (paylaşılan MonitorCardTitle) — burada iki satırlık kırpmaya çevrilir. Tam ad `title`'da ve detayın başlığında.
 */
const TITLE = cn(
  'mb-0 w-auto max-w-full min-w-0 shrink font-mono text-[14.5px] leading-snug font-semibold tracking-normal whitespace-normal',
  '[&>span]:line-clamp-2 [&>span]:break-all [&>span]:whitespace-normal',
)

/**
 * Alan Adı (Domain) Süre Bitişi izleme KARTI (2026-09-27 yeniden tasarım — shadcn, mobil duyarlı). Paylaşılan
 * MonitorCard ailesinden kurulur (stretched button, sol şerit YOK, duraklatılmış/alarm görünümü MonitorCard'da); içerik
 * dili panodaki sertifika kartıyla aynı (tonlu kahraman panel, kalan süre çubuğu, plan çipi). Okuma sırası:
 *   durum satırı (seçim · durum · alarm · bakım · Değişti — kaynak etiketi · bağlantı) → alan adı (eş aralıklı, en fazla
 *   iki satır, kopyala) + isteğe bağlı ad → registrar → KAHRAMAN PANEL (kalan gün · bitiş tarihi · kalan kayıt süresi
 *   çubuğu · yenileme planı çipi / kısayolu; bitiş bilinmiyorsa neden) → koruma çipleri (transfer kilidi · DNSSEC ·
 *   kara liste · NS) → EPP durum kodları (önem sırasıyla dört + "+N") → takım/grup · etiketler → son kontrol (göreli,
 *   tam zaman ipucunda) + eylemler (telefonda Şimdi kontrol et + "Diğer işlemler").
 * Kritik (alarmsız, etkin) kart TÜM kenarı kırmızı tonla çizilir (Port/HTTP kartlarıyla aynı); alarm varsa
 * MonitorCard'ın dış çizgisi geçerlidir.
 *
 * <p>Sayfaya ait kablolama YUVA olarak gelir (Port / HTTP / Ping kartlarıyla aynı sözleşme): `select` (toplu seçim
 * kutusu — seçim kümesi sayfada), `meta` (MonitorCardMeta), `actions` (MonitorCardActions — yetki kapıları ve
 * işleyiciler sayfada), `status` + `badge` + `alarmLabel` (sayfanın durum sözlüğü; detay penceresiyle ortak),
 * `onPlanRenewal` (yalnız izlemeyi yönetebilen kişide — paylaşılan RenewalPlanModal'ı sayfa açar). Kart yalnız sunumdan
 * sorumludur.
 *
 * <p>Test kancaları: kök `data-slot="card"` + `data-status` + `data-expiry` (unknown|expired|critical|warning|ok) +
 * `data-domain`; parçalar için bkz. DomainCardParts (`domain-hero`, `domain-days`, `domain-expiry`, `domain-life`,
 * `domain-unknown`, `domain-plan`, `domain-protection`, `domain-epp`, `domain-source`, `domain-changed`,
 * `domain-registrar`), ad `domain-name`.
 *
 * @param {object} monitor        GET /monitoring/domain satırı (snake_case)
 * @param {Function} onOpen       detay penceresini aç (başlık düğmesi — kartın tamamını örter)
 * @param {boolean} running       kontrol ŞU AN koşuyor (sayfanın isRunning(id)) — hiç sonucu yoksa "İlk kontrol yapılıyor…"
 * @param {Function} onPlanRenewal yenileme planı penceresini aç; verilmezse plan çipi salt bilgi, kısayol yok
 */
export default function DomainMonitorCard({
  monitor: m, density = 'rich', status = 'unknown', badge, alarmLabel, onOpen, onPlanRenewal, select, meta, actions, running = false,
}) {
  const t = useT()
  const compact = density === 'compact'
  const alarm = !!m.active_alarm
  const paused = !m.active
  const tone = expiryTone(m.days_remaining)
  const life = lifeOf(m)
  const chip = planChipOf(m, !!onPlanRenewal)
  // Kompakt: yalnız VAR OLAN plan çipi (kısayol Zengin'de ve telefon menüsünde)
  const endChip = chip && (!compact || chip.kind === 'plan') ? chip : null
  const name = String(m.name || '').trim()
  const hasMeta = !!(m.team_name || m.group_name || tagsOf(m).length || m.noc_notify)
  return (
    <MonitorCard status={status} density={density} running={running} alarm={alarm} inactive={paused} data-expiry={tone} data-domain={m.domain}
      className={cn(status === 'down' && !alarm && !paused && 'border-destructive/45 dark:border-destructive/60')}>
      <MonitorCardHeader>
        <MonitorCardTop end={<>
          {!compact && <DomainSourceTag monitor={m} />}
          <CopyLinkButton iconOnly url={monitorDeepLink('domain', m.id)} targetName={m.domain} variant="ghost" size="icon-xs" className={CARD_COPY} />
        </>}>
          {select || null}
          {badge}
          {!alarmMatchesStatus(m) && <MonitorAlarmIcon monitor={m} label={alarmLabel} />}
          <span className={CARD_LAYER}><MaintenanceBadge target={m.domain} /></span>
          <DomainChangedChip monitor={m} />
        </MonitorCardTop>
        {/* Alan adı: eş aralıklı, en fazla iki satır. Kopyala düğmesi ADI kopyalar (köşedeki düğme izlemenin BAĞLANTISINI). */}
        <div className="flex min-w-0 items-start gap-1">
          <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.domain)} title={m.domain} className={TITLE}>
            {m.domain}
          </MonitorCardTitle>
          <CopyButton value={m.domain} label={t('a11y.rowAction', m.domain, t('certcard.copyDomain'))} copiedLabel={t('certcard.domainCopied')}
            // pointer-coarse:-my-2: dokunmatikte 40 px kutu satırı itmesin — simge alan adının ilk satırıyla hizalı kalır
            variant="ghost" buttonSize="icon-xs" size={12} className={cn(CARD_COPY, 'shrink-0 pointer-coarse:-my-2')} />
        </div>
        <MonitorCardRich>
          {name && name !== m.domain && <p data-slot="domain-name" className="mt-0.5 truncate text-xs text-muted-foreground" title={name}>{name}</p>}
          <DomainRegistrarLine monitor={m} />
        </MonitorCardRich>
      </MonitorCardHeader>
      <MonitorCardContent className={cn(compact ? 'mt-2.5' : 'mt-3', !compact && !hasMeta && 'pb-1')}>
        <DomainHero monitor={m} tone={tone} life={life} compact={compact}
          end={endChip && <DomainPlanChip chip={endChip} monitor={m} onPlanRenewal={onPlanRenewal} />} />
        <MonitorCardRich>
          <DomainProtection monitor={m} />
          <DomainEppChips monitor={m} />
          {hasMeta && (
            <div className={cn(CARD_LAYER, 'mt-1 mb-2.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5')}>
              {meta}
              {m.noc_notify && <NocBadge rowLabel={m.domain} />}
              <PingTags monitor={m} />
            </div>
          )}
        </MonitorCardRich>
        {/* Kompakt: yalnız takım rozeti (grup/etiket/koruma/EPP Zengin'de) */}
        {compact && m.team_name && (
          <div data-slot="domain-team" className={cn(CARD_LAYER, 'mb-2 flex w-fit max-w-full min-w-0 items-center text-[.78em]')}>
            <TeamBadge teamId={m.team_id} teamName={m.team_name} />
          </div>
        )}
      </MonitorCardContent>
      {/* mt-auto: ızgara satırındaki kartlar aynı boya uzar — alt çubuk kısa kartta da en altta hizalı kalır. */}
      <MonitorCardFooter actions={actions || null} className="mt-auto">
        <PingCheckedAt at={m.checked_at} />
      </MonitorCardFooter>
    </MonitorCard>
  )
}
