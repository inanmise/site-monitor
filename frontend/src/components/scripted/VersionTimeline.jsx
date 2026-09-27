import { FilePlus2, Pencil, Globe, Users, Trash2, Undo2, RotateCcw, Sprout, ChevronRight } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import UserBadge from '../ui/UserBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Sürüm rozeti (eski .sc-ver-chip) — shadcn Badge, tek aralıklı; zaman çizelgesi, önizleme başlığı ve
 * şablon bilgisi AYNI rozeti kullanır. Test kancası: data-slot="version-chip".
 */
export function VersionChip({ className, children }) {
  return (
    <Badge variant="outline" data-slot="version-chip"
      className={cn('bg-muted/60 px-[7px] py-px font-mono text-[11px] font-bold text-muted-foreground', className)}>
      {children}
    </Badge>
  )
}

/**
 * Sürüm geçmişi zaman çizelgesi — şablon modalı ve monitör sekmesinin ORTAK gövdesi.
 *
 * <p><b>Neden tablo değil.</b> Önceden ikisi de `health-dbtable`'ı ödünç alıyordu: o tablo Sistem
 * Sağlığı'nın veritabanı listesi için yazılmış, {@code table-layout: fixed} ve orada tanımlı
 * genişlik sınıflarıyla çalışıyor. Buradaki beş sütun o sınıfları HİÇ kullanmadığı için tarayıcı
 * beşini de eşit bölüyordu — "Sürüm" ile "Not" aynı genişlikte. Üstelik hücreler dolgulu/kenarlıklı
 * sınıfları (`dbtcol-*-cell`) taşımadığından tamamen dolgusuz kalıyor, `dbtcol-th` ise sıralanabilir
 * sütun izlenimi veren el imleci + mavi hover taşıyordu (sıralama YOK). Sonuç: sıkışık, çerçevesiz
 * ve yanıltıcı bir ızgara.
 *
 * <p>Veri zaten bir olay akışı ("kim, ne zaman, ne yaptı"), tablo değil: her satır bir olay, sırası
 * anlamlı ve alanların uzunlukları çok farklı. Zaman çizelgesi bu şekli doğrudan karşılıyor, notu
 * kendi satırına alıyor ve dar modal genişliğinde kırpılmadan okunuyor.
 *
 * <p><b>Etkileşim — "stretched button" (2026-09-26, `monitoring/MonitorCard` deseni).</b> Satırın
 * kendisi düğme DEĞİL: `renderExtra` içine gerçek düğmeler giriyor (ReleaseNotesPanel yama düğmesi,
 * ChangeHistoryTab IP kopyala) ve düğme içinde düğme geçersiz HTML olurdu. Eskiden bu yüzden satır
 * `role="button"` taşıyan bir div'di. Şimdi başlık (sürüm + olay) GERÇEK bir shadcn Button'dır; onun
 * `::after` örtüsü satırın tamamını kaplar — satırın herhangi bir yerine basmak yine seçer, klavye tek
 * Tab durağında Enter/Space ile seçer (aria-pressed). `renderExtra` bölgesi örtünün ÜSTÜNDE durur ama
 * işaretçiyi yalnız içindeki etkileşimli öğeler alır; rozetlere/metne basmak satırı seçmeye devam eder.
 */

/** Örtünün üstünde, işaretçiyi yalnız etkileşimli çocuklara (bağlantı, düğme, odaklanabilir öğe, Radix
 *  ipucu tetiği — `data-state` taşır) veren `renderExtra` katmanı. */
const EXTRA_LAYER = 'pointer-events-none relative z-10 flex min-w-0 flex-1 flex-wrap items-center gap-2 ' +
  '[&_a]:pointer-events-auto [&_button]:pointer-events-auto [&_[tabindex]]:pointer-events-auto [&_[data-state]]:pointer-events-auto'

/** Olay → ikon + ton. Bilinmeyen olay nötr noktayla çizilir (sessiz boşluk olmaz). */
const EVENT_STYLE = {
  CREATE:   { icon: FilePlus2, tone: 'new' },
  SEED:     { icon: Sprout,    tone: 'new' },
  EDIT:     { icon: Pencil,    tone: 'edit' },
  RESTORE:  { icon: RotateCcw, tone: 'edit' },
  PROMOTE:  { icon: Globe,     tone: 'up' },
  DEMOTE:   { icon: Users,     tone: 'down' },
  DELETE:   { icon: Trash2,    tone: 'danger' },
  UNDELETE: { icon: Undo2,     tone: 'up' },
}
const DEFAULT_STYLE = { icon: Pencil, tone: 'edit' }

