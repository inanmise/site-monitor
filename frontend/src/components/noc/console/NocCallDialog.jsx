import { useEffect, useRef, useState } from 'react'
import { BookOpenText, Mail, Phone, PhoneCall, RefreshCw, UserRound, Users } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { NocCallSection } from '../../admin/alerts/NocCallLog.jsx'
import { AlertLevelBadge, AlertTypeIcon } from '../../admin/alerts/AlertBadges.jsx'
import { unwrap } from '../nocModel.js'
import { toCallAlert } from './nocConsoleModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'

/** "0500 000 00 00" → tel: bağlantısı için yalnız rakam ve baştaki + (boşluk/parantez atılır). */
export function telHref(phone) {
  const s = String(phone ?? '').trim()
  if (!s) return null
  const digits = s.replace(/(?!^\+)[^\d]/g, '')
  return digits.replace(/\D/g, '').length >= 3 ? `tel:${digits}` : null
}

/** Bir kişi satırı: sıra no, ad + unvan, telefon (dokununca arar — `tel:`; numara olduğu gibi görünür). */
function PersonRow({ person, position, t }) {
  const href = telHref(person?.phone)
  return (
    <li data-slot="noc-sheet-person" className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5 rounded-lg border bg-card px-3 py-2">
      {position != null && (
        <span aria-hidden="true" className="inline-flex size-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary tabular-nums">
          {position}
        </span>
      )}
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-sm font-semibold" title={person?.name}>{person?.name || '—'}</span>
        {person?.title && <span className="truncate text-xs text-muted-foreground" title={person.title}>{person.title}</span>}
      </span>
      {href ? (
        <Button asChild variant="outline" size="sm" className="h-10 shrink-0 font-mono tabular-nums sm:h-8 sm:pointer-coarse:h-10">
          <a href={href} className="text-foreground no-underline" aria-label={t('noc.con.sheet.callPerson', person?.name || '', person.phone)}>
            <Phone aria-hidden="true" />{person.phone}
          </a>
        </Button>
      ) : (
        <span className="text-xs text-muted-foreground">{t('noc.con.sheet.noPhone')}</span>
      )}
    </li>
  )
}

/**
 * 7/24 konsolunun "Ara / Arama kaydı gir" penceresi (2026-10-04): alarmın SAHİBİ takımının arama kartı (sıralı arama
 * listesi TELEFONLA, Takım Müdürü, eskalasyon kişileri, yöneticinin arama talimatı — 7/24 e-postasının takım bölümüyle
 * aynı içerik, yalnız arama kaydı girebilene) + mevcut arama kaydı formu ve zaman çizelgesi (`NocCallSection`).
 * Telefon `tel:` bağlantısıdır: telefonda dokununca arar, masaüstünde numara okunur/kopyalanır.
 */
