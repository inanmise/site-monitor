import { Users, Building2, Layers } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { SCOPE_MINE, SCOPE_OTHERS, SCOPE_ALL, normalizeIncidentScope } from './incidentsModel.js'

const OPTIONS = [
  { value: SCOPE_MINE, Icon: Users },
  { value: SCOPE_OTHERS, Icon: Building2 },
  { value: SCOPE_ALL, Icon: Layers },
]

/**
 * "Takımımın olayları | Diğer ekiplerin olayları | Tümü" süzgeci (org geneli salt okunur Olaylar, 2026-09-28).
 *
 * <p>Yalnız sunucu `scope_counts` döndürdüğünde çizilir (ayar açık + çağıran global görüntüleyici değil) — sayılar
 * sunucunun sayfa süzgeçleriyle (durum, kök neden, arama, tarih) aynı. Tek aktif seçimli düğme grubu (`aria-pressed`),
 * kök neden çipleriyle aynı hap biçimi; `flex-wrap` telefonda satır kırar (yatay taşma yok), dokunma hedefi 40 px.
 * Kapsam dışı seçim yapılınca altta salt okunur notu durur. Test kancası `data-slot="incident-scope"`.
 */
export default function IncidentScopeFilter({ value, onChange, counts, className }) {
  const t = useT()
  const current = normalizeIncidentScope(value)
  const label = { [SCOPE_MINE]: t('incov.scope.mine'), [SCOPE_OTHERS]: t('incov.scope.others'), [SCOPE_ALL]: t('incov.scope.all') }
  return (
    // Not, geniş ekranda çiplerle AYNI satırda durur (kapsam değişince içerik aşağı itilmez); telefonda alta sarar.
    <div className={cn('flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5', className)}>
      <div data-slot="incident-scope" role="group" aria-label={t('incov.scope.label')} className="flex flex-wrap items-center gap-1.5">
        {OPTIONS.map(({ value: v, Icon }) => {
          const on = current === v
          const n = counts?.[v]
          return (
            <Button key={v} type="button" variant={on ? 'default' : 'outline'} size="sm" aria-pressed={on} data-scope={v}
              className="h-8 shrink-0 gap-1.5 rounded-full px-3 font-medium pointer-coarse:h-10"
              onClick={() => { if (!on) onChange?.(v) }}>
              <Icon aria-hidden="true" />{label[v]}
              {n != null && (
                <Badge variant={on ? 'secondary' : 'outline'} data-slot="scope-count" className="h-5 px-1.5 text-[0.85em] tabular-nums">{n}</Badge>
              )}
            </Button>
          )
        })}
      </div>
      {current !== SCOPE_MINE && (
        <p data-slot="incident-scope-note" className="m-0 min-w-0 flex-1 basis-64 text-xs text-muted-foreground">{t('incov.scope.readOnlyNote')}</p>
      )}
    </div>
  )
}
