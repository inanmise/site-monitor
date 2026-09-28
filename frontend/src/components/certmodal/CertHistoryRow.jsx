import { ArrowDown, ArrowUp, ChevronDown, CircleCheck, CircleX, Replace, Wrench } from 'lucide-react'
import { formatDate, formatDateOnly, formatDateSec, formatTime } from '../../api/client'
import { toUtc } from '../../utils/localDay.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { daysTone, isFailed, isWarning, techLine } from './certHistoryModel.js'

/**
 * Sertifika Kontrol Geçmişi satır parçaları (2026-09-28 yeniden tasarım) — masaüstü tablo hücreleri, telefon kartı ve
 * açılır ayrıntı paneli AYNI parçalardan çizilir. Eski `upt-rt-*` App.css hücrelerinin yerine shadcn Badge / Button /
 * Card + Tailwind jetonları. Renk tek başına anlam taşımaz: durum rozeti ikon + metin, kalan gün değişimi ok + metin.
 *
 * Test kancaları: `data-slot="cert-hist-status"` (+ `data-status` ok|fail), `cert-hist-days` (+ `data-tone`),
 * `cert-hist-renewed`, `cert-hist-newcert`, `cert-hist-delta`, `cert-hist-error`, `cert-hist-detail`, `cert-hist-card`.
 */

const OK_TONE = 'border-success/40 bg-success/10 text-success'
const FAIL_TONE = 'border-destructive/40 bg-destructive/10 text-destructive'
const NEW_TONE = 'border-primary/40 bg-primary/10 text-primary'
const DAYS_TEXT = {
  danger: 'text-destructive',
  warning: 'text-amber-700 dark:text-amber-400',
  ok: 'text-foreground',
  none: 'text-muted-foreground',
}

/** "45 gün" / "1 day" — İngilizcede tekil/çoğul ayrımı. */
export function daysText(n, t) {
  return Math.abs(Number(n)) === 1 ? t('certh.daysOne', n) : t('certh.days', n)
}

/** Satırın erişilebilir kimliği — satır düğmelerinin adında (aynı adlı 50 "Ayrıntı" düğmesi olmasın). */
export const rowLabelOf = (c) => formatDateSec(c?.checked_at)

/** Kontrol zamanı: yalnız saat (gün, üstteki gün ayırıcısında); tam damga `title` + makine okunur `dateTime`. */
export function CheckTime({ item, className }) {
  return (
    <time dateTime={toUtc(item?.checked_at ?? '')} title={formatDateSec(item?.checked_at)}
      className={cn('font-medium tabular-nums', className)}>
      {formatTime(item?.checked_at)}
    </time>
  )
}

/** Durum rozeti: Başarılı / Başarısız (ikon + metin) + kontrol bakım penceresindeyse "Bakımda". */
export function CheckStatus({ item, t }) {
  const failed = isFailed(item)
  const Icon = failed ? CircleX : CircleCheck
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      <Badge variant="outline" data-slot="cert-hist-status" data-status={failed ? 'fail' : 'ok'}
        className={cn('gap-1 font-semibold', failed ? FAIL_TONE : OK_TONE)}>
        <Icon aria-hidden="true" />{t(failed ? 'certh.fail' : 'certh.ok')}
      </Badge>
      {item?.maintenance === true && (
        <Badge variant="warning" data-slot="cert-hist-maintenance" className="gap-1 font-medium">
          <Wrench aria-hidden="true" />{t('certh.maintenance')}
        </Badge>
      )}
    </span>
  )
}

/** Önceki kontrole göre değişim: yenilendi (↑ + gün) · yeni sertifika · kalan gün azaldı (↓, sessiz). */
export function DaysChange({ cmp, t }) {
  if (!cmp) return null
  if (cmp.renewed) {
    return (
      <Badge variant="outline" data-slot="cert-hist-renewed" className={cn('gap-1 font-semibold', OK_TONE)}>
        <ArrowUp aria-hidden="true" />{t('certh.renewedDelta', cmp.delta)}
      </Badge>
    )
  }
  if (cmp.certChanged) {
    return (
      <Badge variant="outline" data-slot="cert-hist-newcert" className={cn('gap-1 font-semibold', NEW_TONE)}>
        <Replace aria-hidden="true" />{t('certh.newCert')}
      </Badge>
    )
  }
  if (cmp.delta != null && cmp.delta < 0) {
    return (
      <span data-slot="cert-hist-delta" data-dir="down" className="inline-flex items-center gap-0.5 text-[11px] text-muted-foreground">
        <ArrowDown aria-hidden="true" className="size-3" />
        <span aria-hidden="true">{-cmp.delta}</span>
        <span className="sr-only">{t('certh.deltaDown', -cmp.delta)}</span>
      </span>
    )
  }
  return null
}

