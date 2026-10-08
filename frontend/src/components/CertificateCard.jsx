import { memo } from 'react'
import { CirclePause } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import CertificateCardExtras from './CertificateCardExtras.jsx'
import { isInsecure, securityTitle } from '../utils/certSecurity.js'
import CopyButton from './ui/CopyButton.jsx'
import NocStatus from './noc/NocStatus.jsx'
import { CARD_COPY, CARD_LAYER, MonitorCardContent, MonitorCardFooter, MonitorCardHeader } from './monitoring/MonitorCard.jsx'
import {
  CertActions, CertCheckedAt, CertErrorNote, CertExtrasPending, CertHero, CertIssuerLine, CertMeta, CertNotices, CertPlanChip, CertReasons,
  CertStatusBadge, CertTierBadge,
} from './certcard/CertCardParts.jsx'
import {
  TONE_LABEL, certTone, isPausedCert, nonStandardPort, planChipOf, reasonsOf, renewedDaysAgo, validityOf,
} from './certcard/certCardModel.js'
import ManualCertBadge from './manualcert/ManualCertBadge.jsx'
import { isManualCert } from './manualcert/manualCertModel.js'
import { Card } from '@/components/shadcn/card'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Genel Bakış / Uyarılar SERTİFİKA KARTI — 2026-09-27 yeniden tasarım (shadcn, mobil duyarlı), izleme kartlarıyla
 * (Ping/DNS/Keyword/Sayfa Hızı) aynı görsel dil. Okuma sırası:
 *   durum satırı (sözcükle durum · Güvensiz · katman) → alan adı (eş aralıklı, kopyala, 443 değilse port) →
 *   veren (CA) + anahtar/imza özeti → takım · platform · kontrol yolu → KAHRAMAN PANEL (kalan gün, bitiş tarihi,
 *   kalan geçerlilik çubuğu, yeni yenilendi / yenileme planı) → hata nedeni → güven/zincir gerekçe çipleri →
 *   bildirim durumu (şu an şeridi · sessiz alarm · e-posta sorunu) → [Zengin: sağlık · açık alarm · erişilebilirlik ·
 *   değişim/paylaşım/bakım/kontak] → alt çubuk (göreli son kontrol + eylemler; kartın dibine sabit).
 *
 * <p><b>Etkileşim — "stretched button"</b> (izleme kartı `monitoring/MonitorCard.jsx` ile aynı): kart `role="button"`
 * DEĞİL; gerçek düğme ALAN ADIDIR ({@code data-cert-open}), `::after` kartın tamamını örter — fare kartın herhangi bir
 * yerine tıklayınca detay açılır, klavye tek Tab durağında Enter/Space ile açar. İçteki etkileşimli ya da ipucu taşıyan
 * bölgeler örtünün ÜSTÜNDE (`CARD_LAYER`); onlara tıklamak detayı açmaz.
 *
 * <p><b>Ton</b> (`data-status`): valid | warning | high | critical | expired | error — sunucunun `alert_level` hükmü
 * (eşikler uydurulmaz, bkz. certcard/certCardModel). <b>Sol renk şeridi YOK</b> (kalıcı kural): durum rozetle; kritik
 * ve dolmuş kart TÜM kenarıyla kırmızı tonda, hatalı kart kırmızı kenarla.
 *
 * <p><b>Kompakt / Zengin:</b> `extra` (/card-extras satırı) verilirse zengin bölüm çizilir; kompakt = temel bilgiler.
 * Yenileme planı çipi her iki görünümde (plan `extra.renewal` ya da `live.renewal`'dan; plan verisi bu kart için
 * yüklenmediyse "Yenileme planla" kısayolu gösterilmez).
 *
 * <p><b>Mobil:</b> telefonda tek sütun, eylemler Şimdi kontrol et + "Diğer işlemler" menüsü (40 px), uzun alan adı iki
 * satıra kırılır, hiçbir şey taşmaz.
 *
 * <p>Test kancaları: kök `data-slot="card"` + `data-domain` + `data-status` (+ ürün turu `data-tour`); açma düğmesi
 * `[data-cert-open]`; `cert-status`, `cert-tier`, `cert-insecure`, `cert-port`, `cert-issuer-line`, `cert-algo`
 * (`data-strength`), `cert-meta`, `cert-platform(-name|-detail)`, `cert-hero` (`data-tone`), `cert-days`,
 * `cert-validity`, `cert-renewed`, `cert-plan` (`data-state`), `cert-reasons` / `cert-reason` (`data-reason`),
 * `cert-error`, `cert-card-chips` + `data-chip="silent|mail"`, `cert-checked-at`, `cert-card-actions`, `cert-card-more`;
 * 7/24 göstergesi `noc-status` (noc/NocStatus — izleme kartlarıyla aynı bileşen; `noc_notify` envanterden).
 */

