import { Globe, CalendarDays, Network, Search, Target, Radio, ScanSearch, FlaskConical,
  Gauge, ShieldCheck, Folder, Wrench, Layers, FilePlus2, Pencil, Trash2 } from 'lucide-react'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { TONE_CLASS } from './ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Tür kartları — "hangi izlemede ne kadar oluşturma / değişiklik / silme oldu" tek bakışta.
 *
 * <p>Önceki hâli dört düz sayı kutusuydu (toplam + üç olay). Toplamlar "bugün 40 değişiklik
 * olmuş" der ama YÖNETİCİNİN sorduğu soruyu cevaplamaz: hangi izleme türü hareketli, nerede
 * silme var, hangi tür hiç dokunulmamış. Kart başına tür + olay kırılımı bunu doğrudan gösterir.
 *
 * <p>İkonlar Nav'daki izleme türü ikonlarının AYNISI: kullanıcı aynı türü menüde hangi şekille
 * tanıyorsa burada da onu görür, yeni bir görsel dil öğrenmesi gerekmez.
 *
 * <p>Kartlar aynı zamanda SÜZGEÇ: tıklanınca liste o türe daralır, tekrar tıklanınca açılır.
 * Bu yüzden sayılar tür süzgecinden ETKİLENMEZ (sunucu kasten yalnız tarih + kapsam uygular) —
 * aksi halde bir türe tıklandığında diğer kartlar sıfırlanır ve karşılaştırma kaybolurdu.
 */

// Dışa açık: İzleme Değişiklikleri listesi satırdaki tür ikonunu da buradan alır (tek ikon sözlüğü).
export const ICONS = {
  http: Globe, domain: CalendarDays, port: Network, dns: Search, keyword: Target,
  ping: Radio, page: ScanSearch, pagespeed: Gauge, scripted: FlaskConical,
  inventory: ShieldCheck, group: Folder, maintenance: Wrench,
}

/** Kart üstündeki kırılım: ikon + ton + i18n anahtarı. RESTORE/GROUP_RENAME "diğer"e toplanır. */
const BREAKDOWN = [
  { key: 'CREATE', Icon: FilePlus2, tone: 'new' },
  { key: 'UPDATE', Icon: Pencil, tone: 'edit' },
  { key: 'DELETE', Icon: Trash2, tone: 'danger' },
]
/** Kırılım çipi ve oran şeridi tonları (yeni = yeşil, düzenleme = marka mavisi, silme = kırmızı). */
const CHIP_TONE = { new: TONE_CLASS.success, edit: TONE_CLASS.info, danger: TONE_CLASS.danger }
const SEG_BG = { new: 'bg-success', edit: 'bg-primary', danger: 'bg-destructive' }

const sum = (obj) => Object.values(obj || {}).reduce((a, b) => a + Number(b || 0), 0)

/** Kart kabuğu — süzgeç düğmesi (shadcn outline Button, `aria-pressed`); seçili kart TÜM çerçevesiyle vurgulanır. */
const CARD = 'h-auto min-w-0 flex-col items-stretch justify-start gap-1.5 px-3.5 py-3 text-left font-normal whitespace-normal shadow-none transition-[border-color,box-shadow,transform] hover:-translate-y-px hover:border-primary hover:bg-card hover:shadow-[0_0_0_2px_color-mix(in_srgb,var(--primary)_18%,transparent)] motion-reduce:hover:translate-y-0 aria-pressed:border-primary aria-pressed:bg-primary/10 aria-pressed:shadow-[0_0_0_2px_color-mix(in_srgb,var(--primary)_30%,transparent)]'

function CardHead({ Icon, name }) {
  return (
    <span className="flex min-w-0 items-center gap-[7px]">
      <span aria-hidden="true" className="inline-flex size-[26px] shrink-0 items-center justify-center rounded-[7px] bg-primary/10 text-primary"><Icon size={16} /></span>
      <span className="truncate text-[0.82em] font-semibold text-muted-foreground">{name}</span>
    </span>
  )
}

