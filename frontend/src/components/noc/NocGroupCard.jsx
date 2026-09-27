import { EyeOff, Mail, Pencil, Send, Star, Trash2 } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'

/** Kartta görünen ilk adres sayısı; kalanı "+N" açılır listesinde. */
export const VISIBLE_EMAILS = 3

/**
 * 7/24 grubu kartı (Ayarlar → 7/24 İzleme Ekibi, 2026-09-27). Ad + durum rozetleri (Varsayılan / Aktif · Pasif),
 * açıklama, adres sayısı ve ilk adresler (kalanı "+N" Popover — dokunmatikte de açılır), bu grubu seçen izleme sayısı,
 * son güncelleyen. Eylemler (yalnız yazabilen): Test e-postası · Düzenle · Sil. Sol renk şeridi YOK — durum rozette.
 *
 * `readOnly` (kapsamlı müdür): adresler HİÇ çizilmez (yalnız sayı + "yalnız global yönetici görür"); sunucu maskeli
 * adres dönse bile arayüz göstermez.
 * Test kancaları: `data-slot="noc-group"` + `data-active` / `data-default`, `data-slot="noc-group-emails"`.
 */
export default function NocGroupCard({ group, readOnly = false, testing = false, onTest, onEdit, onDelete }) {
  const t = useT()
  const emails = Array.isArray(group.emails) ? group.emails : []
  const count = Number(group.email_count ?? emails.length) || 0
  // Sunucu kapsamlı müdüre adresleri boş dizi + `emails_hidden` döner; arayüz ikisinden biri yeterse gizler
  const hidden = readOnly || group.emails_hidden === true
  const shown = emails.slice(0, VISIBLE_EMAILS)
  const rest = emails.slice(VISIBLE_EMAILS)
  const monitors = Number(group.monitor_count) || 0
  const rowLabel = (label) => t('a11y.rowAction', label, group.name)

  return (
    <Card data-slot="noc-group" data-active={group.active ? 'true' : 'false'} data-default={group.is_default ? 'true' : undefined} className="min-w-0 gap-3 py-4">
      <CardHeader className="gap-1.5 px-4 sm:px-5">
        <CardTitle role="heading" aria-level={5} className="flex min-w-0 flex-wrap items-center gap-2 text-base leading-snug">
          <span className="min-w-0 [overflow-wrap:anywhere]">{group.name}</span>
          {group.is_default && (
            <Badge variant="default" data-slot="noc-group-default" className="gap-1"><Star aria-hidden="true" />{t('noc.gDefaultBadge')}</Badge>
          )}
          <ToneBadge tone={group.active ? 'success' : 'muted'} data-slot="noc-group-state">
            {group.active ? t('noc.gActive') : t('noc.gInactive')}
          </ToneBadge>
        </CardTitle>
        {group.description && <CardDescription className="[overflow-wrap:anywhere]">{group.description}</CardDescription>}
      </CardHeader>

      <CardContent className="flex min-w-0 flex-col gap-2.5 px-4 sm:px-5">
        <div data-slot="noc-group-emails" className="flex min-w-0 flex-wrap items-center gap-1.5 text-sm">
          <span className="inline-flex items-center gap-1.5 font-medium">
            <Mail aria-hidden="true" className="size-4 text-muted-foreground" />{count === 1 ? t('noc.gEmailCount1') : t('noc.gEmailCount', count)}
          </span>
          {hidden ? (
            <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
              <EyeOff aria-hidden="true" className="size-3.5" />{t('noc.gEmailsHidden')}
            </span>
          ) : (
            <>
              {shown.map((e) => (
                <Badge key={e} variant="outline" className="max-w-full font-normal">
                  <span className="min-w-0 truncate" title={e}>{e}</span>
                </Badge>
              ))}
              {rest.length > 0 && (
                <Popover>
                  <PopoverTrigger asChild>
                    <Button type="button" variant="secondary" size="xs" data-action="noc-group-more"
                      className="h-10 min-w-10 rounded-full px-3 tabular-nums sm:h-6 sm:min-w-0 sm:px-2 sm:pointer-coarse:h-10 sm:pointer-coarse:min-w-10"
                      aria-label={t('noc.gMoreEmails', rest.length, group.name)}>
                      +{rest.length}
                    </Button>
                  </PopoverTrigger>
                  <PopoverContent align="start" collisionPadding={8}
                    className="z-(--z-menu) w-auto max-w-[min(22rem,calc(100vw-1rem))] p-0">
                    <p className="border-b px-3 py-2 text-xs font-semibold text-muted-foreground">{t('noc.gAllEmails', group.name)}</p>
                    <ul className="flex max-h-64 list-none flex-col gap-0.5 overflow-y-auto p-2 text-sm">
                      {emails.map((e) => <li key={e} className="rounded px-1.5 py-1 [overflow-wrap:anywhere]">{e}</li>)}
                    </ul>
                  </PopoverContent>
                </Popover>
              )}
            </>
          )}
        </div>
        <p className="text-xs text-muted-foreground">
          <span data-slot="noc-group-monitors">{monitors === 0 ? t('noc.gMonitorsNone') : t('noc.gMonitors', monitors)}</span>
          {(group.updated_by_name || group.updated_at) && (
            <> · {t('noc.gUpdated', group.updated_by_name || '—', group.updated_at ? formatDate(group.updated_at) : '—')}</>
          )}
        </p>
      </CardContent>

      {!readOnly && (
        <CardFooter className="flex flex-wrap gap-2 border-t px-4 pt-3 sm:px-5 [.border-t]:pt-3">
          <Button type="button" variant="outline" size="sm" className="h-10 flex-1 sm:h-8 sm:flex-none sm:pointer-coarse:h-10"
            onClick={() => onTest?.(group)} disabled={testing || !group.active} aria-busy={testing || undefined}
            aria-label={rowLabel(t('noc.gTest'))} title={group.active ? t('noc.gTestTip') : t('noc.gTestInactive')}>
            {testing ? <Spinner size={14} inline decorative /> : <Send aria-hidden="true" />}{t('noc.gTest')}
          </Button>
          <Button type="button" variant="outline" size="sm" className="h-10 flex-1 sm:h-8 sm:flex-none sm:pointer-coarse:h-10"
            onClick={() => onEdit?.(group)} aria-label={rowLabel(t('noc.gEdit'))}>
            <Pencil aria-hidden="true" />{t('noc.gEdit')}
          </Button>
          <Button type="button" variant="ghost" size="sm" className="h-10 flex-1 text-destructive hover:bg-destructive/10 hover:text-destructive sm:ml-auto sm:h-8 sm:flex-none sm:pointer-coarse:h-10"
            onClick={() => onDelete?.(group)} aria-label={rowLabel(t('noc.gDelete'))}>
            <Trash2 aria-hidden="true" />{t('noc.gDelete')}
          </Button>
        </CardFooter>
      )}
    </Card>
  )
}