// Kritik / dolmuş: tüm kenar + hafif zemin; hata: kırmızı kenar (sol şerit değil — kalıcı kural).
const CARD_TONE = {
  critical: 'border-destructive/50 bg-destructive/5 dark:border-destructive/60 dark:bg-destructive/10',
  expired: 'border-destructive/50 bg-destructive/5 dark:border-destructive/60 dark:bg-destructive/10',
  error: 'border-destructive/40 dark:border-destructive/55',
  // Pasif (izleme durduruldu, 2026-10-08): kesikli kenar + soluk zemin — sol şerit YOK (kalıcı kural)
  paused: 'border-dashed bg-muted/20 dark:bg-muted/10',
}

function CertificateCard({ cert, onClick, hasSilentAlert = false, hasMailFailure = false,
                                          onMailFailureClick, isWeak,
                                          onCheckNow, onEdit, onDuplicate, onDelete, onEditContacts, onOpenShared,
                                          checking = false, deleting = false, tourId,
                                          // Zengin görünüm (2026-09-19): /card-extras bloğu; extra yoksa kompakt
                                          extra, onOpenHealth, onConfirmRenewal, onPlanRenewal, confirming = false,
                                          // "Şu an" şeridi (2026-09-19): her iki görünümde; live = {uptime, alert, renewal?}
                                          live,
                                          // Yeni eklenen alan adı ilk kontrol + kart eki bekleniyor (App, 2026-09-28): boşluk yerine bekleme durumu
                                          warming = false, extrasPending = false }) {
  const t = useT()
  const tone = certTone(cert)
  // Pasif kart (2026-10-08): bilgi son kontrolden — bayat gerekçe/bildirim çipleri ve "yeni yenilendi" çizilmez
  const paused = isPausedCert(cert)
  const validity = validityOf(cert)
  const renewedDays = paused ? null : renewedDaysAgo(cert, tone)
  const reasons = paused ? [] : reasonsOf(cert, isWeak)
  const port = nonStandardPort(cert)
  // Plan verisi: zengin görünümde extra'dan, kompaktta live'dan (App aynı /card-extras satırından verir). İkisi de
  // yoksa bu kartın planı BİLİNMİYOR → kısayol gösterilmez.
  const plan = extra?.renewal ?? live?.renewal ?? null
  const planChip = planChipOf(cert, plan, !!(extra || live), !!onPlanRenewal)
  // Zengin görünümde erişilebilirlik kutusu "şu an" şeridinin işini görüyor → şerit tekrar çizilmez (kalabalık yok).
  const richUptime = !!(extra?.uptime && (extra.uptime.pct24 != null || extra.uptime.last_status))
  const domain = cert.domain

  return (
    <Card data-domain={domain} data-tour={tourId} data-status={tone} data-paused={paused ? 'true' : undefined}
      className={cn(
        'group/mcard relative min-w-0 gap-0 overflow-hidden rounded-xl px-4 pt-4 pb-3 shadow-none sm:px-5 sm:pt-[18px] sm:pb-3.5',
        'transition-[translate,box-shadow,border-color] duration-200 hover:-translate-y-[3px] hover:border-primary hover:shadow-lg',
        'motion-reduce:transition-none motion-reduce:hover:translate-y-0',
        CARD_TONE[tone],
      )}>
      <MonitorCardHeader>
        {/* ── Durum satırı: sözcükle durum · Güvensiz (süre rozeti doğru bilgi, kalır) · katman ── */}
        <div className="mb-2.5 flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1">
          <CertStatusBadge tone={tone} label={t(TONE_LABEL[tone])} />
          {isInsecure(cert) && (
            <Badge variant="outline" data-slot="cert-insecure" title={securityTitle(cert, t)}
              className="gap-1 border-destructive/40 bg-destructive/10 px-2 py-[3px] text-[11px] font-bold text-destructive dark:bg-destructive/20">
              {t('cert.sec.insecure')}
            </Badge>
          )}
          {/* Dosyadan yüklenen sertifika (2026-10-06) — yalnız manuel kayıtta; açıklaması dokununca açılır (örtünün üstünde) */}
          {isManualCert(cert) && (
            <ManualCertBadge version={cert.manual_version ?? null} uploadedAt={cert.manual_uploaded_at ?? null} rowLabel={domain} triggerClassName={CARD_LAYER} />
          )}
          {/* Sağ grup: 7/24 göstergesi (izleme kartlarıyla AYNI yer — sağ grubun başı; Zengin = hap, Kompakt = ikon + nokta)
              + katman. Gösterge örtünün üstünde (CARD_LAYER, tıklaması pencereyi açmaz); satır `noc_notify` taşımıyorsa
              (eski sunucu yanıtı) çizilmez. Düzenleme = kartın kendi Düzenle işleyicisi, form 7/24 alanına kaydırılmış. */}
          {(typeof cert.noc_notify === 'boolean' || cert.tier) && (
            <span className="ml-auto flex shrink-0 items-center gap-1.5">
              <NocStatus type="SSL" monitor={cert} rowLabel={domain} canEdit={!!onEdit} onEdit={onEdit} compact={!extra} triggerClassName={CARD_LAYER} />
              {cert.tier && <CertTierBadge tier={cert.tier} />}
            </span>
          )}
        </div>

        {/* Pasif kart (2026-10-08, kullanıcı: "kartın pasif olduğunu kart görünümünden anlamamız lazım"): rozetin hemen
            altında kesikli şerit — izleme durduruldu, bilgiler son kontrolden. Rozet + kesikli kenar + gri kahramanla birlikte. */}
        {paused && (
          <p data-slot="cert-paused-note" role="note"
            className="mb-2 flex min-w-0 items-start gap-1.5 rounded-md border border-dashed border-muted-foreground/40 bg-muted/60 px-2 py-1.5 text-xs font-medium text-muted-foreground">
            <CirclePause aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className="min-w-0">
              {t('certcard.pausedNote')}
              {/* Nasıl sürdürülür — yalnız düzenleyebilene (Düzenle yetkisi yoksa yönlendirme yanıltır) */}
              {onEdit && <span data-slot="cert-paused-howto"> {t('certcard.pausedHowTo')}</span>}
            </span>
          </p>
        )}

        {/* ── Alan adı = kartın AÇMA düğmesi (::after tüm kartı örter) · port · kopyala ── */}
        <div className="mb-1.5 flex min-w-0 items-start gap-1">
          <Button type="button" variant="ghost" data-cert-open="true"
            onClick={() => onClick(domain)}
            aria-label={t('card.openDetailFor', domain || '')}
            className={cn(
              'h-auto min-w-0 shrink justify-start rounded-none p-0 text-left font-mono text-[14.5px] leading-snug font-semibold tracking-normal whitespace-normal text-foreground',
              'hover:bg-transparent hover:text-foreground dark:hover:bg-transparent',
              'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50',
            )}>
            <span className="line-clamp-2 min-w-0 break-all">{domain || t('card.unknown')}</span>
          </Button>
          {port && (
            <Badge variant="outline" data-slot="cert-port" title={t('certcard.portTip', port)}
              className="mt-px h-5 shrink-0 rounded-md px-1.5 font-mono text-[11px] font-semibold text-foreground">
              :{port}
            </Badge>
          )}
          {domain && (
            <CopyButton value={domain} label={t('a11y.rowAction', domain, t('certcard.copyDomain'))} copiedLabel={t('certcard.domainCopied')}
              // pointer-coarse:-my-2: dokunmatikte 40 px kutu satırı itmesin — simge alan adının ilk satırıyla hizalı kalır
              variant="ghost" buttonSize="icon-xs" size={12} className={cn(CARD_COPY, 'shrink-0 pointer-coarse:-my-2')} />
          )}
        </div>
        <CertIssuerLine cert={cert} tone={tone} isWeak={isWeak} />
        <CertMeta cert={cert} rich={!!extra} />
      </MonitorCardHeader>

      <MonitorCardContent>
        <CertHero cert={cert} tone={tone} validity={validity} renewedDays={renewedDays}
          end={<CertPlanChip chip={planChip} cert={cert} onPlanRenewal={onPlanRenewal} />} />
        {!paused && <CertErrorNote error={cert.error} />}
        <CertReasons reasons={reasons} cert={cert} />
        {!paused && (
          <CertNotices cert={cert} live={richUptime ? null : live} hasSilentAlert={hasSilentAlert} hasMailFailure={hasMailFailure}
            onMailFailureClick={onMailFailureClick} />
        )}
        {extra && (
          <CertificateCardExtras cert={cert} extra={extra} reasons={reasons} onOpenHealth={onOpenHealth}
            onConfirmRenewal={onConfirmRenewal} onEditContacts={onEditContacts} confirming={confirming} onOpenShared={onOpenShared} />
        )}
        {!extra && extrasPending && <CertExtrasPending />}
      </MonitorCardContent>

      {/* mt-auto: ızgara satırındaki kartlar aynı boya uzar — alt çubuk kısa kartta da en altta hizalı kalır. */}
      <MonitorCardFooter className="mt-auto" actions={
        <CertActions cert={cert} plan={plan} onCheckNow={onCheckNow} onEdit={onEdit} onDuplicate={onDuplicate}
          onDelete={onDelete} onPlanRenewal={onPlanRenewal} checking={checking} deleting={deleting} />
      }>
        <CertCheckedAt at={cert.checked_at} warming={warming} />
      </MonitorCardFooter>
    </Card>
  )
}

/**
 * MEMO: panoda 50 kart aynı anda duruyor ve App saniyede bir yeniden render olabiliyor (inaktivite geri sayımı,
 * "Şimdi Kontrol Et" akışı). Kart saf: aynı proplarla aynı çıktıyı üretir. Kazancın gerçekleşmesi için ÇAĞIRAN da
 * referansları sabit tutmalı — App.jsx'te cardActions/onClick useCallback ile sarılı.
 */
export default memo(CertificateCard)
