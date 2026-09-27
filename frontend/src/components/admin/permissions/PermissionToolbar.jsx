import { Search, X, ChevronsUpDown, ChevronsDownUp, ShieldAlert, CircleDot } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Toggle } from '@/components/shadcn/toggle'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { ACTIONS } from './permissionModel.js'

/**
 * Matris araç çubuğu: kaynak arama (InputGroup), gösterilen yetki türleri (ToggleGroup, en az biri açık kalır),
 * "yalnız kaydedilmemişler" / "yalnız hassas" süzgeçleri (Toggle) ve tüm grupları aç/kapat.
 * Mobil-önce: telefonda her denetim tam genişlik ve 40 px; ≥640 px'te tek satıra sarar.
 */
export default function PermissionToolbar({
  query, onQuery, kinds, onKinds, canEdit, pendingCount, onlyPending, onOnlyPending,
  onlySensitive, onOnlySensitive, allOpen, onToggleAll, disableGroups,
}) {
  const t = useT()
  return (
    <div data-slot="perm-toolbar" className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
      <InputGroup className="h-10 w-full sm:h-9 sm:w-64 lg:w-72">
        <InputGroupInput type="search" value={query} onChange={(e) => onQuery(e.target.value)}
          placeholder={t('perm.searchResources')} aria-label={t('perm.searchLabel')} />
        <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        {query && (
          <InputGroupAddon align="inline-end">
            <InputGroupButton size="icon-sm" aria-label={t('perm.clearSearch')} onClick={() => onQuery('')}>
              <X aria-hidden="true" />
            </InputGroupButton>
          </InputGroupAddon>
        )}
      </InputGroup>

      <ToggleGroup type="multiple" variant="outline" value={kinds} role="group" aria-label={t('perm.kindsLabel')}
        onValueChange={(v) => { if (v.length) onKinds(v) }}
        className="w-full sm:w-fit">
        {ACTIONS.map(({ key, Icon, labelKey }) => (
          <ToggleGroupItem key={key} value={key} className="h-10 flex-1 gap-1.5 px-3 text-xs sm:h-9 sm:flex-none">
            <Icon aria-hidden="true" className="size-3.5" />{t(labelKey)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>

      <div className="flex flex-wrap items-center gap-2 [&>*]:flex-1 sm:[&>*]:flex-none">
        {canEdit && (
          <Toggle variant="outline" pressed={onlyPending} onPressedChange={onOnlyPending}
            disabled={!onlyPending && pendingCount === 0} className="h-10 px-3 text-xs sm:h-9">
            <CircleDot aria-hidden="true" className="size-3.5" />
            {t('perm.filterPending')}
            {pendingCount > 0 && <Badge variant="warning" className="tabular-nums">{pendingCount}</Badge>}
          </Toggle>
        )}
        <Toggle variant="outline" pressed={onlySensitive} onPressedChange={onOnlySensitive} className="h-10 px-3 text-xs sm:h-9">
          <ShieldAlert aria-hidden="true" className="size-3.5" />
          {t('perm.filterSensitive')}
        </Toggle>
      </div>

      <Button type="button" variant="ghost" onClick={onToggleAll} disabled={disableGroups}
        className="h-10 self-start sm:ml-auto sm:h-9 sm:self-auto">
        {allOpen ? <ChevronsDownUp aria-hidden="true" /> : <ChevronsUpDown aria-hidden="true" />}
        {allOpen ? t('perm.collapseAll') : t('perm.expandAll')}
      </Button>
    </div>
  )
}
