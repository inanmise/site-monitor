import { useRef, useState } from 'react'
import { Bookmark, BookmarkPlus, Check, Pencil, Trash2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useUserPrefs } from '../../hooks/useUserPrefs.js'
import { MAX_VIEWS_PER_LIST, pickParams, sameParams } from '../../hooks/userPrefsModel.js'
import { flushUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import { applyTabView } from '../../utils/navigate.js'
import { useDialog } from './Dialog.jsx'
import { useToast } from './Toast.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator,
  DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'

/**
 * "Görünümler" — liste başına KAYITLI GÖRÜNÜMLER (2026-10-02, öneri 23). Genel shadcn DropdownMenu: kayıtlı görünümler
 * (dokununca uygulanır; şu anki görünüm ✓) · "Bu görünümü kaydet…" (ad istemi `useDialog().showPrompt`) · Yeniden adlandır /
 * Sil alt menüleri (silme onaylı). Görünüm = o sekmenin O ANKİ sayfa-durumu URL parametreleri (`keys` tam adlar, `prefix`
 * önekli aile, `exclude` geçici durumlar — açık ayrıntı, pencere, sayfa numarası). Kaydetmeden önce bekleyen
 * debounce'lu URL yazımları boşaltılır (son yazılan arama da görünüme girsin).
 *
 * <p>Uygulama `applyTabView(tab, params)`: App sekmenin TÜM sayfa-durumu paramlarını silip görünümünkileri yazar ve sayfayı
 * yeniden bağlar — sayfalar durumlarını URL'den okuduğu için görünüm olduğu gibi yürürlüğe girer.
 *
 * <p>Saklama: kişisel tercih belgesi (`savedViews.<listKey>`, sunucuda; hooks/useUserPrefs). Tercihler yüklenmeden HİÇ
 * çizilmez — kayıtlı görünüm kullanmayan için araç çubuğundaki tek fark bu küçük düğmedir. Mevcut Tüm Sertifikalar ön
 * ayarları, Denetim Kaydı görünümleri ve Envanter'in kendi "Görünümler"i (sütun + yoğunluk da saklar) BU bileşene
 * taşınmadı; onların localStorage kayıtları tercih aynasıyla sunucuya gider.
 *
 * <p>Telefonda `labelClassName` ile etiket gizlenip yalnız ikon (40 px) kalabilir; ad her zaman `aria-label`'dadır.
 * Test kancaları: `data-slot="saved-views-trigger"`, öğeler `data-slot="saved-view"` (+ `data-active`). Ürün turu hedefi
 * (öneri 24): tetikte `data-tour="saved-views"` (tur adımı İzleme Panosu sayfasındakini gösterir).
 */
export default function SavedViewsMenu(props) {
  const prefs = useUserPrefs()
  if (!prefs.ready || !props.listKey) return null
  return <SavedViewsMenuInner prefs={prefs} {...props} />
}

function SavedViewsMenuInner({
  prefs, listKey, tab = listKey, keys = [], prefix = null, exclude = [],
  size = 'sm', className, labelClassName, align = 'end',
}) {
  const t = useT()
  const toast = useToast()
  const { showPrompt, showConfirm } = useDialog()
  const [open, setOpen] = useState(false)
  const [current, setCurrent] = useState(null)
  const actedRef = useRef(false)
  const views = prefs.viewsFor(listKey)

  const capture = () => {
    flushUrlQuerySync()
    return pickParams(window.location.search, { keys, prefix, exclude })
  }
  const onOpenChange = (next) => {
    if (next) setCurrent(capture())
    setOpen(next)
  }

  function apply(v) {
    applyTabView(tab, v.params || {})
  }

  async function save() {
    const params = capture()
    const name = await showPrompt({
      title: t('views.saveTitle'), message: t('views.saveMsg'), placeholder: t('views.namePh'),
      confirmText: t('views.saveBtn'), cancelText: t('app.cancel'),
    })
    const clean = String(name ?? '').trim()
    if (!clean) return
    const r = prefs.saveView(listKey, clean, params)
    if (r?.full) toast.error(t('views.full', MAX_VIEWS_PER_LIST))
  }

  async function rename(v) {
    const name = await showPrompt({
      title: t('views.renameTitle', v.name), defaultValue: v.name, placeholder: t('views.namePh'),
      confirmText: t('views.renameBtn'), cancelText: t('app.cancel'),
    })
    const clean = String(name ?? '').trim()
    if (!clean || clean === v.name) return
    const r = prefs.renameView(listKey, v.name, clean)
    if (r?.conflict) toast.error(t('views.conflict', clean))
  }

  async function remove(v) {
    const ok = await showConfirm({
      title: t('views.deleteTitle'), message: t('views.deleteMsg', v.name),
      confirmText: t('views.deleteBtn'), cancelText: t('app.cancel'),
    })
    if (ok) prefs.deleteView(listKey, v.name)
  }

  const act = (fn) => () => { actedRef.current = true; fn() }
  const label = views.length ? t('views.buttonCount', views.length) : t('views.button')
  const ITEM = 'min-h-10 sm:min-h-8'

  return (
    <DropdownMenu open={open} onOpenChange={onOpenChange} modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" size={size} data-slot="saved-views-trigger" data-tour="saved-views" aria-label={label} title={label}
          className={cn('shrink-0 gap-1.5', className)}>
          <Bookmark aria-hidden="true" />
          <span className={labelClassName}>{t('views.button')}</span>
          {views.length > 0 && (
            <Badge variant="secondary" aria-hidden="true" className="h-5 min-w-5 rounded-full px-1.5 tabular-nums">{views.length}</Badge>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align={align} collisionPadding={8} data-slot="saved-views-menu"
        className="z-(--z-menu) w-72 max-w-[calc(100vw-1rem)]"
        onCloseAutoFocus={(e) => {
          // KebabMenu deseni: seçilen eylem bir pencere açtıysa odak oradadır — tetiğe geri çekme.
          const picked = actedRef.current
          actedRef.current = false
          const a = document.activeElement
          if (picked && a && a !== document.body) e.preventDefault()
        }}>
        <DropdownMenuLabel className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('views.menuTitle')}</DropdownMenuLabel>
        {views.length === 0 ? (
          <DropdownMenuItem disabled className={cn(ITEM, 'text-muted-foreground')}>{t('views.empty')}</DropdownMenuItem>
        ) : (
          <DropdownMenuGroup>
            {views.map((v) => {
              const active = current != null && sameParams(current, v.params)
              return (
                <DropdownMenuItem key={v.name} data-slot="saved-view" data-active={active ? 'true' : undefined}
                  className={ITEM} onSelect={act(() => apply(v))}>
                  <Check aria-hidden="true" className={cn('text-primary', !active && 'invisible')} />
                  <span className="min-w-0 truncate" title={v.name}>{v.name}</span>
                  {active && <span className="sr-only">{t('views.current')}</span>}
                </DropdownMenuItem>
              )
            })}
          </DropdownMenuGroup>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem className={ITEM} data-slot="saved-views-save" onSelect={act(save)}>
          <BookmarkPlus aria-hidden="true" />{t('views.save')}
        </DropdownMenuItem>
        {views.length > 0 && (
          <>
            <DropdownMenuSub>
              <DropdownMenuSubTrigger className={cn(ITEM, 'gap-2')}><Pencil aria-hidden="true" className="size-4 text-muted-foreground" />{t('views.rename')}</DropdownMenuSubTrigger>
              <DropdownMenuSubContent className="z-(--z-menu) max-w-[calc(100vw-2rem)]">
                {views.map((v) => (
                  <DropdownMenuItem key={v.name} className={ITEM} onSelect={act(() => rename(v))}>
                    <span className="min-w-0 truncate" title={v.name}>{v.name}</span>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuSubContent>
            </DropdownMenuSub>
            <DropdownMenuSub>
              <DropdownMenuSubTrigger className={cn(ITEM, 'gap-2')}><Trash2 aria-hidden="true" className="size-4 text-muted-foreground" />{t('views.delete')}</DropdownMenuSubTrigger>
              <DropdownMenuSubContent className="z-(--z-menu) max-w-[calc(100vw-2rem)]">
                {views.map((v) => (
                  <DropdownMenuItem key={v.name} variant="destructive" className={ITEM} onSelect={act(() => remove(v))}>
                    <span className="min-w-0 truncate" title={v.name}>{v.name}</span>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuSubContent>
            </DropdownMenuSub>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
