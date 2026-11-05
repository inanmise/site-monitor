import { useMemo, useState } from 'react'
import { FlaskConical, KeyRound, Lightbulb, ScrollText, Variable } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { CodeBlock, SectionTitle } from '../../http/diagnose/HttpDiagnoseParts.jsx'
import { K6_NOTE_CODES, keywordToK6 } from './keywordToK6.js'

const statusOf = (r) => Number(r?.status ?? errorInfoOf(r)?.status)

/**
 * "Sentetik izlemeye dönüştür" penceresi (kw paket F1 / F3 — tembel parça). Anahtar kelime izlemesinden İSTEMCİDE üretilen
 * k6 betiğini (önizleme + kopyala), ortam değişkenlerini (gizliler BOŞ) ve taşınamayanları (notlar) gösterir. "Sentetik
 * formda aç": kullanıcının sunucudaki yarım "yeni izleme" taslağı varsa ÜZERİNE YAZMA onayı istenir (taslaklar okunamazsa
 * hiçbir şey yazılmaz — habersiz ezme yok), ardından taslak `new` anahtarına yazılır ve Sentetik İzleme sayfası
 * `sc_open=draft` ile açılır (sayfa taslağı forma yükler). Anahtar kelime izlemesi DEĞİŞMEZ.
 *
 * <p>Kancalar: `kw-k6-dialog`, `kw-k6-script`, `kw-k6-env` (`data-secret`), `kw-k6-note` (`data-code`), `kw-k6-open`,
 * `kw-k6-error`.
 */
export default function ConvertToSyntheticDialog({ monitor, onClose }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const built = useMemo(() => keywordToK6(monitor, { t }), [monitor, t])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function openInForm() {
    setError(null)
    setBusy(true)
    try {
      const drafts = await api.monitoring.getScriptedDrafts()
      if (!drafts?.success) {
        setError(statusOf(drafts) === 403 ? t('keyword.k6.noPermission') : t('keyword.k6.draftsUnreadable', drafts?.error || ''))
        return
      }
      const existing = (drafts.data?.drafts || []).find((d) => d.monitor_key === 'new')
      if (existing) {
        const ok = await showConfirm({
          title: t('keyword.k6.overwriteTitle'),
          message: t('keyword.k6.overwriteBody', existing.monitor_name || t('keyword.k6.unnamedDraft')),
          confirmText: t('keyword.k6.overwriteConfirm'), variant: 'warning',
        })
        if (!ok) return
      }
      const res = await api.monitoring.saveScriptedDraft({
        monitorKey: 'new', monitorName: built.form.name || null, formJson: JSON.stringify(built.form),
      })
      if (!res?.success) {
        setError(statusOf(res) === 403 ? t('keyword.k6.noPermission') : t('keyword.k6.saveFailed', res?.error || ''))
        return
      }
      navigateTo('scripted', { sc_open: 'draft' })
      onClose?.()
    } catch (e) {
      setError(t('keyword.k6.saveFailed', e?.message || ''))
    } finally {
      setBusy(false)
    }
  }

  const notes = built.notes.filter((n) => K6_NOTE_CODES.includes(n.code))
  return (
    <ModalShell open onClose={onClose} title={t('keyword.k6.dialogTitle')} icon={FlaskConical} size="lg" scrollBody busy={busy}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={busy} className="pointer-coarse:h-10">{t('keyword.cancel')}</Button>
          <Button type="button" data-slot="kw-k6-open" onClick={openInForm} disabled={busy} aria-busy={busy || undefined} className="pointer-coarse:h-10">
            <FlaskConical aria-hidden="true" />{busy ? t('keyword.k6.opening') : t('keyword.k6.open')}
          </Button>
        </>
      )}>
      <div data-slot="kw-k6-dialog" className="flex min-w-0 flex-col gap-4">
        <p className="m-0 text-sm [overflow-wrap:anywhere]">{t('keyword.k6.dialogIntro')}</p>
        {error && <AlertBanner tone="danger" role="alert" className="mb-0"><span data-slot="kw-k6-error">{error}</span></AlertBanner>}

        {notes.length > 0 && (
          <section className="flex min-w-0 flex-col gap-1.5">
            <SectionTitle icon={Lightbulb}>{t('keyword.k6.notesTitle')}</SectionTitle>
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
              {notes.map((n, i) => (
                <li key={`${n.code}-${i}`} data-slot="kw-k6-note" data-code={n.code}
                  className="rounded-md border border-amber-500/30 bg-amber-500/5 px-3 py-2 text-sm [overflow-wrap:anywhere]">
                  {t(`keyword.k6.note.${n.code}`, ...n.params)}
                </li>
              ))}
            </ul>
          </section>
        )}

        <section className="flex min-w-0 flex-col gap-1.5">
          <SectionTitle icon={Variable}>{t('keyword.k6.env')}</SectionTitle>
          {built.env.length === 0 ? (
            <p className="m-0 text-sm text-muted-foreground">{t('keyword.k6.envNone')}</p>
          ) : (
            <>
              <ul className="m-0 flex list-none flex-col gap-1 p-0">
                {built.env.map((e) => (
                  <li key={e.name} data-slot="kw-k6-env" data-secret={e.secret ? 'true' : 'false'} className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
                    <code className="font-mono text-xs font-semibold [overflow-wrap:anywhere]">{e.name}</code>
                    <Badge variant="outline" className="h-auto rounded-md overflow-visible whitespace-normal text-[11px]">
                      {e.secret ? <><KeyRound aria-hidden="true" />{t('keyword.k6.envSecret')}</> : t('keyword.k6.envPlain')}
                    </Badge>
                    {!e.secret && e.value && <span className="min-w-0 font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">{e.value}</span>}
                  </li>
                ))}
              </ul>
              {built.env.some((e) => e.secret) && <p className="m-0 text-xs text-muted-foreground">{t('keyword.k6.secretsNote')}</p>}
            </>
          )}
        </section>

        <section className="flex min-w-0 flex-col gap-1.5">
          <SectionTitle icon={ScrollText}>{t('keyword.k6.script')}</SectionTitle>
          <CodeBlock data-slot="kw-k6-script" maxH="max-h-96" copy={built.script} copyLabel={t('keyword.k6.copy')}>{built.script}</CodeBlock>
        </section>
      </div>
    </ModalShell>
  )
}
