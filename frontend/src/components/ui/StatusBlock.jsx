import { Empty, EmptyHeader, EmptyMedia, EmptyTitle, EmptyDescription, EmptyContent } from '@/components/shadcn/empty'
import { cn } from '@/lib/utils'

/**
 * Ortalanmış durum bloğu — boş liste, hata ekranı, "sonuç yok" gibi tam-bölüm durumları.
 * Çizim shadcn Empty (EmptyMedia / EmptyTitle / EmptyDescription / EmptyContent).
 *
 * Neden var: `.empty-state` (App.css) `color: var(--success)` taşıyor, yani BOŞ ve HATALI
 * durumlar yeşil çıkıyor. ErrorBoundary fallback'i de o sınıfı kullandığı için çökme ekranı
 * yeşil bir gövdeye kırmızı bir başlık koyuyordu. Bu bileşen tonu açık bir prop yapar.
 *
 * `loading`: ilk yükleme durumu (QA ISSUE-006) — simge döner, yükseklik boş-durumla aynı kalır,
 * kartlar gelince sayfa zıplamaz.
 *
 * Saf sunum: metinler ve ikon dışarıdan gelir, içinde useT() yoktur.
 * Test kancası: kök `data-slot="empty"` + `data-tone`.
 */
const TONE_INK = {
  neutral: 'text-muted-foreground',
  info:    'text-primary',
  success: 'text-success',
  danger:  'text-destructive',
}

export default function StatusBlock({
  tone = 'neutral', icon: Icon, title, description, actions, children, role, loading = false, className = '',
}) {
  const key = TONE_INK[tone] ? tone : 'neutral'
  return (
    <Empty
      role={role}
      data-tone={key}
      className={cn('flex-none gap-4 px-4 py-10 text-[1.05em] md:px-4 md:py-10', TONE_INK[key], className)}
    >
      {(Icon || title || description) && (
        <EmptyHeader className="max-w-xl">
          {Icon && (
            <EmptyMedia className="mb-1" aria-hidden="true">
              <Icon size={36} className={loading ? 'animate-spin motion-reduce:animate-pulse' : undefined} />
            </EmptyMedia>
          )}
          {title && <EmptyTitle className="text-[1.2em] font-semibold">{title}</EmptyTitle>}
          {description && <EmptyDescription>{description}</EmptyDescription>}
        </EmptyHeader>
      )}
      {actions && <EmptyContent className="max-w-none flex-row flex-wrap justify-center gap-2">{actions}</EmptyContent>}
      {children}
    </Empty>
  )
}
