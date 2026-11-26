/**
 * Nöbet Rutinleri — kalıcı notlar: "Sonraki nöbetçiye not" (devir dönemi) ve "Günün notu" (tamamlama e-postasına girer).
 * İkisi de görev notunun kurallarıyla çalışır (kendini kaydeder, çakışmada iki metin, taslak kişisel depoda).
 */
import { useCallback } from 'react'
import { Card } from '@/components/shadcn/card'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { DAY_NOTE_MAX, shortDate } from './routineModel.js'
import { NoteEditor } from './TaskNote.jsx'

/** Sonraki nöbetçiye not (geçerli devir dönemi). */
export default function HandoverNote({ teamId, handoverCurrent, canWrite, onSaved }) {
  const t = useT()
  const key = handoverCurrent?.period_key
  const save = useCallback((note, version) => api.oncallRoutines.saveHandoverNote(teamId, key, { note, note_version: version }),
    [teamId, key])
  if (!key) return null
  return (
    <Card data-slot="or-handover-note-card" className="gap-2 rounded-xl px-4 py-4">
      <div className="flex flex-col">
        <h3 className="m-0 text-sm font-semibold">{t('or.hand.nextNoteTitle')}</h3>
        <p className="m-0 text-xs text-muted-foreground">
          {t('or.hand.nextNoteHint', shortDate(handoverCurrent.from), shortDate(handoverCurrent.to, true))}
        </p>
      </div>
      <NoteEditor slot="or-handover-note" label={t('or.hand.nextNoteLabel')} value={handoverCurrent.note || ''}
        version={handoverCurrent.note_version || 0} save={save} onSaved={onSaved} readOnly={!canWrite} rows={3}
        maxLength={DAY_NOTE_MAX} draftKey={`sm.or.hand.${teamId}.${key}`} placeholder={t('or.hand.nextNotePlaceholder')} />
    </Card>
  )
}

/** Günün notu (tamamlama e-postasına girer; tamamlanmış günde de yazılabilir). */
export function DayNote({ teamId, date, day, canWrite, onSaved }) {
  const t = useT()
  const save = useCallback((note, version) => api.oncallRoutines.saveDayNote(teamId, date, { note, note_version: version }),
    [teamId, date])
  return (
    <Card data-slot="or-day-note-card" className="gap-2 rounded-xl px-4 py-4">
      <div className="flex flex-col">
        <h3 className="m-0 text-sm font-semibold">{t('or.dayNote.title')}</h3>
        <p className="m-0 text-xs text-muted-foreground">{t('or.dayNote.hint')}</p>
      </div>
      <NoteEditor slot="or-day-note" label={t('or.dayNote.label')} value={day?.note || ''} version={day?.note_version || 0}
        save={save} onSaved={onSaved} readOnly={!canWrite} rows={3} maxLength={DAY_NOTE_MAX}
        draftKey={`sm.or.daynote.${teamId}.${date}`} placeholder={t('or.dayNote.placeholder')} />
    </Card>
  )
}
