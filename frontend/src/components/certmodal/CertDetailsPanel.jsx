import { useMemo } from 'react'
import { Fingerprint, IdCard, KeyRound, CalendarRange, Network, TriangleAlert } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'
import { CertDetailsSummary, CertDetailsTiles } from './CertDetailsSummary.jsx'
import CertSanList from './CertSanList.jsx'
import {
  CopyValue, DefItem, DefList, DetailSection, HexValue, LinkValue, StatusChip, ToneChip, UsageBadges,
} from './CertDetailsParts.jsx'
import {
  clean, detailTone, formatRelative, isBlank, issuerOf, keyInfo, lifetimeOf, sanEntries, statusChip, subjectOf, tlsInfo, trustCode, usageItems,
} from './certDetailsModel.js'

/**
 * Sertifika penceresi → "Sertifika Detayları" sekmesi (2026-09-28 shadcn + mobil web yeniden tasarım). Eski düzen: aynı
 * görünümlü gri ad/değer kutuları + en altta hata kutusu + "N/A" metinleri. Yeni düzen, yukarıdan aşağı:
 *   1) Son kontrol hatası (varsa) — ui/AlertBanner danger, EN ÜSTTE;
 *   2) ÖZET kartı — durum rozeti, kalan gün, geçerlilik zaman çizelgesi (CertDetailsSummary);
 *   3) HIZLI BAKIŞ karoları — sertifika otoritesi, anahtar, alternatif adlar, güven ve zincir;
 *   4) BÖLÜMLER (shadcn Card + `dl`): Kimlik · Geçerlilik · Anahtar · Güvenlik (+ TLS sürümü / şifre takımı, zayıfsa uyarı
 *      rozeti) · Altyapı · Alternatif adlar (SAN).
 * Bölümün TÜM değerleri boşsa bölüm çizilmez; tek tek eksik değer soluk "—". Uzun değerler (DN, seri no, parmak izi,
 * OCSP/CRL) mono, kırpılmadan sarılır, kopyalanabilir (kopya HAM değer). OCSP/CRL yalnız http(s) ise dış bağlantı.
 * Yerleşim mobil önce: telefonda tek sütun, md+ tanım listesi iki sütun, lg+ Geçerlilik ve Anahtar yan yana.
 *
 * Props: `d` — sertifika tel nesnesi (snake_case; /api/history ya da önizlemede /api/check-preview); null = yükleniyor
 * (iskelet). `tone` — pencere başlığındaki durum anahtarı (verilmezse kartın kuralı). `now` — test için saat.
 * Salt okunur: panel hiçbir şey yazmaz (önizleme ve başka takımın kaydında da aynı).
 * Test kancaları: kök `data-slot="cert-details"`, bölüm `data-slot="cert-detail-section"` + `data-section`, alan
 * `data-slot="cert-detail-field"` + `data-field`.
 */

const FLAG_TONE = { HOSTNAME_MISMATCH: 'bad', UNTRUSTED_CA: 'bad' }

