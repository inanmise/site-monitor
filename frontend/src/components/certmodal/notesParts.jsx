import { useEffect } from 'react'
import { CalendarClock, Rocket, Siren, StickyNote } from 'lucide-react'
import { avatarBg, initialsOf } from '../ui/UserBadge.jsx'
import { useUserDirectory } from '../ui/UserDirectory.jsx'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/** Notlar sekmesinin küçük ortak parçaları: kategori simgesi/tonu, yazar avatarı, Escape koruması. */

export const CAT_ICON = { NOTE: StickyNote, DEPLOYMENT: Rocket, INCIDENT: Siren, RENEWAL: CalendarClock }

/** Kategori rozet tonu (eski sol renk şeridi `categoryStripe`'ın yerine — şerit YOK, rozet renkli). */
export const CAT_TONE = {
  DEPLOYMENT: 'bg-primary/10 text-primary dark:bg-primary/20',
  INCIDENT: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  RENEWAL: 'bg-success/15 text-success dark:bg-success/20',
  NOTE: 'bg-muted text-muted-foreground',
}

export function CategoryBadge({ category, label, muted = false, className }) {
  const Icon = CAT_ICON[category] ?? StickyNote
  return (
    <Badge variant="secondary" data-slot="cert-note-category" data-category={category}
      className={cn('gap-1 font-semibold', muted ? CAT_TONE.NOTE : CAT_TONE[category] ?? CAT_TONE.NOTE, className)}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  )
}

/**
 * Zaman çizelgesindeki yazar avatarı — shadcn Avatar; kullanıcı dizininden fotoğraf (varsa), yoksa baş harfler
 * (ui/UserBadge ile aynı ton ve baş harf kuralı). Adı görünür metin olarak başlıkta durduğu için avatar dekoratif.
 */
export function NoteAvatar({ username, name, className }) {
  const dir = useUserDirectory()
  const id = username ? dir.lookup(username)?.id : null
  const ident = username || name || '?'
  return (
    <Avatar aria-hidden="true" className={cn('size-8 max-sm:size-7', className)}>
      {id != null && <AvatarImage src={`/api/users/${id}/photo`} alt="" className="object-cover" />}
      <AvatarFallback className="text-[11px] font-bold text-white" style={{ background: avatarBg(ident) }}>
        {initialsOf(name, username)}
      </AvatarFallback>
    </Avatar>
  )
}

/**
 * Yazı alanında Escape KORUMASI. Radix Dialog Escape'i belge düzeyinde, YAKALAMA evresinde dinler — yazı alanının kendi
 * onKeyDown'ı ondan SONRA çalışır, bu yüzden notu yazarken basılan Escape tüm pencereyi kapatıp taslağı siliyordu.
 * Pencere (window) yakalaması belgeninkinden ÖNCE gelir: odak `ref` içindeyken Escape burada durdurulur ve `onEscape`
 * çağrılır (düzenlemede: iptal; yeni notta: yazı alanından çık — ikinci Escape pencereyi kapatır).
 */
export function useEscapeGuard(ref, onEscape, active = true) {
  useEffect(() => {
    if (!active) return undefined
    const onKey = (e) => {
      if (e.key !== 'Escape' || !ref.current || !ref.current.contains(e.target)) return
      e.stopPropagation()
      e.preventDefault()
      onEscape?.(e)
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [ref, onEscape, active])
}

/** Kısayol ipucu: Apple aygıtlarında ⌘, diğerlerinde Ctrl. */
export function isMacLike() {
  try { return /Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent || '') } catch { return false }
}

/** Ctrl+Enter / ⌘+Enter. */
export function isSubmitCombo(e) {
  return e.key === 'Enter' && (e.ctrlKey || e.metaKey)
}
