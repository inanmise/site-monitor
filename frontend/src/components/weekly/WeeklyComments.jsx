import { useEffect, useRef, useState } from 'react'
import { MessageSquare, Send, ArrowUpFromLine, CheckCircle, Undo2, RotateCcw } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'

/**
 * Haftalık rapor yorum dizisi (2026-09-13, ikinci tur): PO ↔ takım gidiş-gelişi tek yerde.
 * Sistem satırları (gönderildi / onaylandı / iade / yeniden açıldı) da aynı dizide akar; iade
 * notu artık burada da görünür. AUDIT yalnız okur (`canWrite=false`). Rapor id'si değişince
 * yeniden yüklenir; dış durum geçişlerinde (onay/iade) ebeveyn `nonce` artırarak tazeler.
 */
const KIND_ICON = { SUBMIT: ArrowUpFromLine, APPROVE: CheckCircle, REJECT: Undo2, REOPEN: RotateCcw }
/** Sistem satırının TÜM çerçeve tonu (sol şerit değil) — eski .wr-cm-item--approve/reject/submit. */
const KIND_BORDER = {
  APPROVE: 'border-green-300 dark:border-green-800',
  REJECT: 'border-red-300 dark:border-red-800',
  SUBMIT: 'border-blue-300 dark:border-blue-800',
}
export const COMMENT_MAX = 2000

export default function WeeklyComments({ reportId, canWrite, nonce = 0, className }) {
  const t = useT()
  const [items, setItems] = useState(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)
  // Açık rapor (gönderim sürerken rapor değişirse yanıt YENİ raporun dizisine eklenmesin).
  const ridRef = useRef(reportId)
  ridRef.current = reportId

  // Rapor değişince taslak ve hata da sıfırlanır (2026-10-09): A'da yazılan yorum B'ye taşınıp orada gönderilebiliyordu.
  // `nonce` (durum geçişi) taslağa DOKUNMAZ — yazarken onay/iade gelirse metin kaybolmasın.
  useEffect(() => { setText(''); setErr(null) }, [reportId])

  useEffect(() => {
    let alive = true
    setItems(null); setErr(null)
    if (!reportId) return undefined
    ;(async () => {
      try {
        const r = await api.weeklyReports.comments(reportId)
        if (alive) setItems(r?.success ? (r.data || []) : [])
      } catch { if (alive) setItems([]) }
    })()
    return () => { alive = false }
  }, [reportId, nonce])

  async function send() {
    const v = text.trim()
    if (!v || busy) return
    const rid = reportId
    setBusy(true); setErr(null)
    try {
      const r = await api.weeklyReports.addComment(rid, v)
      if (ridRef.current !== rid) return   // bu arada başka rapor açıldı — onun dizisine/taslağına dokunma
      if (r?.success && r.data) { setItems((p) => [...(p || []), r.data]); setText('') }
      else setErr(r?.error || t('wr.cm.failed'))
    } catch { if (ridRef.current === rid) setErr(t('wr.cm.failed')) } finally { setBusy(false) }
  }

  const list = items || []
  return (
    <Card role="region" aria-label={t('wr.cm.title')} data-slot="wr-comments" className={cn('gap-0 py-0 shadow-xs', className)}>
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <MessageSquare aria-hidden="true" className="size-4 text-muted-foreground" />
        <h3 className="text-sm font-semibold">{t('wr.cm.title')}</h3>
        {items && <Badge variant="secondary" className="rounded-full px-2 py-px text-[.72em] font-bold text-muted-foreground tabular-nums">{list.length}</Badge>}
      </div>
      <div className="flex min-w-0 flex-col px-4 py-3">
      {items == null ? (
        <div className="px-0.5 py-1 text-[.84em] text-muted-foreground">…</div>
      ) : list.length === 0 ? (
        <div className="px-0.5 py-1 text-[.84em] text-muted-foreground">{t('wr.cm.empty')}</div>
      ) : (
        <ul className="flex max-h-[28rem] flex-col gap-2 overflow-y-auto">
          {list.map((c) => {
            const sys = c.kind && c.kind !== 'COMMENT'
            const Icon = KIND_ICON[c.kind]
            return (
              <li key={c.id} data-slot="wr-comment" data-kind={String(c.kind || 'COMMENT').toLowerCase()} data-system={sys || undefined}
                className={cn('rounded-lg border bg-card px-2.5 py-2', sys && cn('border-dashed bg-muted/50', KIND_BORDER[c.kind]))}>
                <div className="flex flex-wrap items-center gap-1.5 text-[.78em] text-muted-foreground">
                  {Icon && <Icon size={12} aria-hidden="true" />}
                  {sys && <b className="text-foreground">{t(`wr.cm.kind.${c.kind}`)}</b>}
                  <UserBadge username={c.author} inline size="sm" />
                  <time dateTime={c.created_at}>{formatDate(c.created_at)}</time>
                </div>
                {c.text && <div className="mt-1 text-[.9em] break-words whitespace-pre-wrap">{c.text}</div>}
              </li>
            )
          })}
        </ul>
      )}
      {canWrite && (
        <div className="mt-2.5 flex flex-col gap-1.5 print:hidden">
          <Textarea data-slot="wr-comment-input" rows={2} maxLength={COMMENT_MAX} value={text}
            className="min-h-12 resize-y"
            placeholder={t('wr.cm.placeholder')} aria-label={t('wr.cm.placeholder')} disabled={busy}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); send() } }} />
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-[.76em] text-muted-foreground">{t('wr.cm.hint')} · {text.length}/{COMMENT_MAX}</span>
            <Button type="button" size="sm" onClick={send} disabled={busy || !text.trim()}>
              <Send size={13} aria-hidden="true" /> {t('wr.cm.send')}
            </Button>
          </div>
          {err && <AlertBanner tone="danger" role="alert">{err}</AlertBanner>}
        </div>
      )}
      </div>
    </Card>
  )
}
