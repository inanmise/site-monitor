import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CalendarSync, CircleAlert, Inbox } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Skeleton } from '@/components/shadcn/skeleton'
import { normalizeValues } from './techErrorModel.js'
import { periodLabel } from './TechErrorParts.jsx'
import { carryOverflow, planCarryOver, planHasItems } from './carryOverModel.js'

/** Tekil / çoğul (projenin deseni: `<anahtar>.one` yalnız 1 için). */
const plural = (t, key, n, ...rest) => t(Number(n) === 1 ? `${key}.one` : key, ...rest)
/** Önizleme: ilk {@link PREVIEW_CHARS} karakter, boşluklar teke indirilmiş (tam metin taşınır). */
const PREVIEW_CHARS = 180
const preview = (s) => {
  const flat = String(s || '').replace(/\s+/g, ' ').trim()
  return flat.length > PREVIEW_CHARS ? `${flat.slice(0, PREVIEW_CHARS)}…` : flat
}

/**
 * "Geçen aydan getir" penceresi (2026-11-20, C1). Açılınca geçen ayın raporu PAYLAŞILAN, süre sınırlı okumayla okunur
 * (`loader.load()` — yuvaların "Geçen ay" görünümüyle aynı okuma); okunamazsa neden + yeniden dene (ASLA "getirilecek bir şey
 * yok"). Plan ({@link planCarryOver}): metin bölümleri ve not alanları (yalnız bu ayda BOŞ olanlar — varsayılan seçili), anomali
 * konuları (seçmeli — varsayılan seçili DEĞİL: konu aya özgüdür), getirilemeyenler nedeniyle. "Getir" seçilenleri TASLAĞA yazar
 * (`onApply`); olağan otomatik kayıt / Kaydet kaydeder.
 */
