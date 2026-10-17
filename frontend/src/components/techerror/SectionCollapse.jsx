import { createContext, useCallback, useContext, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { ChevronRight, ChevronsDownUp, ChevronsUpDown } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import { CopyTextButton } from './TechErrorParts.jsx'
import {
  isSectionOpen, parseOpenState, withSectionOpen, withSectionsOpened,
} from './techErrorModel.js'

/**
 * KATLANIR BÖLÜMLER (2026-10-16, kullanıcı kararı): rapor ve şablon düzenleyicisinin bölümleri kapalı başlar; açılan
 * bölümler aynı rapor / şablon için bu tarayıcıda hatırlanır (`sm.te.open.r.<rapor>` / `sm.te.open.t.<kanal>`, kişisel
 * kayıt — çıkışta silinir). Hatalı alanı olan bölüm kendiliğinden açılır; kapalıyken başlıkta hatalı alan sayısı görünür.
 *
 * <p>İçerik HER ZAMAN kurulu kalır (yarım yazılmış geçersiz metin, odak, tablo düzeni kaybolmaz); kapalıyken gizlenir.
 * Tarayıcı destekliyorsa `hidden="until-found"`: sayfada bul (Ctrl+F) kapalı bölümdeki metni de bulur ve bölümü açar.
 * Sağlayıcı (`SectionOpenContext`) yoksa kart eskisi gibi hep açık çizilir.
 */
export const SectionOpenContext = createContext(null)

/** Kişisel kayıt anahtarı: `r` rapor, `t` şablon (kanal). */
export const sectionOpenKey = (kind, id) => `sm.te.open.${kind}.${id}`

function readOpenState(key) {
  try { return parseOpenState(globalThis.localStorage?.getItem(key)) } catch { return parseOpenState(null) }
}

function writeOpenState(key, state) {
  try { globalThis.localStorage?.setItem(key, JSON.stringify(state.all ? { all: true } : { ids: state.ids })) } catch { /* depolama yok / dolu */ }
}

/** Testler ve "tümünü aç" için: bir raporun / şablonun açık bölümlerini yazar (`'all'` ya da kimlik listesi). */
export function rememberSectionsOpen(kind, id, value) {
  writeOpenState(sectionOpenKey(kind, id), value === 'all' ? { all: true, ids: [] } : { all: false, ids: [...(value || [])] })
}

/**
 * Açık bölüm durumu (anahtar değişince — başka rapor — yeniden okunur). `ids`: o anki bölüm kimlikleri ("hepsi açık"
 * iken bir bölüm kapatılınca geri kalanlar açık kalsın diye).
 */
export function useSectionOpenState(storageKey, ids) {
  const [st, setSt] = useState(() => ({ key: storageKey, state: readOpenState(storageKey) }))
  let current = st
  if (st.key !== storageKey) {
    current = { key: storageKey, state: readOpenState(storageKey) }
    setSt(current)
  }
  const idsRef = useRef(ids)
  idsRef.current = ids
  const update = useCallback((fn) => setSt((cur) => {
    const next = fn(cur.state)
    if (next === cur.state) return cur
    writeOpenState(cur.key, next)
    return { key: cur.key, state: next }
  }), [])
  const setOpen = useCallback((id, open) => update((s) => withSectionOpen(s, id, open, idsRef.current)), [update])
  const openMany = useCallback((list) => update((s) => withSectionsOpened(s, list)), [update])
  const setAll = useCallback((open) => update(() => (open ? { all: true, ids: [] } : { all: false, ids: [] })), [update])
  return { state: current.state, setOpen, openMany, setAll }
}

/**
 * Sağlayıcı değeri: açık mı, aç / kapat, kapalı başlıkta gösterilecek hatalı alan sayısı. `invalidCounts` (Map) yalnız
 * hata kümesi değişince yeni nesne olmalı — her tuşta değil (görsel bölümleri yeniden çizilmesin).
 */
export function useSectionOpenValue(open, invalidCounts) {
  const { state, setOpen } = open
  return useMemo(() => ({
    isOpen: (id) => isSectionOpen(state, id),
    setOpen,
    invalidCount: (id) => invalidCounts?.get(id) || 0,
  }), [state, setOpen, invalidCounts])
}

let untilFound = null
function supportsUntilFound() {
  if (untilFound == null) {
    try { untilFound = !!globalThis.document?.body && 'onbeforematch' in globalThis.document.body } catch { untilFound = false }
  }
  return untilFound
}

/*
 * GÖRÜNÜM (2026-10-17, kullanıcı geri bildirimi: "açılan bölümün etrafını çevreleyen çizgi kötü görüntü veriyor"):
 * bölüm başına TEK sakin çerçeve — `rounded-xl border-border bg-card`, gölge yok. Başlık satırı baştan sona aç / kapat
 * hedefidir (düğme h3'ün İÇİNDE kalır — WAI akordeon deseni; ok önde, metin sarar), vurgu ve odak halkası metin kutusunu
 * değil satırı çevreler; kopyalama ikonu ile taşı / sil düğmeleri satırın sağında, düğmenin DIŞINDA. Açık bölümde başlık
 * ile gövde arasında yalnız bir ayraç çizgisi (`border-t` gövdede); gövde kartın yüzeyinde durur. Kapalı bölüm başlık +
 * rozet satırı olarak okunur, boş kutu değil. Yalnız jeton renkleri (sekiz tema, `dark:` yok).
 */
const CARD = 'gap-0 border-border py-0 shadow-none'
const HEADER = 'flex flex-col gap-0 px-0'
const CONTENT = 'border-t px-4 pb-4 pt-4'
/** Başlık satırının iç boşluğu (6 px): düğmenin vurgu dikdörtgeni ve odak halkası kartın kenarından içeride kalır. */
const ROW = 'flex items-start gap-1 p-1.5'
/** Aç / kapat düğmesi: satırın tamamı; `px-2.5` + satır boşluğu = 16 px → ok gövdenin sol kenarıyla (`px-4`) hizalı. */
const TOGGLE = 'h-auto min-h-0 w-full items-start justify-start gap-2 whitespace-normal rounded-lg px-2.5 py-2.5 text-left'
  + ' text-base font-semibold leading-snug text-foreground has-[>svg]:px-2.5 pointer-coarse:min-h-10'
/**
 * Tek satırlı başlığın yüksekliği: `py-2.5` × 2 + `leading-snug` satırı (16 × 1,375 = 22 px) = 42 px. Sağdaki kopyala /
 * taşı / sil düğmeleri bu kutuda ortalanır → çok satıra saran başlıkta İLK satırla hizalı kalırlar (dokunmatikte de).
 */
const SIDE = 'flex min-h-[2.625rem] shrink-0 items-center'
/** Başlığın altındaki rozet / ipucu: ok (16 px) + aralık (8 px) kadar içeride → başlık METNİYLE hizalı (`ps-10`). */
const SUB = 'flex flex-col gap-1.5 pb-3 pe-4'

/**
 * Bölüm kartı. Başlık bir `h3` içindeki aç / kapat düğmesidir (WAI akordeon deseni); kopyalama ikonu başlığın kardeşi,
 * satırın sağında. `hint`: yalnız açıkken. `icon`: başlığın solunda (dekoratif, okun ardında). `headerEnd`: başlık
 * satırının sağı (ör. taşı / sil düğmeleri — kapalıyken de kullanılır). `toggleLabel`: düğmede başlık yerine gösterilecek
 * içerik.
 */
export function CollapsibleCard({
  sectionId, slot, title, copyText, toggleLabel, icon = null, hint = null, headerEnd = null, headerClassName,
  contentClassName, children, cardProps,
}) {
  const ctx = useContext(SectionOpenContext)
  const t = useT()
  const contentRef = useRef(null)
  const open = ctx ? ctx.isOpen(sectionId) : true
  const invalid = ctx && !open ? ctx.invalidCount(sectionId) : 0
  const setOpen = ctx?.setOpen
  const copy = copyText === undefined ? title : copyText
  // `data-slot={undefined}` Card'ın kendi data-slot'unu siler (proje sıfırlaması `[data-slot] { border: 0 }`)
  const slotAttr = slot ? { 'data-slot': slot } : {}

  // Yerleşim etkisi: kapalı bölüm ilk çizimde bir kare bile görünmesin (desteklenen tarayıcıda CSS sınıfı kullanılmıyor)
  useLayoutEffect(() => {
    const el = contentRef.current
    if (!el || !setOpen || !supportsUntilFound()) return undefined
    if (open) {
      el.removeAttribute('hidden')
      return undefined
    }
    el.setAttribute('hidden', 'until-found')
    const onMatch = () => setOpen(sectionId, true)
    el.addEventListener('beforematch', onMatch)
    return () => el.removeEventListener('beforematch', onMatch)
  }, [open, setOpen, sectionId])

  // Başlık satırı: h3 (satırın kalanı) + kopyalama ikonu aynı `te-copy-row` kabında (ikon başlığın KARDEŞİ — başlığın
  // erişilebilir adı temiz kalır), sağda `headerEnd`. Kopyala ve taşı / sil düğmeleri ilk satır yüksekliğinde ortalanır.
  const titleRow = (heading) => (
    <div className={ROW}>
      <div data-slot="te-copy-row" className="flex min-w-0 flex-1 items-start">
        {heading}
        <CopyTextButton text={copy} className={SIDE} />
      </div>
      {headerEnd && <div className={SIDE}>{headerEnd}</div>}
    </div>
  )

  if (!ctx) {
    return (
      <Card className={CARD} {...slotAttr} data-section={sectionId} {...cardProps}>
        <CardHeader className={cn(HEADER, headerClassName)}>
          {titleRow(
            <h3 className="flex min-w-0 flex-1 items-start gap-2 px-2.5 py-2.5 text-base font-semibold leading-snug">
              {icon}
              <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{toggleLabel ?? title}</span>
            </h3>,
          )}
          {hint && <div className={cn(SUB, 'ps-4')}>{hint}</div>}
        </CardHeader>
        <CardContent className={cn(CONTENT, contentClassName)}>{children}</CardContent>
      </Card>
    )
  }

  return (
    <Collapsible open={open} onOpenChange={(o) => setOpen(sectionId, o)} asChild>
      <Card className={CARD} {...slotAttr} data-section={sectionId} data-open={open ? 'true' : 'false'} {...cardProps}>
        <CardHeader className={cn(HEADER, headerClassName)}>
          {titleRow(
            <h3 className="min-w-0 flex-1 text-base font-semibold leading-snug">
              <CollapsibleTrigger asChild>
                <Button type="button" variant="ghost" className={TOGGLE} data-slot="te-section-toggle">
                  <ChevronRight aria-hidden="true"
                    className={cn('mt-[3px] size-4 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none',
                      open && 'rotate-90')} />
                  {icon}
                  <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{toggleLabel ?? title}</span>
                </Button>
              </CollapsibleTrigger>
            </h3>,
          )}
          {(invalid > 0 || (open && hint)) && (
            <div className={cn(SUB, 'ps-10')}>
              {invalid > 0 && (
                <Badge variant="destructive" className="w-fit rounded-md overflow-visible whitespace-normal" data-slot="te-section-invalid">
                  {t('te.sec.invalid', invalid)}
                </Badge>
              )}
              {open && hint}
            </div>
          )}
        </CardHeader>
        <CollapsibleContent forceMount ref={contentRef} data-slot="te-section-content"
          className={supportsUntilFound() ? undefined : 'data-[state=closed]:hidden'}>
          <CardContent className={cn(CONTENT, contentClassName)}>{children}</CardContent>
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}

/** "Tümünü aç / kapat": bölümlerden biri kapalıysa açar, hepsi açıksa kapatır. */
export function ExpandAllButton({ allOpen, onToggle, className }) {
  const t = useT()
  const Icon = allOpen ? ChevronsDownUp : ChevronsUpDown
  return (
    <Button type="button" variant="outline" size="sm" className={cn('pointer-coarse:min-h-10', className)}
      data-slot="te-sections-all" onClick={() => onToggle(!allOpen)}>
      <Icon aria-hidden="true" /> {allOpen ? t('te.sec.collapseAll') : t('te.sec.expandAll')}
    </Button>
  )
}
