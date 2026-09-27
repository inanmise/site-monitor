import { cn } from '@/lib/utils'

/**
 * Sayfa başlığı + eylem çubuğu — her sayfanın üstünde AYNI düzen (2026-09-27, kullanıcı isteği: Pano'daki
 * "Şimdi Kontrol Et / Domain Ekle" düğmeleri kullanıcı deneyimi açısından en uygun yere).
 *
 * <p>Solda ikon kutusu + başlık + tek satırlık açıklama + meta çipleri (sayaçlar, son güncelleme); sağda eylemler.
 * Eylem sırası soldan sağa "en az → en çok önemli" (Yenile · ikincil · BİRİNCİL en sağda): göz başlıktan sağa
 * kayar, birincil eylem her sayfada aynı köşede bulunur. Telefonda eylemler başlığın altına iner ve düğmeler
 * satırı eşit paylaşır (sığmayan alt satıra tek parça sarar) — başparmakla ulaşılır, gizli menü yok.
 *
 * Props:
 *  - icon: lucide bileşeni (ikon kutusu; telefonda gizli — yer dar)
 *  - title, description: metin (title zorunlu; h2 — sayfa hiyerarşisi App başlığının altında)
 *  - meta: React düğümü — Badge/çip listesi (opsiyonel)
 *  - actions: React düğümü — Button'lar (opsiyonel)
 *  - children: başlığın ALTINA tam genişlik ek içerik (ör. uyarı şeridi)
 * Test kancaları: data-slot="page-header|page-title|page-description|page-meta|page-actions".
 */
export default function PageHeader({ icon: Icon, title, description, meta, actions, className, children, ...rest }) {
  return (
    <header data-slot="page-header"
      className={cn('mb-4 flex min-w-0 flex-col gap-3', className)} {...rest}>
      {/* ≥640 px: başlık + eylemler tek satır; SIĞMAZSA eylemler alt satıra sarar ve sağa yaslanır (flex-wrap + ml-auto).
          Eskiden eylemler `shrink-0` idi ve başlık bloğu (min-w-0) sıfıra ezilirdi — 768 px'te kenar çubuğu açıkken
          "Genel Bakış" başlığı düğmelerin altına biniyor, açıklama kelime kelime alt alta diziliyordu (2026-09-27).
          Başlık bloğu 18rem tabanla büyür; eylemler kabı en fazla satır genişliği kadar olur ve içinde sarar. */}
      <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between sm:gap-x-4 sm:gap-y-3">
        <div className="flex min-w-0 items-start gap-3 sm:flex-[1_1_18rem]">
          {Icon && (
            <span aria-hidden="true"
              className="hidden size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary sm:inline-flex">
              <Icon className="size-5" />
            </span>
          )}
          <div className="min-w-0">
            <h2 data-slot="page-title" className="text-xl leading-tight font-bold tracking-tight sm:text-2xl">{title}</h2>
            {description && (
              <p data-slot="page-description" className="mt-0.5 max-w-[78ch] text-sm text-muted-foreground">{description}</p>
            )}
            {meta && (
              <div data-slot="page-meta" className="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">{meta}</div>
            )}
          </div>
        </div>
        {actions && (
          // Telefonda her düğme satırı eşit paylaşır (flex-1) ve en az 40 px yüksektir (dokunma hedefi kuralı — sayfalar
          // size="sm" düğme verse de); ≥640 px'te doğal genişlik, sağa yaslı.
          <div data-slot="page-actions"
            className="flex min-w-0 flex-wrap items-center gap-2 sm:ml-auto sm:max-w-full sm:justify-end [&>*]:flex-1 sm:[&>*]:flex-none max-sm:[&_[data-slot=button]]:min-h-10">
            {actions}
          </div>
        )}
      </div>
      {children}
    </header>
  )
}