export default function CarryOverDialog({ loader, source, template, getValues, year, month, channelName, resolve, onApply, onClose }) {
  const t = useT()
  const [st, setSt] = useState({ status: 'loading', plan: null, error: null })
  const [texts, setTexts] = useState(() => new Set())
  const [fields, setFields] = useState(() => new Set())
  const [topics, setTopics] = useState(() => new Set())
  const alive = useRef(true)
  const seq = useRef(0)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  const read = useCallback(() => {
    if (!loader?.load) return
    const my = ++seq.current
    setSt({ status: 'loading', plan: null, error: null })
    loader.load().then((data) => {
      if (!alive.current || my !== seq.current) return
      const prevTpl = data?.template
      if (!prevTpl || !Array.isArray(prevTpl.sections)) { setSt({ status: 'failed', plan: null, error: 'failed' }); return }
      const plan = planCarryOver({
        prev: { template: prevTpl, values: normalizeValues(data.values), year: data.report?.year ?? source?.year, month: data.report?.month ?? source?.month },
        cur: { template, values: getValues(), year, month },
        channelName,
      })
      setTexts(new Set(plan.texts.map((x) => x.id)))
      setFields(new Set(plan.fields.map((x) => x.id)))
      setTopics(new Set())
      setSt({ status: 'ok', plan, error: null })
    }, (e) => {
      if (!alive.current || my !== seq.current) return
      setSt({ status: 'failed', plan: null, error: e?.kind || 'failed' })
    })
  }, [loader, source?.year, source?.month, template, getValues, year, month, channelName])
  useEffect(() => { read() }, [read])

  const plan = st.plan
  const okTopics = useMemo(() => (plan ? plan.topics.filter((x) => x.status === 'ok') : []), [plan])
  const skippedTopics = useMemo(() => (plan ? plan.topics.filter((x) => x.status !== 'ok') : []), [plan])
  const picked = texts.size + fields.size + topics.size
  const overflow = plan ? carryOverflow(getValues(), plan, topics) : 0
  const period = periodLabel(source, t)
  // Kaynak tam önceki ay değilse (`neighbors.prev`) metinler "geçen ay" demez, raporun ayını söyler (inceleme 2026-11-20)
  const periodText = period || t('te.prev.unknownPeriod')
  const exact = source?.exact !== false
  const sectionTitle = useMemo(() => new Map((template?.sections || []).map((s) => [s.id, s.title])), [template])
  const issueSectionCount = (template?.sections || []).filter((s) => s.kind === 'issues').length

  const toggle = (setter, key) => (c) => setter((s) => {
    const next = new Set(s)
    if (c === true) next.add(key)
    else next.delete(key)
    return next
  })

  const apply = () => {
    if (!plan || picked === 0) return
    onApply({ texts, fields, topics }, plan)
  }

  const footer = (
    <div className="flex flex-wrap justify-end gap-2">
      <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={onClose}>{t('te.cancel')}</Button>
      <Button type="button" className="pointer-coarse:min-h-10" disabled={st.status !== 'ok' || picked === 0} onClick={apply}
        data-slot="te-carry-apply">
        {t('te.carry.apply', picked)}
      </Button>
    </div>
  )

  const failText = st.error === 'gone' ? t('te.carry.fail.gone', periodText)
    : st.error === 'timeout' ? t('te.carry.fail.timeout', periodText) : t('te.carry.fail.failed', periodText)

  return (
    <ModalShell open onClose={onClose} title={exact ? t('te.carry.title') : t('te.carry.titleOther')} icon={CalendarSync} size="lg" scrollBody className={PHONE_FULLSCREEN}
      footer={footer}>
      <div className="flex flex-col gap-4" data-slot="te-carry" data-state={st.status}>
        <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('te.carry.intro', periodText)}</p>
        {source && source.exact === false && (
          <p className="m-0 text-sm [overflow-wrap:anywhere]" data-slot="te-carry-not-exact">{t('te.carry.notExact', period)}</p>
        )}
        {st.status === 'loading' && (
          <div className="flex flex-col gap-2" role="status" aria-live="polite">
            <span className="sr-only">{t('te.carry.loading', periodText)}</span>
            <Skeleton className="h-10 w-full rounded-lg motion-reduce:animate-none" />
            <Skeleton className="h-10 w-full rounded-lg motion-reduce:animate-none" />
          </div>
        )}
        {st.status === 'failed' && (
          <AlertBanner tone="danger" icon={CircleAlert} title={t('te.carry.fail.title', periodText)}
            actions={<Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" onClick={read}
              data-slot="te-carry-retry">{t('te.retry')}</Button>}>
            <span data-slot="te-carry-failed" data-kind={st.error}>{failText}</span>
          </AlertBanner>
        )}
        {st.status === 'ok' && plan && !planHasItems(plan) && plan.skipped.length === 0 && skippedTopics.length === 0 && (
          <StatusBlock tone="neutral" icon={Inbox} title={t('te.carry.nothingTitle')} description={t('te.carry.nothing', period)} />
        )}
        {st.status === 'ok' && plan && (plan.texts.length > 0 || plan.fields.length > 0) && (
          <section className="flex flex-col gap-2" aria-labelledby="te-carry-texts-h" data-slot="te-carry-texts">
            <h3 id="te-carry-texts-h" className="m-0 text-sm font-semibold">{t('te.carry.textsTitle')}</h3>
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.carry.textsHint')}</p>
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {plan.texts.map((x) => (
                <CarryRow key={`t:${x.id}`} id={`te-carry-t-${x.id}`} checked={texts.has(x.id)} onChange={toggle(setTexts, x.id)}
                  label={resolve(x.label) || x.id} kind={t('te.carry.kindText')} preview={preview(x.value)} slot="te-carry-text" dataId={x.id} />
              ))}
              {plan.fields.map((x) => (
                <CarryRow key={`f:${x.id}`} id={`te-carry-f-${x.id}`} checked={fields.has(x.id)} onChange={toggle(setFields, x.id)}
                  label={resolve(x.label) || x.id} kind={t('te.carry.kindField')} preview={preview(x.value)} slot="te-carry-field" dataId={x.id} />
              ))}
            </ul>
          </section>
        )}
        {st.status === 'ok' && plan && okTopics.length > 0 && (
          <section className="flex flex-col gap-2" aria-labelledby="te-carry-topics-h" data-slot="te-carry-topics">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 id="te-carry-topics-h" className="m-0 text-sm font-semibold">{t('te.carry.topicsTitle')}</h3>
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="sm" variant="ghost" className="pointer-coarse:min-h-10" data-slot="te-carry-topics-all"
                  onClick={() => setTopics(new Set(okTopics.map((x) => x.key)))}>{t('te.carry.selectAll')}</Button>
                <Button type="button" size="sm" variant="ghost" className="pointer-coarse:min-h-10" data-slot="te-carry-topics-none"
                  onClick={() => setTopics(new Set())}>{t('te.carry.selectNone')}</Button>
              </div>
            </div>
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.carry.topicsHint')}</p>
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {okTopics.map((x, i) => {
                const flags = [
                  x.part.outage === true ? (x.part.outage_id ? t('te.carry.flagOutageId', x.part.outage_id) : t('te.carry.flagOutage')) : null,
                  x.part.impact === true ? t('te.carry.flagImpact') : x.part.impact === false ? t('te.carry.flagNoImpact') : null,
                  issueSectionCount > 1 || x.targetSectionId !== x.prevSectionId
                    ? t('te.carry.toSection', resolve(sectionTitle.get(x.targetSectionId)) || x.targetSectionId) : null,
                ].filter(Boolean)
                return (
                  <CarryRow key={x.key} id={`te-carry-k-${i}`} checked={topics.has(x.key)} onChange={toggle(setTopics, x.key)}
                    label={x.name || t('te.iss.topicN', i + 1)} kind={t('te.carry.kindTopic')} preview={preview(x.part.description)}
                    flags={flags} slot="te-carry-topic" dataId={x.key} />
                )
              })}
            </ul>
            {overflow > 0 && (
              <p className="m-0 text-sm text-destructive [overflow-wrap:anywhere]" role="status" data-slot="te-carry-overflow">
                {plural(t, 'te.carry.overflow', overflow, overflow)}
              </p>
            )}
          </section>
        )}
        {st.status === 'ok' && plan && (plan.skipped.length > 0 || skippedTopics.length > 0) && (
          <section className="flex flex-col gap-2" aria-labelledby="te-carry-skipped-h" data-slot="te-carry-skipped">
            <h3 id="te-carry-skipped-h" className="m-0 text-sm font-semibold">{t('te.carry.skippedTitle')}</h3>
            <ul className="m-0 flex list-disc flex-col gap-1 ps-5 text-sm text-muted-foreground">
              {plan.skipped.map((x) => (
                <li key={`${x.kind}:${x.id}`} className="[overflow-wrap:anywhere]" data-slot="te-carry-skip" data-reason={x.reason} data-id={x.id}>
                  {t('te.carry.skipLine', resolve(x.label) || x.id, t(`te.carry.reason.${x.reason}`))}
                </li>
              ))}
              {skippedTopics.map((x) => (
                <li key={x.key} className="[overflow-wrap:anywhere]" data-slot="te-carry-skip" data-reason={x.reason} data-id={x.key}>
                  {t('te.carry.skipLine', x.name || x.key, t(`te.carry.reason.${x.reason}`))}
                </li>
              ))}
            </ul>
          </section>
        )}
        {st.status === 'ok' && (
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-carry-never">{t('te.carry.never')}</p>
        )}
      </div>
    </ModalShell>
  )
}

/** Bir seçim satırı: kutu + ad (kutunun adı `aria-labelledby` ile) + tür etiketi + önizleme + işaretler. */
function CarryRow({ id, checked, onChange, label, kind, preview: text, flags = [], slot, dataId }) {
  return (
    <li className="flex min-w-0 items-start gap-3 rounded-lg border p-3" data-slot={slot} data-id={dataId} data-checked={checked ? 'true' : 'false'}>
      <Checkbox id={id} checked={checked} onCheckedChange={onChange} aria-labelledby={`${id}-l`} aria-describedby={text ? `${id}-p` : undefined}
        className="mt-0.5" />
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <label id={`${id}-l`} htmlFor={id} className="min-w-0 text-sm font-medium leading-snug [overflow-wrap:anywhere]">{label}</label>
          <Badge variant="outline" className="rounded-md overflow-visible whitespace-normal text-xs font-normal">{kind}</Badge>
        </div>
        {text && <p id={`${id}-p`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{text}</p>}
        {flags.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {flags.map((f) => <Badge key={f} variant="secondary" className="rounded-md overflow-visible whitespace-normal text-xs font-normal">{f}</Badge>)}
          </div>
        )}
      </div>
    </li>
  )
}