// Saf sunum: metinlerin tamamı prop olarak gelir (bileşen i18n'e bağlı değil).
// 2026-09-11 genişleme (sürüm & dağıtım geçmişi için, mevcut 3 çağıran DEĞİŞMEZ):
//   eventStyles — EVENT_STYLE üstüne birleşir (yeni olay türleri: UPGRADE/ROLLBACK/RELEASE…)
//   renderMeta(v) — verilince tarih+UserBadge satırının YERİNE çizilir (dağıtımda kullanıcı yok)
//   versionPrefix — "v" varsayılan
//   caret(v) — sağdaki ok yerine özel işaret (aç/kapa)
//   renderBelow(v) — satırın altına genişletilmiş içerik (değişiklik listesi)
export default function VersionTimeline({
  rows, selId, onPick, eventLabel, currentLabel, renderExtra,
  eventStyles, renderMeta, versionPrefix = 'v', caret, renderBelow,
}) {
  const styles = eventStyles ? { ...EVENT_STYLE, ...eventStyles } : EVENT_STYLE
  return (
    <ol className="sc-vt">
      {rows.map(v => {
        const style = styles[v.event_type] || DEFAULT_STYLE
        const Icon = style.icon
        const selected = selId === v.id
        const extra = renderExtra?.(v)
        return (
          <li key={v.id} className={cn('sc-vt-item', selected && 'is-sel')}>
            {/* Nokta satırın DIŞINDA: satır `relative` (örtünün kabı), nokta ise li'ye göre konumlanır. */}
            <span className={`sc-vt-dot sc-vt-dot--${style.tone}`} aria-hidden="true">
              <Icon size={13} />
            </span>
            <div data-slot="version-row" data-selected={selected ? 'true' : undefined}
              className={cn('relative flex items-start gap-2.5 rounded-lg py-[9px] pr-2.5 transition-colors hover:bg-muted/60 motion-reduce:transition-none',
                selected && 'bg-primary/8 hover:bg-primary/10')}>
              <span className="flex min-w-0 flex-1 flex-col gap-[3px]">
                <span className="flex flex-wrap items-center gap-2">
                  <Button type="button" variant="ghost" data-slot="version-pick" aria-pressed={selected}
                    onClick={() => onPick(v)}
                    className={cn('h-auto min-w-0 flex-wrap justify-start gap-2 rounded-none p-0 text-left text-[length:inherit] font-normal whitespace-normal',
                      'hover:bg-transparent dark:hover:bg-transparent focus-visible:ring-0',
                      'after:absolute after:inset-0 after:rounded-lg focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50')}>
                    <VersionChip>{versionPrefix}{v.version}</VersionChip>
                    <span className="text-[.88em] font-bold text-foreground">{eventLabel(v.event_type)}</span>
                    {v.current && (
                      <Badge variant="ghost" data-slot="version-current"
                        className="px-1 text-[10px] font-bold tracking-[.04em] text-success uppercase">{currentLabel}</Badge>
                    )}
                  </Button>
                  {extra && <span data-slot="version-extra" className={EXTRA_LAYER}>{extra}</span>}
                </span>
                {renderMeta ? renderMeta(v) : (
                <span className="sc-vt-meta">
                  <span className="sys-mono">{formatDateSec(v.created_at)}</span>
                  <span className="sc-vt-sep" aria-hidden="true">·</span>
                  <UserBadge username={v.created_by} size="sm" inline nameOnly />
                </span>
                )}
                {/* Not KENDİ satırında: tabloda "Not" sütunu diğerleriyle eşit genişlikteydi ve
                    uzun notlar okunmaz hâle geliyordu. Yoksa satır hiç çizilmez — boş "—" yerine. */}
                {v.note && <span className="sc-vt-note">{v.note}</span>}
              </span>

              {caret ? caret(v) : <ChevronRight size={15} className="sc-vt-caret" aria-hidden="true" />}
            </div>
            {renderBelow?.(v) && <div className="sc-vt-below">{renderBelow(v)}</div>}
          </li>
        )
      })}
    </ol>
  )
}
