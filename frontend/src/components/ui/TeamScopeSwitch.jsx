import { Users, Building2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import SegmentedControl from './SegmentedControl.jsx'

/** Kapsam değerleri — sunucu sözleşmesiyle aynı (`scope=mine|all`). */
export const SCOPE_MINE = 'mine'
export const SCOPE_ALL = 'all'
/** Bilinmeyen/boş değer → 'mine' (URL/localStorage'dan gelen her şey buradan geçer). */
export function normalizeScope(v) { return v === SCOPE_ALL ? SCOPE_ALL : SCOPE_MINE }

/**
 * "Takımlarım | Tüm takımlar" anahtarı (org geneli görünürlük, kullanıcı kararı 2026-09-26).
 *
 * Envanter, Tüm Sertifikalar ve Durum İzleme aynı bileşeni kullanır. YALNIZ sunucu `visible_to_all: true`
 * dediğinde çizilir (ayar kapalıyken anahtar yok, liste bugünkü gibi). Görünüm ui/SegmentedControl
 * (shadcn ToggleGroup) — tek aktif seçim, `aria-pressed`'li düğmeler. Test kancası `data-slot="team-scope-switch"`.
 */
export default function TeamScopeSwitch({ value, onChange, visible = false, className = '' }) {
  const t = useT()
  if (!visible) return null
  return (
    <div data-slot="team-scope-switch" className={className}>
      <SegmentedControl ariaLabel={t('scope.label')} value={normalizeScope(value)} onChange={onChange}
        options={[
          { value: SCOPE_MINE, label: t('scope.mine'), icon: Users },
          { value: SCOPE_ALL, label: t('scope.all'), icon: Building2 },
        ]} />
    </div>
  )
}