/** Kalan gün (tona göre) + değişim işareti. Süresi dolmuşsa "N gün önce doldu". */
export function CheckDays({ item, cmp, t, className }) {
  const d = item?.days_remaining
  if (d == null || !Number.isFinite(Number(d))) return <span className="text-muted-foreground">—</span>
  const tone = daysTone(d, isWarning(item))
  return (
    <span className="inline-flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1">
      <span data-slot="cert-hist-days" data-tone={tone} className={cn('font-semibold tabular-nums', DAYS_TEXT[tone], className)}>
        {Number(d) === -1 ? t('certh.expiredAgoOne') : Number(d) < 0 ? t('certh.expiredAgo', -Number(d)) : daysText(Number(d), t)}
      </span>
      <DaysChange cmp={cmp} t={t} />
    </span>
  )
}

/** Sertifika özeti: bitiş tarihi + veren (kırpılır, tam ad `title`'da ve ayrıntıda). Başarısız kontrolde "—". */
export function CheckCert({ item, t }) {
  const issuer = item?.issuer_cn || item?.issuer
  if (!item?.not_after && !issuer) return <span className="text-muted-foreground">—</span>
  return (
    <span className="flex min-w-0 flex-col">
      {item.not_after && <span className="tabular-nums">{t('certh.expires', formatDateOnly(item.not_after))}</span>}
      {issuer && <span className="block truncate text-muted-foreground" title={issuer}>{issuer}</span>}
    </span>
  )
}

/** Ayrıntı aç/kapa düğmesi — tam hata metni ve sertifika alanları satırın ALTINDA açılır (dokunmatikte de çalışır). */
export function DetailToggle({ open, onToggle, panelId, rowLabel, t, className }) {
  const text = t(open ? 'certh.hideDetails' : 'certh.showDetails')
  return (
    <Button type="button" variant="ghost" size="xs" data-slot="cert-hist-toggle"
      aria-expanded={open} aria-controls={open ? panelId : undefined}
      aria-label={t('a11y.rowAction', rowLabel, text)} onClick={onToggle}
      className={cn('shrink-0 gap-1 text-muted-foreground hover:text-foreground pointer-coarse:h-10', className)}>
      <span>{text}</span>
      <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
    </Button>
  )
}

/** Son sütun: hata metni (tek satır, kırpılmış) ya da teknik özet + ayrıntı düğmesi. */
export function CheckDetailCell({ item, open, onToggle, panelId, t }) {
  const line = techLine(item)
  return (
    <span className="flex min-w-0 items-center gap-2">
      <span className="min-w-0 flex-1">
        {item?.error ? (
          <span data-slot="cert-hist-error" className="block truncate text-destructive" title={item.error}>{item.error}</span>
        ) : (
          <span className="block truncate text-muted-foreground">{line || '—'}</span>
        )}
      </span>
      <DetailToggle open={open} onToggle={onToggle} panelId={panelId} rowLabel={rowLabelOf(item)} t={t} />
    </span>
  )
}

/**
 * Açılır ayrıntı paneli: tam hata metni (kopyalanabilir) + o kontrolde okunan sertifika alanları + önceki kontrole göre
 * değişim notu. Yalnız VERİSİ olan alanlar çizilir (başarısız kontrolde sertifika alanları boştur).
 */
