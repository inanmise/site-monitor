import { lazy, Suspense } from 'react'
import { useT } from '../../../i18n/index.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'

/**
 * Markdown editörü (@uiw/react-md-editor) İHTİYAÇ ANINDA yüklenir (2026-10-09, açılış paketi küçültme).
 *
 * <p>Eskiden ui/MarkdownEditor, haftalık rapor MdField ve envanter formu editörü statik içe aktarıyordu; her izleme
 * sayfası (MonitorPageHeader → MonitorGuideButton → MarkdownEditor) ve Alarm Geçmişi (→ olay formu) bu yüzden
 * ~1 MB'lık editör parçasını ön-yükleme grafiğinde taşıyordu. Artık sarmalayıcıyı içe aktarmak editörü çekmez;
 * editör ilk düzenlenebilir çizimde yüklenir, o arada AYNI yükseklikte yazma alanı biçimli bir iskelet durur
 * (yerleşim zıplamaz). Bir kez yüklenince sonraki açılışlar eşzamanlı çizilir (React.lazy önbelleği).
 *
 * <p>Prop'lar kütüphanenin MDEditor prop'larıdır; `commands` / `extraCommands` dizi ya da `(md) => dizi` kurucusu
 * olabilir (bkz. createMdEditor). `highlight` = markdown kaynağını yazarken renklendiren `common` girişi (envanter);
 * varsayılan `nohighlight` girişi (yazma alanında vurgu zaten kapalı olan editörler).
 */
const MdEditorPlain = lazy(() => import('./MdEditorPlain.jsx'))
const MdEditorHighlight = lazy(() => import('./MdEditorHighlight.jsx'))

/** Editörün kutusu (araç çubuğu + yazma alanı) biçiminde iskelet; yükseklik editörün `height`'ı (kütüphane varsayılanı 200). */
export function MdEditorSkeleton({ height = 200 }) {
  const t = useT()
  return (
    <div data-slot="md-editor-skeleton" role="status" aria-live="polite"
      style={{ height: typeof height === 'number' ? `${height}px` : height }}
      className="flex w-full min-w-0 flex-col overflow-hidden rounded-[3px] border border-input bg-background shadow-xs">
      <div aria-hidden="true" className="flex h-[30px] shrink-0 items-center gap-1.5 border-b border-input px-2">
        {[0, 1, 2, 3, 4, 5].map((i) => <Skeleton key={i} className="h-3.5 w-4 rounded-sm" />)}
      </div>
      <div aria-hidden="true" className="flex min-h-0 flex-1 flex-col gap-2 overflow-hidden p-2.5">
        <Skeleton className="h-3 w-3/4 max-w-full" />
        <Skeleton className="h-3 w-1/2 max-w-full" />
      </div>
      <span className="sr-only">{t('app.loading')}</span>
    </div>
  )
}

export default function LazyMdEditor({ highlight = false, ...props }) {
  const Impl = highlight ? MdEditorHighlight : MdEditorPlain
  return (
    <Suspense fallback={<MdEditorSkeleton height={props.height} />}>
      <Impl {...props} />
    </Suspense>
  )
}