export default function ChangeKindCards({ t, kindCounts = {}, selected = '', onSelect }) {
  // Sunucu yalnız KAYDI OLAN türleri döndürür; hiç değişiklik görmemiş tür kart üretmez
  // (11 boş kutu göstermek sinyali gürültüye çevirirdi).
  const kinds = Object.keys(kindCounts)
    .map(k => ({ kind: String(k).toLowerCase(), events: kindCounts[k], total: sum(kindCounts[k]) }))
    .filter(k => k.total > 0)
    .sort((a, b) => b.total - a.total)

  if (kinds.length === 0) return null

  const grandTotal = kinds.reduce((a, k) => a + k.total, 0)
  const busiest = kinds[0]

  return (
    // Telefonda 2'li ızgara, geniş ekranda en az 190 px'lik otomatik sütunlar
    <div data-slot="change-kind-grid" className="my-3 grid grid-cols-2 gap-2.5 sm:grid-cols-[repeat(auto-fill,minmax(190px,1fr))]">
      {/* "Tümü" kartı: hem genel toplam hem süzgeci temizleme yolu. */}
      <Button type="button" variant="outline" data-kind-card="all" aria-pressed={selected === ''}
        className={cn(CARD, 'bg-primary/5')} onClick={() => onSelect('')}>
        <CardHead Icon={Layers} name={t('chg.allKinds')} />
        <span className="text-[1.6em] leading-none font-bold tabular-nums">{grandTotal}</span>
        <span className="text-[0.74em] text-muted-foreground">{t('chg.kpiBusiest', t('chg.kind.' + busiest.kind))}</span>
      </Button>

      {kinds.map(({ kind, events, total }) => {
        const Icon = ICONS[kind] || Layers
        const isSel = selected === kind
        return (
          <SimpleTooltip key={kind} content={t('chg.kpiFilterHint')}>
            <Button type="button" variant="outline" data-kind-card={kind} aria-pressed={isSel}
              className={CARD} onClick={() => onSelect(isSel ? '' : kind)}>
              <CardHead Icon={Icon} name={t('chg.kind.' + kind)} />
              <span className="text-[1.6em] leading-none font-bold tabular-nums">{total}</span>

              {/* Kırılım: sıfır olan olay türü GÖSTERİLMEZ — "0 silme" bilgisi kartı doldurup
                  asıl sayıları bastırıyordu. Olay adı ekran okuyucuya sr-only metinle verilir. */}
              <span className="flex flex-wrap gap-[5px]">
                {BREAKDOWN.filter(b => Number(events?.[b.key] || 0) > 0).map(b => (
                  <Badge key={b.key} variant="outline" data-kind-chip={b.tone} title={t('chg.event' + b.key)}
                    className={cn('gap-[3px] rounded-full px-1.5 py-px text-[0.74em] font-semibold tabular-nums', CHIP_TONE[b.tone])}>
                    <b.Icon aria-hidden="true" /><span className="sr-only">{t('chg.event' + b.key)}: </span><span>{events[b.key]}</span>
                  </Badge>
                ))}
              </span>

              {/* Oran şeridi: sayıları okumadan da "burada çok silme var" görülebilsin.
                  Genişlik YÜZDEYLE değil flex oranıyla veriliyor — bu bir doluluk göstergesi değil,
                  üç parçalı bir DAĞILIM. Flex hem aritmetiği kaldırıyor hem de elle yazılmış yüzde
                  çubuklarını yasaklayan bekçiye (progress-guard) takılmıyor; o kural tek değerli
                  ilerleme çubukları için var ve ProgressBar burada yanlış bileşen olurdu. */}
              <span className="flex h-1 overflow-hidden rounded-full bg-border" aria-hidden="true">
                {BREAKDOWN.map(b => {
                  const n = Number(events?.[b.key] || 0)
                  return n > 0 ? (
                    <i key={b.key} data-seg={b.tone} className={cn('block h-full', SEG_BG[b.tone])} style={{ flexGrow: n }} />
                  ) : null
                })}
              </span>
            </Button>
          </SimpleTooltip>
        )
      })}
    </div>
  )
}
