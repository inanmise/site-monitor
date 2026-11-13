import { CircleSlash, RefreshCw, TriangleAlert } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'

/**
 * Kaynak durumu kapısı (2026-11-13): bir bölümün verisi yükleniyorsa iskelet, ilk okuma düştüyse "okunamadı + yeniden dene",
 * eski sunucu ucu tanımıyorsa "bu sürümde yok"; veri varsa içeriği çizer — tazeleme düştüyse üstte küçük "güncellenemedi"
 * notu (son veri kalır). `hidden` → hiçbir şey (izin yok). Bilinmeyen ASLA "yok" ya da yeşil değildir.
 * Test kancaları: `data-slot="stats-src-loading" | "stats-src-failed" | "stats-src-unsupported" | "stats-src-stale"`.
 */
export default function SourceGate({ source, onRetry, children, skeletonClassName = 'h-40', failedTitle }) {
  const t = useT()
  if (!source || source.status === 'hidden') return null
  if (source.data == null) {
    if (source.status === 'loading') {
      return (
        <div data-slot="stats-src-loading" aria-busy="true">
          <span role="status" className="sr-only">{t('stv.loading')}</span>
          <Skeleton className={skeletonClassName} />
        </div>
      )
    }
    if (source.status === 'unsupported') {
      return (
        <div data-slot="stats-src-unsupported">
          <StatusBlock tone="neutral" icon={CircleSlash} title={t('stx.unsupported')} className="rounded-xl border border-dashed py-6 md:py-6" />
        </div>
      )
    }
    return (
      <div data-slot="stats-src-failed">
        <StatusBlock tone="danger" icon={TriangleAlert} title={failedTitle || t('stx.failed')} description={t('stx.failedHint')}
          className="rounded-xl border border-dashed py-6 md:py-6"
          actions={onRetry ? (
            <Button type="button" variant="outline" className="pointer-coarse:h-10 max-sm:h-10" onClick={onRetry}>
              <RefreshCw aria-hidden="true" />{t('stx.retry')}
            </Button>
          ) : null} />
      </div>
    )
  }
  return (
    <>
      {source.status === 'failed' && (
        <p data-slot="stats-src-stale" role="status" className="m-0 flex items-center gap-1.5 text-xs text-amber-700 dark:text-amber-300">
          <TriangleAlert aria-hidden="true" className="size-3.5 shrink-0" />{t('stx.stale')}
        </p>
      )}
      {children(source.data)}
    </>
  )
}
