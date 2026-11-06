import { Suspense } from 'react'
import { useT } from '../../i18n/index.jsx'
import ErrorBoundary from '../ErrorBoundary.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'

/**
 * Tembel yüklenen bir ekran bölümünün yedeği: iskelet (2026-11-06, performans PF4 O3).
 *
 * <p>Ayarlar bölümleri, Yönetim sekmeleri ve Sistem Sağlığı'nın ağır alt görünümleri artık ilk açılışta indiriliyor;
 * parça gelene kadar sayfanın iskeleti görünür (boş beyaz alan ya da kayan düzen yok). Ekran okuyucuya "yükleniyor"
 * (`role="status"`) — görsel çizgiler gizli.
 */
export function SectionSkeleton({ label, rows = 3, className }) {
  const t = useT()
  return (
    <div role="status" aria-busy="true" aria-label={label || t('tbl.loading')} data-slot="lazy-section-loading"
      className={cn('flex w-full min-w-0 flex-col gap-3', className)}>
      <Skeleton aria-hidden="true" className="h-7 w-2/5 max-w-xs" />
      <Skeleton aria-hidden="true" className="h-4 w-3/4 max-w-xl" />
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} aria-hidden="true" className="h-24 w-full" />)}
    </div>
  )
}

/**
 * Tembel bölüm kabı = hata sınırı + Suspense (iskelet yedeği).
 *
 * <p>`sectionKey` değişince (başka bölüme geçiş) hata sınırı SIFIRLANIR — bir bölümün parçası yüklenemedi diye diğer
 * bölümler kapanmaz, sayfanın geri kalanı (gezinti) çalışır. Parça yüklenemezse (dağıtım sonrası bayat paket)
 * ErrorBoundary'nin bilinen yolu işler: sürüm başına bir kez otomatik yenileme, sonra çökme ekranı ("Yenile" sayfayı
 * yeniden yükler — önbelleklenmiş başarısız `lazy()` sözü yeniden denenmez, bu yüzden yerinde yeniden bağlama yok).
 */
export default function LazySection({ sectionKey, label, rows, className, children }) {
  return (
    <ErrorBoundary key={sectionKey}>
      <Suspense fallback={<SectionSkeleton label={label} rows={rows} className={className} />}>
        {children}
      </Suspense>
    </ErrorBoundary>
  )
}
