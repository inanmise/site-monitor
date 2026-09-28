import { useCallback, useId, useRef } from 'react'
import { ChevronDown, History, Pencil, Trash2, Undo2 } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { relativeTime } from '../admin/audit/auditFormat.js'
import HintPopover from '../ui/HintPopover.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Label } from '@/components/shadcn/label'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { NOTE_MAX_LENGTH, noteCategory } from './notesModel.js'
import { CategoryBadge, NoteAvatar, isSubmitCombo, useEscapeGuard } from './notesParts.jsx'

/**
 * Zaman çizelgesinde TEK not — sol rayda yazar avatarı (baş harfler), kartta yazar · kategori rozeti · göreli zaman
 * (dokun/tıkla → tam zaman, ui/HintPopover: telefonda da açılır) · "düzenlendi" işareti (kim/ne zaman) · eylemler
 * (Düzenle / Sil / Geri yükle; dokunmatikte 40 px) · satır içi düzenleme (Ctrl/⌘+Enter kaydet, Escape iptal) · katlanır
 * yaşam döngüsü geçmişi. Silinmiş not kesikli çerçeve + üstü çizili metin. Sol renk şeridi YOK — kategori rozetle.
 *
 * Test kancaları: `data-slot="cert-note"` + `data-category` + `data-deleted`, geçmiş `data-slot="cert-note-history"`.
 */

const REV_TONE = {
  CREATE: 'bg-success/15 text-success dark:bg-success/20',
  EDIT: 'bg-primary/10 text-primary dark:bg-primary/20',
  DELETE: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  RESTORE: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
}
const ICON_BTN = 'text-muted-foreground pointer-coarse:size-10 max-sm:size-10'

function fmtTs(ts, fallback) {
  if (!ts) return fallback
  const out = formatDate(ts)
  return (!out || out === 'N/A') ? fallback : out
}

