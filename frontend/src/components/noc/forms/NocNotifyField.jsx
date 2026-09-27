import { useId } from 'react'
import { Headset, MoonStar, Settings } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import {
  Field as ShadcnField, FieldContent, FieldDescription, FieldLabel, FieldTitle,
} from '@/components/shadcn/field'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'
import {
  activeGroupsOf, effectiveSelection, isTypeDisabled, listedGroups, shownSelection, toggleGroupId,
} from './nocFormModel.js'
import { useNocFormOptions } from './useNocFormOptions.js'

/**
 * "7/24 izleme ekibine bildir" — dokuz izleme formu + sertifika envanter formunun ORTAK alanı (2026-09-27; sözleşme
 * `.migration/noc/CONTRACT.md`). Form ızgarasında iki sütunu kaplar.
 *
 * Düzen (shadcn Field "seçim kartı"): başlık + tek satır açıklama + Switch TEK bir etiket içinde — kartın tamamı dokunma
 * hedefi (≥44 px), açıkken çerçeve vurgulu. Varsayılan KAPALI (yeni izleme); düzenlemede kayıtlı değer.
 *  - Tür yönetici tarafından kapatılmışsa (`disabled_types`) uyarı bandı — anahtar KULLANILABİLİR kalır (tür yeniden
 *    açılınca izlemenin tercihi geçerli olur).
 *  - Hiç aktif 7/24 grubu yoksa bilgi satırı: GLOBAL yönetici için Ayarlar → 7/24 bağlantısı (grup tanımlayabilen
 *    tek kişi), diğerlerine — kapsamlı müdür dâhil: 7/24 ayarları ona salt okunur — "yöneticinize başvurun".
 *  - Açıkken ve BİRDEN ÇOK aktif grup varsa grup seçici (onay kutuları; varsayılanlar önseçili). Tek aktif grup varsa
 *    seçici yerine "E-postalar X grubuna gider" satırı.
 *
 * Değer sözleşmesi: `onChange(patch)` — `{ nocNotify }` ya da `{ nocGroupIds }` (çağıran kendi form anahtarlarına
 * eşler; envanter formu snake_case tutar). `groupIds` boş dizi = varsayılan gruplar (sunucuda null).
 *
 * Test kancaları: `data-slot="noc-notify-field"` (+ `data-on`), `noc-type-off`, `noc-no-groups`, `noc-group-picker`
 * (+ `data-explicit`), `noc-group-option` (+ `data-group-id`), `noc-one-group`.
 */
export default function NocNotifyField({
  type, checked = false, groupIds = [], onChange, canOpenSettings, disabled = false, className,
}) {
  const t = useT()
  // Ayarlar → 7/24 bağlantısı YALNIZ global yöneticiye (çağıran `globalAdmin` geçirir): kapsamlı müdür rol ADMIN'dir ve
  // izin matrisinde ayar satırını görür, ama 7/24 grubu YAZAMAZ — bağlantı onu salt okunur sayfaya gönderirdi.
  // Bilinmiyorsa bağlantı YOK (güvenli taraf: "yöneticinize başvurun").
  const settingsLink = canOpenSettings === true
  const { groups, disabledTypes } = useNocFormOptions()
  const id = useId()
  const titleId = `${id}-title`
  const descId = `${id}-desc`
  const pickerId = `${id}-groups`

  const on = !!checked
  const active = activeGroupsOf(groups)
  const known = Array.isArray(groups)
  const typeOff = isTypeDisabled(type, disabledTypes)
  const noGroups = known && active.length === 0
  const typeLabel = t(`noc.type.${type}`)

  return (
    <div data-slot="noc-notify-field" data-on={on ? 'true' : 'false'}
      className={cn('flex min-w-0 flex-col gap-2.5 sm:col-span-2', className)}>
      <FieldLabel htmlFor={id} className="w-full cursor-pointer has-[:disabled]:cursor-default">
        <ShadcnField orientation="horizontal" role={undefined} data-disabled={disabled ? 'true' : undefined}
          className="min-h-11 items-center gap-3">
          <FieldContent className="gap-1">
            <FieldTitle id={titleId} className="gap-1.5 font-semibold">
              <Headset aria-hidden="true" className="size-4 shrink-0 text-primary" />
              {t('nocf.title')}
            </FieldTitle>
            <FieldDescription id={descId} className="text-xs leading-snug">{t('nocf.hint')}</FieldDescription>
          </FieldContent>
          <Switch id={id} checked={on} disabled={disabled} aria-labelledby={titleId} aria-describedby={descId}
            onCheckedChange={(v) => onChange?.({ nocNotify: v === true })} />
        </ShadcnField>
      </FieldLabel>

      {typeOff && (
        <div data-slot="noc-type-off">
          <AlertBanner tone="warning" icon={MoonStar} className="mb-0" title={t('nocf.typeOffTitle')}>
            {t('nocf.typeOffBody', typeLabel === `noc.type.${type}` ? type : typeLabel)}
          </AlertBanner>
        </div>
      )}

      {noGroups && (
        <p data-slot="noc-no-groups" className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
          <span>{t('nocf.noGroups')}</span>
          {settingsLink ? (
            <Button type="button" variant="link" size="xs" className="h-auto p-0 text-xs pointer-coarse:min-h-10"
              onClick={() => navigateTo('settings', { sec: 'noc' })}>
              <Settings aria-hidden="true" />{t('nocf.noGroupsCta')}
            </Button>
          ) : <span>{t('nocf.noGroupsAsk')}</span>}
        </p>
      )}

      {on && active.length === 1 && (
        <p data-slot="noc-one-group" className="text-xs text-muted-foreground">{t('nocf.oneGroup', active[0].name)}</p>
      )}

      {on && active.length > 1 && (
        <GroupPicker labelId={pickerId} groups={groups} ids={groupIds} disabled={disabled}
          onChange={(next) => onChange?.({ nocGroupIds: next })} />
      )}
    </div>
  )
}

