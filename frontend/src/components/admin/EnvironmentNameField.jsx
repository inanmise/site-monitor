import { X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { EnvBadge } from './releases/DeployBadges.jsx'
import { Input } from '@/components/shadcn/input'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'

/** Genel Ayarlar kataloğundaki anahtar — backend `BuildInfo.ENV_KEY`. */
export const ENV_KEY = 'site.monitor.environment'
/** Hazır seçenekler; serbest değer de yazılabilir. */
export const ENV_PRESETS = ['dev', 'staging', 'prod']
/** Backend `BuildInfo.ENV_NAME` ile AYNI desen (sunucu da 400 ile reddeder; burada anında geri bildirim). */
const ENV_NAME_RE = /^[a-z0-9-]{1,40}$/

/** Boş geçerlidir: ayarı kaldırır, Helm/APP_ENVIRONMENT'e döner. */
export function isValidEnvName(v) {
  return v === '' || ENV_NAME_RE.test(v)
}

/**
 * Ayarlar → Genel Ayarlar → "Ortam adı" (2026-09-29). Serbest metin + hazır seçenekler (dev / staging / prod) +
 * "Boşalt" (Helm değerine dön) + ŞU AN GEÇERLİ ad ve kaynağı ("Ayarlardan" / "Helm (APP_ENVIRONMENT)" / "Otomatik")
 * — kaynak, kaydeden sunucunun katalog yanıtındaki `effective` / `effective_source` alanlarından gelir.
 *
 * Bağlar ui/Field render-prop'undan (`id`, `describedBy`, `invalid`); biçim hatası Field'ın hata yuvasında
 * çizilir (GeneralSettings). Yazarken küçük harfe çevrilir (sunucu büyük harfi sessizce değiştirmez, reddeder).
 * `disabled`: kapsamlı müdür (GLOBAL_ONLY kalem, `read_only`) — hazır seçenekler ve Boşalt da kilitlenir.
 */
export default function EnvironmentNameField({ id, describedBy, invalid, value, onChange, disabled, effective, source }) {
  const t = useT()
  const v = value ?? ''
  return (
    <div data-slot="env-name-field" className="flex min-w-0 flex-col gap-2">
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
        <Input id={id} aria-describedby={describedBy} aria-invalid={invalid || undefined}
          value={v} disabled={disabled} maxLength={40} autoComplete="off" autoCapitalize="none" spellCheck={false}
          placeholder={t('general.env.placeholder')}
          className="font-mono sm:w-56"
          onChange={(e) => onChange(e.target.value.toLowerCase())} />
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <ToggleGroup type="single" variant="outline" size="sm" role="group" aria-label={t('general.env.presets')}
            value={ENV_PRESETS.includes(v) ? v : ''} disabled={disabled}
            onValueChange={(p) => { if (p && p !== v) onChange(p) }}>
            {ENV_PRESETS.map((p) => (
              <ToggleGroupItem key={p} value={p} role="button" aria-pressed={p === v} aria-checked={undefined}
                className="px-3 font-mono text-xs text-foreground pointer-coarse:h-10">
                {p}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          {v !== '' && !disabled && (
            <Button type="button" variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground pointer-coarse:h-10"
              onClick={() => onChange('')}>
              <X aria-hidden="true" /> {t('general.env.clear')}
            </Button>
          )}
        </div>
      </div>
      {effective && (
        <div data-slot="env-effective" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          <span>{t('general.env.current')}</span>
          <EnvBadge env={effective} />
          <Badge variant="outline" data-source={source || 'auto'} className="rounded-md px-1.5 font-medium">
            {t('general.env.source.' + (source || 'auto'))}
          </Badge>
        </div>
      )}
    </div>
  )
}