function NoteHistory({ revisions, loading, t }) {
  return (
    <div data-slot="cert-note-history" className="mt-1 flex flex-col gap-2 rounded-md border bg-muted/40 px-3 py-2.5">
      <div className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
        <History aria-hidden="true" className="size-3.5" /> {t('notes.historyTitle')}
      </div>
      {loading ? (
        <LoadingBlock label={t('notes.historyLoading')} fullWidth />
      ) : revisions.length === 0 ? (
        <div className="text-xs text-muted-foreground">{t('notes.historyEmpty')}</div>
      ) : (
        <ol className="flex flex-col gap-2">
          {[...revisions].reverse().map((r) => (
            <li key={r.id} data-slot="cert-note-revision" data-event={r.event_type} className="flex min-w-0 flex-col gap-1 text-xs">
              <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                <Badge variant="secondary" className={cn('font-semibold', REV_TONE[r.event_type] ?? REV_TONE.EDIT)}>
                  {t(`notes.event${r.event_type}`)}
                </Badge>
                <time dateTime={r.edited_at || undefined} className="tabular-nums text-muted-foreground">{fmtTs(r.edited_at, t('notes.dateUnknown'))}</time>
                {(r.edited_by || r.edited_by_name)
                  ? <UserBadge username={r.edited_by || r.edited_by_name} displayName={r.edited_by_name || undefined} inline size="sm" />
                  : <span className="font-semibold">{t('notes.authorUnknown')}</span>}
              </div>
              {r.body && (
                <div className="flex min-w-0 flex-col gap-0.5 border-t border-dashed pt-1">
                  {r.event_type === 'EDIT' && <span className="text-[11px] font-semibold text-muted-foreground">{t('cnote.previousText')}</span>}
                  <p className="whitespace-pre-wrap text-muted-foreground [overflow-wrap:anywhere]">{r.body}</p>
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

export default function CertNoteItem({
  note: n, perms, last = false, editing = false, editBody = '', onEditBody, onStartEdit, onCancelEdit, onSaveEdit, editSaving = false,
  onDelete, onRestore, pending = false, historyOpen = false, onToggleHistory, revisions = [], historyLoading = false,
}) {
  const t = useT()
  const editId = useId()
  const editWrap = useRef(null)
  const cat = noteCategory(n)
  const author = n.author_name || n.author_username || t('notes.authorUnknown')
  const created = fmtTs(n.created_at, t('notes.dateUnknown'))
  const rel = relativeTime(n.created_at, t) || created
  const edited = !!n.updated_at
  const cancel = useCallback(() => onCancelEdit?.(), [onCancelEdit])
  useEscapeGuard(editWrap, cancel, editing)

  return (
    <li data-slot="cert-note" data-category={cat} data-deleted={perms.isDeleted ? 'true' : undefined}
      className="relative flex min-w-0 gap-3 max-sm:gap-2">
      {/* Zaman çizelgesi rayı: avatar + sonraki nota uzanan dikey çizgi (son notta yok). */}
      <div aria-hidden="true" className="flex shrink-0 flex-col items-center pt-2">
        <NoteAvatar username={n.author_username} name={n.author_name} />
        {!last && <span className="mt-1 w-px flex-1 bg-border" />}
      </div>

      <Card className={cn('mb-3 min-w-0 flex-1 gap-2 px-3 py-2.5 shadow-none sm:px-4', perms.isDeleted && 'border-dashed bg-muted/40')}>
        <div className="flex min-w-0 items-start gap-2">
          <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1">
            <span data-slot="cert-note-author" className="min-w-0 text-sm font-semibold [overflow-wrap:anywhere]">{author}</span>
            <CategoryBadge category={cat} label={t(`notes.cat.${cat}`)} muted={perms.isDeleted} />
            <HintPopover content={t('cnote.createdAt', created)} triggerClassName="text-xs font-normal text-muted-foreground max-sm:min-h-10 pointer-coarse:min-h-10">
              <time dateTime={n.created_at || undefined} className="tabular-nums">{rel}</time>
            </HintPopover>
            {edited && (
              <HintPopover content={t('cnote.editedAt', fmtTs(n.updated_at, t('notes.dateUnknown')), n.updated_by || t('notes.authorUnknown'))}
                triggerClassName="max-sm:min-h-10 pointer-coarse:min-h-10">
                <Badge variant="outline" data-slot="cert-note-edited" className="gap-1 font-normal text-muted-foreground italic">
                  <Pencil aria-hidden="true" />{t('notes.editedLabel')}
                </Badge>
              </HintPopover>
            )}
          </div>
          {!editing && (perms.canEdit || perms.canDelete || perms.canRestore) && (
            <div data-slot="cert-note-actions" className="-my-1 -mr-1 flex shrink-0 items-center gap-0.5">
              {perms.canEdit && (
                <SimpleTooltip content={t('notes.edit')}>
                  <Button type="button" variant="ghost" size="icon-sm" className={cn(ICON_BTN, 'hover:text-primary')}
                    aria-label={t('cnote.editOf', author)} onClick={() => onStartEdit?.(n)} disabled={pending}>
                    <Pencil aria-hidden="true" />
                  </Button>
                </SimpleTooltip>
              )}
              {perms.canDelete && (
                <SimpleTooltip content={t('notes.delete')}>
                  <Button type="button" variant="ghost" size="icon-sm" className={cn(ICON_BTN, 'hover:text-destructive')}
                    aria-label={t('cnote.deleteOf', author)} onClick={() => onDelete?.(n)} disabled={pending} aria-busy={pending || undefined}>
                    {pending ? <Spinner size={14} inline decorative /> : <Trash2 aria-hidden="true" />}
                  </Button>
                </SimpleTooltip>
              )}
              {perms.canRestore && (
                <SimpleTooltip content={t('notes.restoreBtn')}>
                  <Button type="button" variant="ghost" size="icon-sm" className={cn(ICON_BTN, 'hover:text-success')}
                    aria-label={t('cnote.restoreOf', author)} onClick={() => onRestore?.(n)} disabled={pending} aria-busy={pending || undefined}>
                    {pending ? <Spinner size={14} inline decorative /> : <Undo2 aria-hidden="true" />}
                  </Button>
                </SimpleTooltip>
              )}
            </div>
          )}
        </div>

        {perms.isDeleted && (
          <div className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
            <Trash2 aria-hidden="true" className="size-3.5" />
            <span>{t('notes.deletedBanner', fmtTs(n.deleted_at, t('notes.dateUnknown')), n.deleted_by || t('notes.authorUnknown'))}</span>
          </div>
        )}

        {editing ? (
          <div ref={editWrap} className="flex min-w-0 flex-col gap-2">
            <Label htmlFor={editId} className="sr-only">{t('notes.edit')}</Label>
            <Textarea id={editId} value={editBody} maxLength={NOTE_MAX_LENGTH} autoFocus
              onChange={(e) => onEditBody?.(e.target.value)}
              onKeyDown={(e) => { if (isSubmitCombo(e)) { e.preventDefault(); if (!editSaving && editBody.trim()) onSaveEdit?.() } }}
              className="max-h-72 min-h-20 resize-y" />
            <div className="flex flex-wrap items-center justify-end gap-2">
              <span className="mr-auto text-xs text-muted-foreground tabular-nums">{t('cnote.counter', editBody.length, NOTE_MAX_LENGTH)}</span>
              <Button type="button" variant="ghost" size="sm" className="max-sm:h-10" onClick={cancel} disabled={editSaving}>
                {t('notes.cancel')}
              </Button>
              <Button type="button" size="sm" className="gap-1.5 max-sm:h-10" onClick={() => onSaveEdit?.()} disabled={editSaving || !editBody.trim()}
                aria-busy={editSaving || undefined}>
                {editSaving && <Spinner size={14} inline decorative />}
                {editSaving ? t('notes.saving') : t('notes.save')}
              </Button>
            </div>
          </div>
        ) : (
          <p data-slot="cert-note-body" className={cn('text-sm whitespace-pre-wrap [overflow-wrap:anywhere]', perms.isDeleted && 'text-muted-foreground line-through')}>{n.note}</p>
        )}

        <Collapsible open={historyOpen} onOpenChange={() => onToggleHistory?.(n.id)}>
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm"
              className="-mx-2 h-7 w-fit gap-1 px-2 text-xs font-medium text-muted-foreground max-sm:h-10">
              <History aria-hidden="true" className="size-3.5" />
              {historyOpen ? t('notes.hideHistory') : t('notes.showHistory')}
              <ChevronDown aria-hidden="true" className={cn('size-3.5 transition-transform motion-reduce:transition-none', historyOpen && 'rotate-180')} />
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <NoteHistory revisions={revisions} loading={historyLoading} t={t} />
          </CollapsibleContent>
        </Collapsible>
      </Card>
    </li>
  )
}
