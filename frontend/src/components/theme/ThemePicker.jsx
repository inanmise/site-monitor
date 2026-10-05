import { Check, Palette } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import ThemeSwatch from './ThemeSwatch.jsx'
import { themeName, schemeLabel } from './themeLabels.js'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Tema seçeneği satırı (menü radyosu) — renk örneği + ad + şema ipucu + etkin olanda onay işareti. Radix'in sol
 * nokta göstergesi gizlenir (yerini sağdaki onay alır); seçim menüyü KAPATMAZ (değişiklik anında görülsün, kullanıcı
 * temaları art arda deneyebilsin). Dokunmatikte 40 px satır.
 */
export function ThemeRadioItems({ className }) {
  const t = useT()
  const { theme, themes, setTheme } = useTheme()
  return (
    <DropdownMenuRadioGroup value={theme} onValueChange={(v) => v && setTheme(v)} className={className}>
      {themes.map((th) => {
        const active = th.id === theme
        return (
          <DropdownMenuRadioItem key={th.id} value={th.id} data-slot="theme-option" data-theme-id={th.id}
            onSelect={(e) => e.preventDefault()}
            className="min-h-10 gap-2.5 rounded-md py-1 pl-2 [&>span:first-child]:hidden">
            <ThemeSwatch id={th.id} />
            {/* Aradaki boşluk düğümü erişilebilir adı "Crucible Koyu şema" yapar (esnek kapta görünmez) */}
            <span className="flex min-w-0 flex-1 flex-col leading-tight">
              <span className="truncate font-medium">{themeName(t, th.id)}</span>{' '}
              <span className="truncate text-xs text-muted-foreground">{schemeLabel(t, th.scheme)}</span>
            </span>
            {active && <Check aria-hidden="true" data-slot="theme-option-check" className="size-4 text-primary" />}
          </DropdownMenuRadioItem>
        )
      })}
    </DropdownMenuRadioGroup>
  )
}

/**
 * Kullanıcı tema seçicisi (2026-10-05) — yalnız yöneticinin AÇIK bıraktığı temalar listelenir. Telefonda üst çubukta
 * ikon düğme (40 px); masaüstünde aynı liste kullanıcı menüsünün "Tema" alt menüsündedir (NavUser).
 * Klavye: tetik Enter/Space/Aşağı ile açılır, oklar gezinir, Enter seçer, Esc kapatır (Radix DropdownMenu).
 */
export default function ThemePicker({ className, align = 'end' }) {
  const t = useT()
  const { theme } = useTheme()
  const name = themeName(t, theme)
  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="ghost" size="icon" data-slot="theme-picker-trigger"
          aria-label={t('theme.picker.label', name)} title={t('theme.picker.label', name)}
          className={cn('size-10 [&_svg]:size-5', className)}>
          <Palette aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align={align} sideOffset={6} collisionPadding={8} data-slot="theme-picker"
        className="z-(--z-menu) w-64 max-w-[calc(100vw-1rem)] p-1">
        <DropdownMenuLabel className="px-2 pt-1.5 pb-0.5 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">
          {t('nav.theme')}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <ThemeRadioItems />
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