export default function NocCallDialog({ row, open, onClose, onChanged }) {
  const t = useT()
  const [sheet, setSheet] = useState({ loading: true, error: null, data: null })
  const seq = useRef(0)
  const alertId = row?.id

  async function loadSheet() {
    if (alertId == null) return
    const my = ++seq.current
    setSheet((s) => ({ ...s, loading: true, error: null }))
    try {
      const r = unwrap(await api.noc.callSheet(alertId))
      if (my !== seq.current) return
      setSheet(r.ok ? { loading: false, error: null, data: r.data || {} } : { loading: false, error: r.error || t('noc.con.sheet.loadError'), data: null })
    } catch (e) {
      if (my === seq.current) setSheet({ loading: false, error: e?.message || t('noc.con.sheet.loadError'), data: null })
    }
  }
  useEffect(() => {
    if (open) loadSheet()
    return () => { seq.current += 1 }
  }, [open, alertId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!row) return null
  const d = sheet.data
  const calls = Array.isArray(d?.call_list) ? d.call_list : []
  const esc = Array.isArray(d?.escalation) ? d.escalation : []
  const name = row.monitor?.name || row.domain
  const callAlert = toCallAlert(row)

  return (
    <ModalShell open={open} onClose={onClose} size="lg" scrollBody icon={PhoneCall} title={t('noc.con.sheet.title', name)}>
      <div data-slot="noc-call-dialog" data-alert-id={row.id} className="flex min-w-0 flex-col gap-4">
        <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
          <AlertLevelBadge level={row.level} />
          <AlertTypeIcon type={row.alert_type} />
          <span className="min-w-0 font-medium [overflow-wrap:anywhere]">{row.domain}</span>
          {row.team_name && <Badge variant="outline" className="gap-1 font-normal"><Users aria-hidden="true" className="size-3" />{row.team_name}</Badge>}
        </div>

        <section aria-labelledby={`noc-sheet-${row.id}`} data-slot="noc-call-sheet-card" className="flex min-w-0 flex-col gap-3">
          <h3 id={`noc-sheet-${row.id}`} className="m-0 flex items-center gap-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">
            <Phone aria-hidden="true" className="size-3.5" />{t('noc.con.sheet.heading', d?.team_name || row.team_name || '—')}
          </h3>
          {sheet.loading && !d && (
            <div aria-hidden="true" className="flex flex-col gap-2"><Skeleton className="h-12 w-full rounded-lg" /><Skeleton className="h-12 w-full rounded-lg" /></div>
          )}
          {sheet.error && (
            <AlertBanner tone="danger" role="alert" className="mb-0" title={t('noc.con.sheet.loadError')}
              actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={loadSheet}><RefreshCw aria-hidden="true" />{t('noc.retry')}</Button>}>
              {String(sheet.error)}
            </AlertBanner>
          )}
          {d && (
            <>
              {d.team_id == null && <p className="m-0 text-sm text-muted-foreground">{t('noc.con.sheet.noTeam')}</p>}
              {d.team_id != null && calls.length === 0 && (
                <AlertBanner tone="warning" className="mb-0" title={t('noc.con.sheet.noListTitle')}>{t('noc.con.sheet.noList')}</AlertBanner>
              )}
              {calls.length > 0 && (
                <ol data-slot="noc-sheet-call-list" aria-label={t('noc.con.sheet.listLabel')} className="m-0 flex list-none flex-col gap-1.5 p-0">
                  {calls.map((p, i) => <PersonRow key={`${p.name}-${i}`} person={p} position={p.position ?? i + 1} t={t} />)}
                </ol>
              )}
              {d.manager && (
                <div className="flex flex-col gap-1.5">
                  <span className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground"><UserRound aria-hidden="true" className="size-3.5" />{t('noc.con.sheet.manager')}</span>
                  <ul data-slot="noc-sheet-manager" className="m-0 list-none p-0"><PersonRow person={d.manager} t={t} /></ul>
                </div>
              )}
              {esc.length > 0 && (
                <div className="flex flex-col gap-1.5">
                  <span className="text-xs font-semibold text-muted-foreground">{t('noc.con.sheet.escalation')}</span>
                  <ul data-slot="noc-sheet-escalation" className="m-0 flex list-none flex-col gap-1 p-0">
                    {esc.map((c, i) => (
                      <li key={`${c.email}-${i}`} className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-sm">
                        <span className="font-medium">{c.name}</span>
                        {c.role && <span className="text-xs text-muted-foreground">{c.role}</span>}
                        {c.email && (
                          <a href={`mailto:${c.email}`} className="inline-flex min-w-0 items-center gap-1 text-xs text-primary [overflow-wrap:anywhere]">
                            <Mail aria-hidden="true" className="size-3 shrink-0" />{c.email}
                          </a>
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {d.call_instructions && (
                <AlertBanner tone="info" icon={BookOpenText} className="mb-0" title={t('noc.con.sheet.instructions')}>
                  <span className="whitespace-pre-wrap">{d.call_instructions}</span>
                </AlertBanner>
              )}
            </>
          )}
        </section>

        <NocCallSection alert={callAlert} canWrite focusKey={open ? 1 : 0} onChanged={onChanged} />
      </div>
    </ModalShell>
  )
}
