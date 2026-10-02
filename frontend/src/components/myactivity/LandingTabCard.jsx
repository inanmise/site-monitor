import { useId } from 'react'
import { House } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useUserPrefs } from '../../hooks/useUserPrefs.js'
import { resolveLandingTab } from '../../hooks/userPrefsModel.js'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'

/**
 * "Açılış sekmesi" (2026-10-02, öneri 23) — Etkinliklerim yan kartı (push tercihlerinin yanında, kullanıcının kişisel
 * ayarları). Seçenekler App'ten gelir: kullanıcının GERÇEKTEN açabildiği sekmeler (utils/landingTabs.js — App.jsx / Nav
 * görünürlük kurallarıyla aynı). Varsayılan "Pano (varsayılan)" = saklanan değer yok → bugünkü davranış.
 *
 * <p>Seçim kişisel tercih belgesine yazılır (iyimser; 1 sn toplu PUT). App yalnız adreste `?tab=` (ya da başka bir derin
 * bağlantı paramı) YOKKEN ve tercihler yüklendikten sonra uygular — bağlantıyla gelen sayfa her zaman kazanır. Tercihler
 * yüklenemediyse seçici kapalıdır (yazmak sunucudaki değeri bilmeden ezerdi).
 *
 * Test kancası: `data-slot="landing-tab"`.
 */
export default function LandingTabCard({ options = [] }) {
  const t = useT()
  const prefs = useUserPrefs()
  const selectId = useId()
  const hintId = useId()
  const value = resolveLandingTab(prefs.landingTab, options.map((o) => o.id)) ?? ''
  return (
    <Card data-slot="landing-tab" className="min-w-0 gap-3 py-4 shadow-none">
      <CardHeader className="px-4">
        <CardTitle className="flex items-center gap-2 text-sm">
          <House aria-hidden="true" className="size-4 text-primary" />{t('landing.title')}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-1.5 px-4">
        <Label htmlFor={selectId} className="leading-snug">{t('landing.label')}</Label>
        <div className="min-w-0 [&>*]:w-full">
          <NativeSelect id={selectId} value={value} disabled={!prefs.ready} aria-describedby={hintId}
            onChange={(e) => prefs.setLandingTab(e.target.value || null)} className="w-full pointer-coarse:h-10">
            <NativeSelectOption value="">{t('landing.default')}</NativeSelectOption>
            {options.map((o) => <NativeSelectOption key={o.id} value={o.id}>{t(o.labelKey)}</NativeSelectOption>)}
          </NativeSelect>
        </div>
        <span id={hintId} className="text-xs leading-snug text-muted-foreground">
          {prefs.status === 'failed' ? t('landing.unavailable') : t('landing.hint')}
        </span>
      </CardContent>
    </Card>
  )
}
