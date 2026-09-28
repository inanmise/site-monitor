import { lazy, Suspense } from 'react'
import { Globe } from 'lucide-react'
import ModalShell from '../../ui/ModalShell.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { cn } from '@/lib/utils'

const HttpMetricsExplorer = lazy(() => import('../HttpMetricsExplorer.jsx'))   // recharts → tembel yükle

/**
 * İstek Gezgini penceresi — ui/ModalShell, SABİT boyut (MonitorDetailModal / CertificateModal deseni, 2026-09-28):
 * yükseklik içerikle oynamaz (yükleniyor → veri geçişinde pencere zıplamaz), başlık sabit, YALNIZ gövde kayar ve
 * süzgeç çubuğu gövdenin tepesine yapışır; kaydırma çubuğuna yer ayrılır (genişlik oynamaz). Telefonda neredeyse tam
 * ekran (`100dvh - 2rem`), geniş ekranda 88vh / 1200 px. `grid-cols-[minmax(0,1fr)]`: uzun uç adı pencereyi taşırmaz.
 */
export default function HttpExplorerModal({ t, focus, onClose }) {
  return (
    <ModalShell open onClose={onClose} title={t('hreq.x.title')} icon={Globe} size="xl" scrollBody
      className={cn('grid-cols-[minmax(0,1fr)] sm:max-w-[min(96vw,1200px)]',
        'h-[calc(100dvh-2rem)] max-h-[calc(100dvh-2rem)] sm:h-[min(88vh,calc(100dvh-2rem))] sm:max-h-[min(88vh,calc(100dvh-2rem))] sm:w-full',
        '[&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable] [&_[data-slot=modal-shell-body]]:scroll-pt-28')}>
      <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
        <HttpMetricsExplorer initialFocus={focus} />
      </Suspense>
    </ModalShell>
  )
}