function Loading() {
  const t = useT()
  return (
    <div data-slot="cert-details-loading" role="status" aria-live="polite" className="flex min-w-0 flex-col gap-4">
      <span className="sr-only">{t('modal.loading')}</span>
      <Skeleton aria-hidden="true" className="h-44 w-full rounded-xl motion-reduce:animate-none" />
      <div aria-hidden="true" className="grid grid-cols-2 gap-2.5 lg:grid-cols-4">
        {[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-24 rounded-xl motion-reduce:animate-none" />)}
      </div>
      <Skeleton aria-hidden="true" className="h-40 w-full rounded-xl motion-reduce:animate-none" />
    </div>
  )
}

/** Tarih + göreli süre ("12.03.2027 14:00 · 6 ay sonra"); tarih yoksa boş (— çizilir). */
function DateValue({ iso, now }) {
  if (!iso) return null
  const rel = formatRelative(iso, dateLocale(), now)
  return (
    <span className="inline-flex flex-wrap items-baseline gap-x-1.5">
      <span className="tabular-nums">{formatDate(iso)}</span>
      {rel && <span className="text-xs text-muted-foreground">· {rel}</span>}
    </span>
  )
}

function remainingText(days, t) {
  if (typeof days !== 'number' || !Number.isFinite(days)) return null
  if (days < 0) return Math.abs(days) === 1 ? t('cdp.expiredAgoOne') : t('cdp.expiredAgoMany', Math.abs(days))
  if (days === 0) return t('cdp.expiresToday')
  return days === 1 ? t('cdp.daysLeftOne') : t('sslv.daysLeft', days)
}

export default function CertDetailsPanel({ d, tone: toneProp, now: nowProp }) {
  const t = useT()
  const sanList = useMemo(() => (d ? sanEntries(d.san, d.domain) : []), [d])
  if (!d) return <Loading />

  const now = nowProp ?? Date.now()
  const tone = detailTone(d, toneProp)
  const subject = subjectOf(d)
  const issuer = issuerOf(d)
  const key = keyInfo(d)
  const life = lifetimeOf(d, now)
  const ku = usageItems(d.key_usage, 'ku')
  const eku = usageItems(d.ext_key_usage, 'eku')
  const flags = Array.isArray(d.security_flags) ? d.security_flags.filter(Boolean) : []
  const chips = {
    trust: statusChip('trust', trustCode(d)),
    chain: statusChip('chain', d.chain_status),
    rev: statusChip('rev', d.revocation_status),
    dep: statusChip('dep', d.deployment_status),
  }
  const error = clean(d.error)
  const tls = tlsInfo(d)

  // Bölüm görünürlüğü: değerlerden EN AZ biri doluysa çizilir.
  const showIdentity = [d.domain, subject.cn, subject.org, issuer.cn, d.serial_number, d.subject_dn, d.issuer_dn].some((v) => !isBlank(v))
  const showValidity = [d.not_before, d.not_after, d.days_remaining, d.checked_at, d.intermediate_expiry].some((v) => !isBlank(v))
  const showKey = [key.alg, key.sig, d.is_ca, ku, eku].some((v) => !isBlank(v))
  const showSecurity = [d.fingerprint, chips.trust, chips.chain, chips.rev, chips.dep, flags, tls.version, tls.cipher].some((v) => !isBlank(v))
  const showInfra = [d.ocsp_url, d.crl_url, d.via, d.tls_mode_used].some((v) => !isBlank(v))
  const port = typeof d.port === 'number' && d.port > 0 ? d.port : null

  return (
    <div data-slot="cert-details" data-tone={tone} className="flex min-w-0 flex-col gap-4">
      {error && (
        <AlertBanner tone="danger" title={t('cdp.errorTitle')} className="mb-0">
          <span data-slot="cert-details-error" className="font-mono text-[12.5px]">{error}</span>
        </AlertBanner>
      )}

      <CertDetailsSummary d={d} tone={tone} now={now} />
      <CertDetailsTiles d={d} sanList={sanList} />

      <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
        {showIdentity && (
          <DetailSection slot="identity" icon={IdCard} title={t('modal.secIdentity')} className="lg:col-span-2">
            <DefList>
              <DefItem slot="domain" label={t('modal.domain')}>{clean(d.domain)}</DefItem>
              <DefItem slot="subject" label={t('modal.subject')}>{subject.cn}</DefItem>
              {subject.org && <DefItem slot="subject-org" label={t('cdp.subjectOrg')}>{subject.org}</DefItem>}
              <DefItem slot="issuer" label={t('modal.issuer')}>
                {issuer.cn && (
                  <span className="inline-flex flex-wrap items-baseline gap-x-1.5">
                    <span>{issuer.cn}</span>
                    {issuer.org && issuer.org !== issuer.cn && <span className="text-xs text-muted-foreground">· {issuer.org}</span>}
                  </span>
                )}
              </DefItem>
              <DefItem slot="serial" label={t('modal.serialNumber')} full>
                {clean(d.serial_number) && <HexValue value={clean(d.serial_number)} label={t('modal.serialNumber')} slot="cert-serial" />}
              </DefItem>
              <DefItem slot="subject-dn" label={t('modal.subjectDn')} full>
                {clean(d.subject_dn) && <CopyValue value={clean(d.subject_dn)} label={t('modal.subjectDn')} />}
              </DefItem>
              <DefItem slot="issuer-dn" label={t('modal.issuerDn')} full>
                {clean(d.issuer_dn) && <CopyValue value={clean(d.issuer_dn)} label={t('modal.issuerDn')} />}
              </DefItem>
            </DefList>
          </DetailSection>
        )}

        {showValidity && (
          <DetailSection slot="validity" icon={CalendarRange} title={t('modal.secValidity')}>
            <DefList>
              <DefItem slot="not-before" label={t('modal.notBefore')}><DateValue iso={d.not_before} now={now} /></DefItem>
              <DefItem slot="not-after" label={t('modal.notAfter')}><DateValue iso={d.not_after} now={now} /></DefItem>
              <DefItem slot="days" label={t('modal.daysRemain')}>
                {remainingText(d.days_remaining, t) && (
                  <span className={typeof d.days_remaining === 'number' && d.days_remaining < 0 ? 'font-semibold text-destructive' : 'tabular-nums'}>
                    {remainingText(d.days_remaining, t)}
                  </span>
                )}
              </DefItem>
              <DefItem slot="total" label={t('cdp.totalValidity')}>{life ? t('modal.alertDays', life.totalDays) : null}</DefItem>
              <DefItem slot="checked" label={t('modal.lastCheck')}><DateValue iso={d.checked_at} now={now} /></DefItem>
              {!isBlank(d.intermediate_expiry) && (
                <DefItem slot="intermediate" label={t('cdp.intermediate')}>
                  <span className="inline-flex flex-wrap items-baseline gap-x-1.5">
                    <span className="tabular-nums">{formatDate(d.intermediate_expiry)}</span>
                    {typeof d.intermediate_days_remaining === 'number' && (
                      <span className={d.intermediate_days_remaining <= 30 ? 'text-xs font-semibold text-destructive' : 'text-xs text-muted-foreground'}>
                        · {remainingText(d.intermediate_days_remaining, t)}
                      </span>
                    )}
                  </span>
                </DefItem>
              )}
            </DefList>
          </DetailSection>
        )}

        {showKey && (
          <DetailSection slot="key" icon={KeyRound} title={t('modal.secKey')}>
            <DefList>
              <DefItem slot="pubkey" label={t('modal.pubKeyAlgo')}>
                {key.alg && <span className="tabular-nums">{key.alg}{key.size ? ` · ${key.size} bit` : ''}</span>}
              </DefItem>
              <DefItem slot="sigalg" label={t('modal.sigAlgo')}>
                {key.sig && (
                  <span className="inline-flex flex-wrap items-baseline gap-x-1.5">
                    <span className="font-mono text-[12.5px]">{key.sig}</span>
                    {key.sigShort && key.sigShort !== key.sig && <span className="text-xs text-muted-foreground">· {key.sigShort}</span>}
                  </span>
                )}
              </DefItem>
              <DefItem slot="is-ca" label={t('modal.isCA')}>
                {d.is_ca == null ? null : d.is_ca ? t('modal.yes') : t('modal.no')}
              </DefItem>
              <DefItem slot="key-usage" label={t('modal.keyUsage')} full>
                {ku.length > 0 && <UsageBadges items={ku} kind="ku" />}
              </DefItem>
              <DefItem slot="ext-key-usage" label={t('modal.extKeyUsage')} full>
                {eku.length > 0 && <UsageBadges items={eku} kind="eku" />}
              </DefItem>
            </DefList>
          </DetailSection>
        )}

        {showSecurity && (
          <DetailSection slot="security" icon={Fingerprint} title={t('modal.secSecurity')} className="lg:col-span-2">
            <DefList>
              <DefItem slot="fingerprint" label={t('modal.fingerprint')} full>
                {clean(d.fingerprint) && <HexValue value={clean(d.fingerprint)} label={t('modal.fingerprint')} slot="cert-fingerprint" />}
              </DefItem>
              <DefItem slot="trust" label={t('modal.trustStatus')}>{chips.trust && <StatusChip chip={chips.trust} />}</DefItem>
              <DefItem slot="chain" label={t('modal.chainStatus')}>{chips.chain && <StatusChip chip={chips.chain} />}</DefItem>
              <DefItem slot="revocation" label={t('modal.revocationStatus')}>{chips.rev && <StatusChip chip={chips.rev} />}</DefItem>
              <DefItem slot="deployment" label={t('modal.deploymentStatus')}>{chips.dep && <StatusChip chip={chips.dep} />}</DefItem>
              {/* Bağlantı (2026-09-28): el sıkışmasının TLS sürümü + şifre takımı. Boşsa satır YOK (eski kayıt / hata).
                  Zayıf hükmü sunucudan (tls_assessment — CertificateHealthRules); açıklama görünür metin (dokunmatikte ipucu yok). */}
              {tls.version && (
                <DefItem slot="tls-version" label={t('cdp.tlsVersion')}>
                  <span className="flex min-w-0 flex-col gap-1">
                    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="tabular-nums" title={tls.version}>{tls.versionLabel}</span>
                      {tls.weakProtocol && (
                        <ToneChip tone="warn" icon={TriangleAlert} data-slot="cert-weak-protocol">{t('cdp.weakProtocol')}</ToneChip>
                      )}
                    </span>
                    {tls.weakProtocol && <span className="text-xs text-muted-foreground">{t('sslv.protocol.fail')}</span>}
                  </span>
                </DefItem>
              )}
              {tls.cipher && (
                <DefItem slot="cipher" label={t('cdp.cipherSuite')}>
                  <span className="flex min-w-0 flex-col gap-1">
                    <span className="inline-flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="min-w-0 font-mono text-[12.5px] [overflow-wrap:anywhere]">{tls.cipher}</span>
                      {tls.weakCipher && (
                        <ToneChip tone="warn" icon={TriangleAlert} data-slot="cert-weak-cipher">{t('cdp.weakCipher')}</ToneChip>
                      )}
                    </span>
                    {tls.weakCipher && <span className="text-xs text-muted-foreground">{t('sslv.cipher.fail')}</span>}
                  </span>
                </DefItem>
              )}
              {flags.length > 0 && (
                <DefItem slot="flags" label={t('cdp.flags')} full>
                  <span className="flex min-w-0 flex-wrap gap-1.5">
                    {flags.map((f) => (
                      <ToneChip key={f} tone={FLAG_TONE[f] ?? 'warn'} data-flag={f} title={f}>
                        {FLAG_TONE[f] ? t(`cdp.flag.${f}`) : f}
                      </ToneChip>
                    ))}
                  </span>
                </DefItem>
              )}
            </DefList>
          </DetailSection>
        )}

        {showInfra && (
          <DetailSection slot="infra" icon={Network} title={t('modal.secInfra')} className="lg:col-span-2">
            <DefList>
              <DefItem slot="ocsp" label={t('modal.ocspUrl')} full>
                {clean(d.ocsp_url) && <LinkValue value={clean(d.ocsp_url)} label={t('modal.ocspUrl')} />}
              </DefItem>
              <DefItem slot="crl" label={t('modal.crlUrl')} full>
                {clean(d.crl_url) && <LinkValue value={clean(d.crl_url)} label={t('modal.crlUrl')} />}
              </DefItem>
              {port && <DefItem slot="port" label={t('sslv.f.port')} mono>{String(port)}</DefItem>}
              {clean(d.via) && (
                <DefItem slot="via" label={t('sslv.f.route')}>{t(d.via === 'proxy' ? 'sslv.via.proxy' : 'sslv.via.direct')}</DefItem>
              )}
              {clean(d.tls_mode_used) && <DefItem slot="tls-mode" label={t('sslv.f.tlsMode')} mono>{clean(d.tls_mode_used)}</DefItem>}
            </DefList>
          </DetailSection>
        )}

        <CertSanList key={d.domain} entries={sanList} />
      </div>
    </div>
  )
}