/** Grup seçici — onay kutuları (grup sayısı küçük: tipik 1–5). Son işaretli grup kaldırılamaz (bkz. toggleGroupId). */
function GroupPicker({ labelId, groups, ids, disabled, onChange }) {
  const t = useT()
  // Sunucu gibi: kayıtlı seçimin hepsi pasif/silinmişse varsayılanlar izlenir (bkz. effectiveSelection)
  const explicit = effectiveSelection(ids, groups).length > 0
  const shown = shownSelection(ids, groups)
  const rows = listedGroups(ids, groups)
  return (
    <div role="group" aria-labelledby={labelId} data-slot="noc-group-picker" data-explicit={explicit ? 'true' : 'false'}
      className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-muted/30 px-3.5 py-2.5">
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <span id={labelId} className="text-sm font-medium">{t('nocf.groupsLabel')}</span>
        {explicit ? (
          <Button type="button" variant="link" size="xs" disabled={disabled} className="ml-auto h-auto p-0 text-xs pointer-coarse:min-h-10"
            onClick={() => onChange([])}>
            {t('nocf.useDefaults')}
          </Button>
        ) : (
          <Badge variant="secondary" data-slot="noc-following-defaults" className="text-[11px]">{t('nocf.followingDefaults')}</Badge>
        )}
      </div>
      <div className="grid min-w-0 grid-cols-1 gap-x-4 sm:grid-cols-2">
        {rows.map((g) => {
          const gid = Number(g.id)
          const isOn = shown.includes(gid)
          const last = isOn && shown.length === 1
          return (
            <GroupOption key={gid} group={g} checked={isOn} disabled={disabled} locked={last}
              title={last ? t('nocf.lastGroup') : undefined}
              onToggle={() => onChange(toggleGroupId(ids, gid, groups))} />
          )
        })}
      </div>
      <p className="text-xs text-muted-foreground">{explicit ? t('nocf.groupsExplicitHint') : t('nocf.groupsDefaultHint')}</p>
    </div>
  )
}

/**
 * `locked`: son işaretli grup — yalnız kutu kilitlenir; etiket SOLUKLAŞMAZ (seçili grubun "pasif" gibi görünmemesi için),
 * neden `title` + seçicinin alt satırında. `disabled`: tüm alan kilitli (form yazılamaz) → etiket de soluk.
 */
function GroupOption({ group, checked, disabled, locked = false, title, onToggle }) {
  const t = useT()
  const id = useId()
  return (
    <ShadcnField orientation="horizontal" role={undefined} data-slot="noc-group-option" data-group-id={group.id}
      data-disabled={disabled ? 'true' : undefined} title={title}
      className="min-h-10 min-w-0 gap-2 sm:min-h-8 sm:pointer-coarse:min-h-10">
      <Checkbox id={id} checked={checked} disabled={disabled || locked} onCheckedChange={onToggle} />
      {/* Kilitli (son) grupta Label'ın `peer-disabled` soluklaştırması ezilir — yalnız kutu kilitli görünür */}
      <FieldLabel htmlFor={id} className={cn('min-w-0 flex-wrap items-center gap-1.5 font-normal',
        locked && !disabled && 'peer-disabled:cursor-default peer-disabled:opacity-100')}>
        <span className="min-w-0 break-words">{group.name}</span>
        {group.is_default && <Badge variant="outline" className="text-[10.5px]">{t('nocf.groupDefault')}</Badge>}
        {group.active !== true && <Badge variant="secondary" className="text-[10.5px]">{t('nocf.groupInactive')}</Badge>}
      </FieldLabel>
    </ShadcnField>
  )
}
