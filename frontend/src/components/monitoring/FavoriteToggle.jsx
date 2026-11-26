import { useEffect } from 'react'
import { Star, StarOff } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useUserPrefs } from '../../hooks/useUserPrefs.js'
import { MAX_FAVORITES, MONITOR_TYPES, favoriteDisplayName } from '../../hooks/userPrefsModel.js'
import { useToast } from '../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Favori izleme anahtarı (2026-10-02, öneri 23) — dokuz izleme türünün kartında (MonitorCardTop, `noc` bağlamından) ve
 * detay penceresinin başlığında (MonitorDetailModal) TEK bileşen. shadcn ghost Button + `aria-pressed`; ad eylemi söyler:
 * "Favorilere ekle: <ad>" / "Favorilerden çıkar: <ad>".
 *
 * <p>Görünüm: favori değilken soluk Star; favoriyken dolu amber Star — ince işaretçide (fare) üzerine gelince/odakta
 * StarOff'a döner ("çıkar" eylemi). Dokunmatikte yapışkan hover olmasın diye değişim yalnız `pointer-fine`.
 *
 * <p>Tercihler yüklenmeden (giriş öncesi, GET hatası, sağlayıcısız yalıtılmış test) HİÇ çizilmez — "yeni seçeneği
 * kullanmayan için hiçbir şey değişmez" ve sunucuyu bilmeden yazmak başka cihazın favorilerini ezerdi. Dış bileşen
 * yalnız tercih bağlamını okur (sağlayıcısız ağaçta da güvenli); düğme — Toast/i18n kancalarıyla — yalnız hazırken bağlanır.
 * Varsayılan kart sırası (utils/monitorSort.js) favoriden ETKİLENMEZ: favori bir süzgeç/kısayoldur.
 *
 * <p>Ad (H paketi inceleme bulgusu 4): TEK kaynak {@link favoriteDisplayName} — izlemenin kendi `name` alanı, yoksa `name`
 * prop'u (kartta satır etiketi, başlıkta adres). Kart da başlık da aynı adı saklar (eskiden kart URL / host saklıyordu →
 * palet adı değil adresi gösteriyordu, ad alanı olan türlerde yeniden adlandırma hiç görünmüyordu).
 *
 * <p>Ad eşitleme (H paketi 2026-11-26): KART (`size="card"`, güncel listeden çizilir) favorinin saklı adı güncel addan farklıysa
 * onu günceller — palet favoriyi kimlikle açar, adı buradan gösterir. Yalnız kart eşitler (başlığın `noc.monitor`'u açılış
 * anının kopyası olabilir; iki kaynak birbirini ezip her açılışta yazım yapmasın); ad aynıysa yazım YOK.
 *
 * Test kancası: `data-slot="favorite-toggle"` (+ `data-on`).
 */
export default function FavoriteToggle({ type, id, monitor = null, name, size = 'card', className }) {
  const prefs = useUserPrefs()
  if (!prefs.ready || !MONITOR_TYPES.includes(type) || id == null || id === '') return null
  return <FavoriteButton prefs={prefs} type={type} id={id} name={favoriteDisplayName(monitor, name)} size={size} className={className} />
}

function FavoriteButton({ prefs, type, id, name, size, className }) {
  const t = useT()
  const toast = useToast()
  const current = String(name || '').trim()
  const label = current || `#${id}`
  const on = prefs.isFavorite(type, id)
  const stored = on ? (prefs.favorites.find((f) => f.type === type && f.id === Number(id))?.name ?? null) : null
  const renameFavorite = prefs.renameFavorite
  useEffect(() => {
    if (size !== 'card' || !on || !current || stored === current.slice(0, 300)) return
    renameFavorite?.({ type, id: Number(id), name: current })
  }, [size, on, current, stored, type, id, renameFavorite])
  const text = on ? t('fav.remove', label) : t('fav.add', label)
  const onClick = (e) => {
    e.stopPropagation()
    const r = prefs.toggleFavorite({ type, id: Number(id), name: label })
    if (r?.full) toast.error(t('fav.full', MAX_FAVORITES))
  }
  return (
    <Button type="button" variant="ghost" size={size === 'header' ? 'icon-sm' : 'icon-xs'}
      data-slot="favorite-toggle" data-on={on ? 'true' : undefined}
      aria-pressed={on} aria-label={text} title={text} onClick={onClick}
      className={cn('group/fav shrink-0 text-muted-foreground hover:text-amber-600 dark:hover:text-amber-400 pointer-coarse:size-10',
        on && 'text-amber-500 dark:text-amber-400', className)}>
      {on ? (
        <>
          <Star aria-hidden="true" className="fill-current pointer-fine:group-hover/fav:hidden pointer-fine:group-focus-visible/fav:hidden" />
          <StarOff aria-hidden="true" className="hidden pointer-fine:group-hover/fav:inline-block pointer-fine:group-focus-visible/fav:inline-block" />
        </>
      ) : <Star aria-hidden="true" />}
    </Button>
  )
}
