import { Lightbulb, ScanSearch } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import {
  FINDING_TONE, categoryLabel, dimensionsText, findingLabel, formatBytesExact, formatMs, formatNumber, foundInText,
  isPageLoad, itemBytes, itemSizeText, optimizedImage, optimizedText, problemText, statusText, verdictOf, verdictText, verdictTone, cacheText,
} from './resourceScanModel.js'
import { CHIP, SmallBadge, TOUCH, UrlText, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/** Ekranda gösterilen yanıt başlıkları (sunucu yalnız bunları kaydeder; çerez / kimlik başlığı ASLA). */
const HEADER_KEYS = ['content_type', 'content_encoding', 'cache_control', 'expires', 'content_length', 'server', 'x_cache']

/** Adres kırılımı: ana makine, yol, sorgu (geçersiz adres → null). */
function urlParts(url) {
  try {
    const u = new URL(String(url))
    return { host: u.host, path: u.pathname || '/', query: u.search ? u.search.slice(1) : '' }
  } catch { return null }
}

/**
 * Kaynak analizi → NESNE AYRINTISI (r177): bir nesnenin adres kırılımı, son adresi (yönlendirme sonrası), nerede
 * bulunduğu (sayfanın HTML'i ya da hangi CSS), boyut + kaynağı, süreler, karar ve sorun, bağlantı yolu (vekil /
 * doğrudan — nesne başına), kaydedilen yanıt başlıkları ve bu nesneye değen öneriler. Sürümden önceki analizde
 * olmayan alanlar "Bu analizde kaydedilmemiş" der — değer UYDURULMAZ. İç içe ModalShell (detay penceresinin üstünde).
 */
export default function ResourceScanObjectDetail({ item, onClose, onGoRec, recommendations }) {
  const t = useT()
  const locale = useDateLocale()
  if (!item) return null
  const parts = urlParts(item.url)
  const nr = t('prs.od.notRecorded')
  const v = verdictOf(item)
  const vt = verdictText(item, t)
  const findings = Array.isArray(item.findings) ? item.findings : []
  const recCodes = new Set((recommendations || []).map((r) => r?.code))
  const headers = item.headers && typeof item.headers === 'object' ? item.headers : null
  const opt = optimizedImage(item)
  const dim = dimensionsText(item)
  const found = foundInText(item, t)
  const name = (parts && parts.path.split('/').filter(Boolean).pop()) || parts?.host || item.url || '—'
  const finalUrl = item.final_url === undefined ? undefined : item.final_url

  return (
    <ModalShell open onClose={onClose} title={name} icon={ScanSearch} size="md" scrollBody closeLabel={t('app.dismiss')}
      footer={<Button type="button" variant="outline" className={TOUCH} onClick={onClose}>{t('app.dismiss')}</Button>}>
      <div data-slot="prs-od" data-verdict={v} className="flex min-w-0 flex-col gap-4 text-sm">
        <div className="flex min-w-0 flex-col gap-1">
          <UrlText url={item.url} t={t} className="text-sm" />
          <span className="flex flex-wrap items-center gap-1.5">
            <ToneBadge tone="muted" className={CHIP}>{categoryLabel(item.category, t)}</ToneBadge>
            <ToneBadge tone={verdictTone(item)} className={cn(CHIP, 'tabular-nums')} data-slot="prs-od-status">
              {statusText(item, t)}{vt ? ` · ${vt}` : ''}
            </ToneBadge>
            {!isPageLoad(item) && <ToneBadge tone="muted" className={CHIP}>{t('prs.obj.notCounted')}</ToneBadge>}
          </span>
        </div>

        <Group title={t('prs.od.address')}>
          <Row label={t('prs.od.host')} value={item.host || parts?.host} empty={nr} />
          <Row label={t('prs.od.path')} value={parts?.path} empty={nr} />
          {parts?.query && <Row label={t('prs.od.query')} value={parts.query} />}
          <Row label={t('prs.od.finalUrl')} slot="prs-od-final"
            value={finalUrl === undefined ? null : (finalUrl ? <UrlText url={finalUrl} t={t} className="text-sm" /> : t('prs.od.noRedirect'))}
            empty={nr} />
          <Row label={t('prs.od.foundIn')} slot="prs-od-found" value={found || null} empty={nr} />
          <Row label={t('prs.od.via')} slot="prs-od-via"
            value={item.via === 'proxy' ? t('prs.via.proxy') : item.via === 'direct' ? t('prs.via.direct') : null} empty={nr} />
        </Group>

        <Group title={t('prs.od.measure')}>
          <Row label={t('prs.col.size')} value={itemBytes(item) == null ? itemSizeText(item, t, locale)
            : `${itemSizeText(item, t, locale)} · ${formatBytesExact(item.bytes, locale)}`} />
          <Row label={t('prs.od.sizeSource')}
            value={item.size_source === 'header' ? t('prs.od.sizeHeader') : item.size_source === 'body' ? t('prs.od.sizeBody')
              : item.size_source === 'unknown' ? t('prs.od.sizeUnknownSource') : null} empty={nr} />
          {opt && <Row label={t('prs.od.optimized')} slot="prs-od-optimized" value={optimizedText(item, t, locale)} />}
          {dim && <Row label={t('prs.export.col.dimensions')} value={t('prs.img.dims', dim)} />}
          <Row label={t('prs.od.ttfb')} value={item.ttfb_ms != null ? formatMs(item.ttfb_ms, locale) : null} empty="—" />
          <Row label={t('prs.od.duration')} value={formatMs(item.duration_ms, locale)} />
          <Row label={t('prs.od.start')} value={item.start_ms != null ? formatMs(item.start_ms, locale) : t('prs.obj.noWaterfall')} />
          {Number(item.redirects) > 0 && <Row label={t('prs.export.col.redirects')} value={formatNumber(item.redirects, locale)} />}
          {isPageLoad(item) && <Row label={t('prs.export.col.cache')} value={cacheText(item, t, locale)} />}
          {item.error && <Row label={t('prs.export.col.problem')} slot="prs-od-problem" value={problemText(item.error, t)} />}
        </Group>

        <Group title={t('prs.od.headers')} slot="prs-od-headers">
          {headers ? HEADER_KEYS.filter((k) => headers[k] != null && headers[k] !== '').map((k) => (
            <Row key={k} label={t(`prs.od.h.${k}`)} value={<code className={cn('font-mono text-xs', WRAP)}>{String(headers[k])}</code>} />
          )) : <p className="m-0 text-xs text-muted-foreground">{t('prs.od.headersNotRecorded')}</p>}
          {headers && HEADER_KEYS.every((k) => headers[k] == null || headers[k] === '') && (
            <p className="m-0 text-xs text-muted-foreground">{t('prs.od.headersNone')}</p>
          )}
        </Group>

        <Group title={t('prs.od.findings')} slot="prs-od-findings">
          {findings.length === 0 ? (
            <p className="m-0 text-xs text-muted-foreground">{t(item.findings_source === 'client' ? 'prs.od.findingsNoneOld' : 'prs.od.findingsNone')}</p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
              {findings.map((c) => (
                <li key={c} data-finding={c} className="flex min-w-0 flex-wrap items-center gap-2">
                  <SmallBadge tone={FINDING_TONE[c] || 'muted'}>{findingLabel(c, t)}</SmallBadge>
                  {onGoRec && recCodes.has(c) && (
                    <Button type="button" variant="ghost" size="sm" data-slot="prs-od-gorec" className={cn('h-7 px-2 text-xs', TOUCH)}
                      onClick={() => onGoRec(c)}>
                      <Lightbulb aria-hidden="true" />{t('prs.od.goRec')}
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {item.findings_source === 'client' && findings.length > 0 && (
            <p className="m-0 text-[11px] text-muted-foreground">{t('prs.od.findingsOld')}</p>
          )}
        </Group>
        {item.third_party === true && <p className="m-0 text-xs text-muted-foreground">{t('prs.od.thirdPartyNote')}</p>}
        {item.lazy === true && <p className="m-0 text-xs text-muted-foreground">{t('prs.od.lazyNote')}</p>}
      </div>
    </ModalShell>
  )
}

function Group({ title, slot, children }) {
  return (
    <section data-slot={slot} className="flex min-w-0 flex-col gap-1.5">
      <h4 className="m-0 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase">{title}</h4>
      <div className="flex min-w-0 flex-col gap-1.5">{children}</div>
    </section>
  )
}

function Row({ label, value, empty, slot }) {
  const missing = value === null || value === undefined || value === ''
  return (
    <dl data-slot={slot} data-missing={missing ? 'true' : undefined} className="m-0 grid min-w-0 grid-cols-1 gap-0.5 sm:grid-cols-[minmax(0,11rem)_minmax(0,1fr)] sm:gap-3">
      <dt className={cn('text-xs font-medium text-muted-foreground', WRAP)}>{label}</dt>
      <dd className={cn('m-0 text-sm', missing ? 'text-muted-foreground italic' : 'text-foreground', WRAP)}>{missing ? (empty ?? '—') : value}</dd>
    </dl>
  )
}
