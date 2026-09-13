import { useEffect, useState } from 'react'
import { MessageSquare, Send, ArrowUpFromLine, CheckCircle, Undo2, RotateCcw } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import UserBadge from '../ui/UserBadge.jsx'

/**
 * Haftalık rapor yorum dizisi (2026-09-13, ikinci tur): PO ↔ takım gidiş-gelişi tek yerde.
 * Sistem satırları (gönderildi / onaylandı / iade / yeniden açıldı) da aynı dizide akar; iade
 * notu artık burada da görünür. AUDIT yalnız okur (`canWrite=false`). Rapor id'si değişince
 * yeniden yüklenir; dış durum geçişlerinde (onay/iade) ebeveyn `nonce` artırarak tazeler.
 */
const KIND_ICON = { SUBMIT: ArrowUpFromLine, APPROVE: CheckCircle, REJECT: Undo2, REOPEN: RotateCcw }
export const COMMENT_MAX = 2000

export default function WeeklyComments({ reportId, canWrite, nonce = 0 }) {
  const t = useT()
  const [items, setItems] = useState(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)

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
    setBusy(true); setErr(null)
    try {
      const r = await api.weeklyReports.addComment(reportId, v)
      if (r?.success && r.data) { setItems((p) => [...(p || []), r.data]); setText('') }
      else setErr(r?.error || t('wr.cm.failed'))
    } catch { setErr(t('wr.cm.failed')) } finally { setBusy(false) }
  }

  const list = items || []
  return (
    <section className="wr-cm" aria-label={t('wr.cm.title')}>
      <div className="show-section-header wr-cm-head">
        <MessageSquare size={14} aria-hidden="true" /> {t('wr.cm.title')}
        {items && <span className="wr-cm-count">{list.length}</span>}
      </div>
      {items == null ? (
        <div className="wr-cm-empty">…</div>
      ) : list.length === 0 ? (
        <div className="wr-cm-empty">{t('wr.cm.empty')}</div>
      ) : (
        <ul className="wr-cm-list">
          {list.map((c) => {
            const sys = c.kind && c.kind !== 'COMMENT'
            const Icon = KIND_ICON[c.kind]
            return (
              <li key={c.id} className={`wr-cm-item${sys ? ` wr-cm-item--sys wr-cm-item--${String(c.kind).toLowerCase()}` : ''}`}>
                <div className="wr-cm-meta">
                  {Icon && <Icon size={12} aria-hidden="true" />}
                  {sys && <b className="wr-cm-kind">{t(`wr.cm.kind.${c.kind}`)}</b>}
                  <UserBadge username={c.author} inline size="sm" />
                  <time dateTime={c.created_at}>{formatDate(c.created_at)}</time>
                </div>
                {c.text && <div className="wr-cm-text">{c.text}</div>}
              </li>
            )
          })}
        </ul>
      )}
      {canWrite && (
        <div className="wr-cm-form">
          <textarea className="input wr-cm-input" rows={2} maxLength={COMMENT_MAX} value={text}
            placeholder={t('wr.cm.placeholder')} aria-label={t('wr.cm.placeholder')} disabled={busy}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); send() } }} />
          <div className="wr-cm-form-row">
            <span className="wr-cm-hint">{t('wr.cm.hint')} · {text.length}/{COMMENT_MAX}</span>
            <button type="button" className="btn btn-primary btn-sm-p" onClick={send} disabled={busy || !text.trim()}>
              <Send size={13} /> {t('wr.cm.send')}
            </button>
          </div>
          {err && <div className="wr-cm-err" role="alert">{err}</div>}
        </div>
      )}
    </section>
  )
}
