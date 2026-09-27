import { useState } from 'react'
import { Bookmark, Check, Trash2 } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { EMPTY_FILTERS, QUICK_VIEWS, normalizeFilters, sameFilters } from './auditFilters.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'

// ── Kaydedilebilir görünümler (localStorage; kişisel kolaylık — paylaşılmaz) ──
export const SAVED_VIEWS_KEY = 'sm.audit.savedViews'
/** Önek konvansiyonundan önce kullanılan ad — okunur, taşınır, sonra silinir (bir kerelik göç). */
const SAVED_VIEWS_KEY_LEGACY = 'auditSavedViews'

export function loadSavedViews() {
  try {
    const cur = JSON.parse(localStorage.getItem(SAVED_VIEWS_KEY))
    if (Array.isArray(cur)) return cur
    // Göç: kullanıcı kaydettiği görünümleri anahtar yeniden adlandırıldı diye kaybetmemeli.
    const old = JSON.parse(localStorage.getItem(SAVED_VIEWS_KEY_LEGACY))
    if (Array.isArray(old) && old.length) {
      localStorage.setItem(SAVED_VIEWS_KEY, JSON.stringify(old))
      localStorage.removeItem(SAVED_VIEWS_KEY_LEGACY)
      return old
    }
    return []
  } catch { return [] }   // kota/gizli mod/bozuk JSON — görünümsüz devam
}
function persistSavedViews(views) {
  try { localStorage.setItem(SAVED_VIEWS_KEY, JSON.stringify(views)) } catch { /* kota/gizli mod — sessiz geç */ }
}

const GROUP_LABEL = 'px-2 pt-1 pb-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase'

/**
 * "Görünümler" — hazır denetim senaryoları (güvenlik olayları, yetki/yapılandırma değişiklikleri, başarısız
 * girişler) + kullanıcının kaydettiği süzgeç kümeleri tek shadcn Popover'da. Etkin görünüm süzgeçten TÜRETİLİR;
 * etkin olana yeniden basmak süzgeci temizler.
 */
export default function AuditViewsMenu({ filters, onApply }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [views, setViews] = useState(loadSavedViews)
  const [name, setName] = useState('')

  function apply(next) { onApply(next); setOpen(false) }
  function save() {
    const n = name.trim()
    if (!n) return
    const next = [...views.filter(v => v.name !== n), { name: n, filters }]
    setViews(next); persistSavedViews(next); setName('')
  }
  function remove(n) {
    const next = views.filter(v => v.name !== n)
    setViews(next); persistSavedViews(next)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm" className="h-10 md:h-8">
          <Bookmark aria-hidden="true" /> {t('audit.views')}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="z-(--z-menu) w-80 max-w-[calc(100vw-2rem)] p-2">
        <div role="group" aria-label={t('audit.quickViews')}>
          <div className={GROUP_LABEL}>{t('audit.quickViews')}</div>
          {QUICK_VIEWS.map(v => {
            const target = normalizeFilters({ ...EMPTY_FILTERS, ...v.filter })
            const active = sameFilters(filters, target)
            return (
              <Button key={v.key} type="button" variant="ghost" size="sm" aria-pressed={active}
                onClick={() => apply(active ? { ...EMPTY_FILTERS } : target)}
                className={cn('h-10 w-full justify-start font-normal sm:h-8', active && 'bg-primary/10 text-primary')}>
                <Check aria-hidden="true" className={cn(!active && 'invisible')} />
                {t('audit.preset.' + v.key)}
              </Button>
            )
          })}
        </div>
        <Separator className="my-2" />
        <div role="group" aria-label={t('audit.savedViews')}>
          <div className={GROUP_LABEL}>{t('audit.savedViews')}</div>
          {views.length === 0 && <p className="px-2 pb-1 text-sm text-muted-foreground">{t('audit.noSavedViews')}</p>}
          <ul className="flex list-none flex-col">
            {views.map(v => (
              <li key={v.name} className="flex items-center gap-1">
                <Button type="button" variant="ghost" size="sm" title={v.name}
                  onClick={() => apply(normalizeFilters(v.filters))}
                  className="h-10 min-w-0 flex-1 justify-start font-normal sm:h-8">
                  <span className="truncate">{v.name}</span>
                </Button>
                <Button type="button" variant="ghost" size="icon-sm" className="size-10 text-muted-foreground hover:text-destructive sm:size-8"
                  onClick={() => remove(v.name)} aria-label={t('a11y.rowAction', v.name, t('audit.deleteView'))}>
                  <Trash2 />
                </Button>
              </li>
            ))}
          </ul>
          <div className="mt-2 flex gap-1.5 px-1">
            <Input className="h-10 min-w-0 flex-1 md:h-8" placeholder={t('audit.viewNamePh')} aria-label={t('audit.viewNamePh')}
              value={name} onChange={e => setName(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); save() } }} />
            <Button type="button" size="sm" className="h-10 md:h-8" disabled={!name.trim()} onClick={save}>{t('audit.saveView')}</Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}