export function CertCheckDetail({ id, item, cmp, t }) {
  const label = rowLabelOf(item)
  const fields = [
    [t('certh.dCheckedAt'), formatDateSec(item.checked_at)],
    [t('modal.notAfter'), item.not_after ? formatDate(item.not_after) : null],
    [t('modal.notBefore'), item.not_before ? formatDate(item.not_before) : null],
    [t('modal.issuer'), item.issuer_cn || item.issuer],
    [t('modal.subject'), item.subject],
    [t('modal.serialNumber'), item.serial_number, true],
    [t('modal.fingerprint'), item.fingerprint, true],
    [t('certh.dTls'), [item.tls_version, item.cipher_suite].filter(Boolean).join(' · ')],
    [t('modal.chainStatus'), item.chain_status],
    [t('modal.revocationStatus'), item.revocation_status],
    [t('modal.trustStatus'), item.trust_status],
    [t('modal.deploymentStatus'), item.deployment_status],
    [t('certh.dIntermediate'), item.intermediate_days_remaining != null ? daysText(item.intermediate_days_remaining, t) : null],
    [t('certh.dDuration'), item.response_ms != null ? `${item.response_ms} ms` : null],
    [t('certh.dErrorClass'), item.error_class],
  ].filter(([, v]) => v != null && v !== '')
  return (
    <div id={id} role="region" aria-label={t('certh.detailRegion', label)} data-slot="cert-hist-detail"
      className="flex min-w-0 flex-col gap-2.5 rounded-lg border bg-muted/30 p-3 text-xs">
      {cmp?.renewed && (
        <p className="m-0 flex items-center gap-1.5 font-medium text-success">
          <ArrowUp aria-hidden="true" className="size-3.5 shrink-0" />{t('certh.renewedNote', cmp.delta)}
        </p>
      )}
      {cmp?.certChanged && (
        <p className="m-0 flex items-center gap-1.5 font-medium text-primary [overflow-wrap:anywhere]">
          <Replace aria-hidden="true" className="size-3.5 shrink-0" />
          {cmp.olderSerial ? t('certh.changedNoteSerial', cmp.olderSerial) : t('certh.changedNote')}
        </p>
      )}
      {item.error && (
        // Kopyala düğmesi AlertBanner'ın yan sütununda DEĞİL metnin altında: telefonda yan sütun uzun hata metnini
        // ~150 px'lik bir şeride sıkıştırıyordu (Playwright 390×844, 2026-09-28).
        <AlertBanner tone="danger" title={t('modal.errorMsg')} className="mb-0">
          <span className="block whitespace-pre-wrap">{item.error}</span>
          <span className="mt-1.5 flex">
            <CopyButton value={item.error} label={t('a11y.rowAction', label, t('certh.copyError'))}
              copiedLabel={t('certh.copied')} variant="outline" className="bg-background pointer-coarse:size-10" />
          </span>
        </AlertBanner>
      )}
      {fields.length > 0 && (
        <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2 lg:grid-cols-3">
          {fields.map(([k, v, mono]) => (
            <div key={k} className="min-w-0">
              <dt className="text-[10.5px] font-semibold tracking-[.04em] text-muted-foreground uppercase">{k}</dt>
              <dd className={cn('m-0 font-medium text-foreground [overflow-wrap:anywhere]', mono && 'font-mono text-[11px]')}>{v}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  )
}

/**
 * Telefon kartı (< 768 px): üst satır saat + durum; ikinci satır kalan gün (büyük) + değişim, sağda bitiş; veren;
 * hata (iki satır); tam genişlikte 40 px ayrıntı düğmesi; açıkken ayrıntı paneli kartın içinde.
 */
export function CertHistoryCard({ item, cmp, open, onToggle, panelId, t }) {
  const issuer = item.issuer_cn || item.issuer
  return (
    <Card data-slot="cert-hist-card" data-status={isFailed(item) ? 'fail' : 'ok'}
      className="min-w-0 gap-2 px-3 py-2.5 text-xs shadow-none">
      <div className="flex min-w-0 items-center justify-between gap-2">
        <CheckTime item={item} className="text-sm" />
        <CheckStatus item={item} t={t} />
      </div>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <CheckDays item={item} cmp={cmp} t={t} className="text-sm" />
        {item.not_after && <span className="text-muted-foreground tabular-nums">{t('certh.expires', formatDateOnly(item.not_after))}</span>}
      </div>
      {issuer && <p className="m-0 truncate text-muted-foreground" title={issuer}>{issuer}</p>}
      {item.error && <p data-slot="cert-hist-error" className="m-0 line-clamp-2 text-destructive [overflow-wrap:anywhere]">{item.error}</p>}
      <DetailToggle open={open} onToggle={onToggle} panelId={panelId} rowLabel={rowLabelOf(item)} t={t}
        className="h-10 w-full justify-between border px-3 text-foreground" />
      {open && <CertCheckDetail id={panelId} item={item} cmp={cmp} t={t} />}
    </Card>
  )
}
