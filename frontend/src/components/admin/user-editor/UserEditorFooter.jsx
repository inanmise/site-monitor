import { ArrowRight, CircleAlert, ListChecks, Pencil, Save } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { FIELD_LABEL } from './userEditorModel.js'
import { Button } from '@/components/shadcn/button'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'

/** Değişiklik özetindeki değer → okunur metin (rol etiketleri, Aktif/Pasif, takım adları; boş = "(boş)"). */
function displayValue(t, key, v, teamMap) {
  if (key === 'active') return v ? t('usr.active') : t('usr.inactive')
  if (key === 'team_ids') {
    const ids = Array.isArray(v) ? v : []
    return ids.length ? ids.map((id) => teamMap[id] || `#${id}`).join(', ') : t('ued.emptyValue')
  }
  if (key === 'org_role') return v ? t('usr.orgRoleVal.' + v) : t('usr.orgRoleNone')
  if (v == null || String(v).trim() === '') return t('ued.emptyValue')
  return String(v)
}

/**
 * Yapışkan altlık (2026-10-02, kullanıcı isteği): solda durum (hatalı alan sayısı · "N değişiklik" — dokununca
 * eski → yeni özetini açan Popover, gizli değer [şifre] listelenmez · "Değişiklik yok"), sağda İptal + Kaydet
 * (görüntülemede Kapat + Düzenle). Kaydet: kayıt sürerken ve düzenlemede değişiklik yokken kapalı; hatalı form
 * Kaydet'e basınca alanların yanında açıklanır (useFormErrors) — düğme bu yüzden geçersiz formda KAPANMAZ.
 * Telefonda düğmeler satırı eşit paylaşır (40 px), durum satırı üstte.
 */
export default function UserEditorFooter({ ed }) {
  const t = useT()
  const { isAdd, isView, changes, saving, errorCount } = ed
  const status = (() => {
    if (isView) return null
    if (isAdd) {
      return ed.dirty
        ? <span data-slot="ued-dirty" className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="size-2 rounded-full bg-amber-500" />{t('inc.unsaved')}</span>
        : <span>{t('ued.requiredNote')}</span>
    }
    if (changes.length === 0) return <span data-slot="ued-dirty" data-count="0">{t('ued.noChanges')}</span>
    return (
      <Popover>
        <PopoverTrigger asChild>
          <Button type="button" variant="outline" size="sm" data-slot="ued-dirty" data-count={changes.length}
            className="h-8 gap-1.5 border-amber-500/40 font-medium max-sm:h-10">
            <span aria-hidden="true" className="size-2 rounded-full bg-amber-500" />
            {changes.length === 1 ? t('ued.changesOne') : t('ued.changesMany', changes.length)}
            <ListChecks aria-hidden="true" className="text-muted-foreground" />
          </Button>
        </PopoverTrigger>
        <PopoverContent align="start" side="top" data-slot="ued-changes"
          className="z-(--z-menu) w-[min(24rem,calc(100vw-2rem))] p-0">
          <div className="border-b px-3 py-2">
            <p className="m-0 text-sm font-semibold">{t('ued.changesTitle')}</p>
            <p className="m-0 text-xs text-muted-foreground">{t('ued.changesHint')}</p>
          </div>
          <ul className="m-0 flex max-h-[min(50vh,20rem)] list-none flex-col gap-2 overflow-y-auto p-3">
            {changes.map((c) => (
              <li key={c.key} data-slot="ued-change-row" data-field={c.key} className="flex min-w-0 flex-col gap-0.5 text-sm">
                <span className="text-xs font-semibold text-muted-foreground">{t(FIELD_LABEL[c.key] || c.key)}</span>
                <span className="flex min-w-0 flex-wrap items-center gap-1.5 [overflow-wrap:anywhere]">
                  <span className="text-muted-foreground line-through decoration-muted-foreground/60">{displayValue(t, c.key, c.from, ed.teamMap)}</span>
                  <ArrowRight aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
                  <span className="font-medium">{displayValue(t, c.key, c.to, ed.teamMap)}</span>
                </span>
              </li>
            ))}
          </ul>
        </PopoverContent>
      </Popover>
    )
  })()

  return (
    <div data-slot="ued-footer" className="flex w-full flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground" aria-live="polite">
        {errorCount > 0 && (
          <span data-slot="ued-error-count" className="inline-flex items-center gap-1 font-medium text-destructive">
            <CircleAlert aria-hidden="true" className="size-3.5" />{t('ued.fixErrors', errorCount)}
          </span>
        )}
        {status}
        {!isView && (
          <span className="hidden items-center gap-1 lg:inline-flex">
            <KbdGroup><Kbd>Ctrl</Kbd><span aria-hidden="true">+</span><Kbd>Enter</Kbd></KbdGroup>{t('ued.kbdSave')}
          </span>
        )}
      </div>
      <div className="flex gap-2 max-sm:*:h-10 max-sm:*:flex-1">
        {isView ? (
          <>
            <Button type="button" variant="secondary" onClick={ed.requestClose}>{t('usr.close')}</Button>
            {ed.startEdit && <Button type="button" onClick={ed.startEdit}><Pencil aria-hidden="true" />{t('usr.edit')}</Button>}
          </>
        ) : (
          <>
            <Button type="button" variant="secondary" onClick={ed.requestClose} disabled={saving}>{t('usr.cancel')}</Button>
            <Button type="button" onClick={ed.save} aria-busy={saving || undefined} data-slot="ued-save"
              disabled={saving || (!isAdd && changes.length === 0)}>
              {saving ? <Spinner size={16} decorative /> : <Save aria-hidden="true" />}
              {saving ? t('usr.saving') : t('usr.save')}
            </Button>
          </>
        )}
      </div>
    </div>
  )
}
