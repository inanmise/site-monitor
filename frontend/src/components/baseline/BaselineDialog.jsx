import { useEffect, useMemo, useRef, useState } from 'react'
import { Activity } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'
import { Switch } from '@/components/shadcn/switch'
import { Input } from '@/components/shadcn/input'
import { BASELINE_DEFAULTS, DIRECTIONS, SEASONS, formFromConfig, isDirty, rangeOf, readiness, toBody, validate } from './baselineModel.js'

/**
 * HTTP izlemesinin dinamik taban çizgisi (yanıt-süresi anomalisi) yapılandırması — izleme detayından açılan ayrı diyalog
 * (uçtan uca tanılama deseni gibi; büyük HTTP formuna dokunmaz). GET/PUT bağımsız: /monitoring/http/{id}/baseline. shadcn +
 * mweb (tek sütun, dokunmatik hedefler). Sunucu doğrulaması yetkilidir; istemci ön kontrolü alan hatalarını erken gösterir.
 */
export default function BaselineDialog({ monitorId, monitorName, onClose, onSaved }) {
  const t = useT()
  const toast = useToast()
  const [cfg, setCfg] = useState(null)
  const [form, setForm] = useState(BASELINE_DEFAULTS)
  const [sampleCount, setSampleCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState(null)
  const [saving, setSaving] = useState(false)
  const seq = useRef(0)

  useEffect(() => {
    let alive = true
    const my = ++seq.current
    setLoading(true); setLoadError(null)
    api.monitoring.getHttpBaseline(monitorId).then((res) => {
      if (!alive || my !== seq.current) return
      if (res?.success) {
        const d = res.data || {}
        setCfg(d); setForm(formFromConfig(d)); setSampleCount(Number(d.sample_count) || 0)
      } else setLoadError(res?.error || t('baseline.loadFailed'))
    }).catch((e) => { if (alive && my === seq.current) setLoadError(e?.message || t('baseline.loadFailed')) })
      .finally(() => { if (alive && my === seq.current) setLoading(false) })
    return () => { alive = false }
  }, [monitorId])   // eslint-disable-line react-hooks/exhaustive-deps

  const ready = useMemo(() => readiness(form, sampleCount), [form, sampleCount])
  const dirty = useMemo(() => isDirty(form, cfg), [form, cfg])

  function set(field, value) {
    setForm((f) => ({ ...f, [field]: value }))
    setErrors((e) => { if (!e[field]) return e; const n = { ...e }; delete n[field]; return n })
    setServerError(null)
  }

  function errText(field) {
    const code = errors[field]
    if (!code) return undefined
    if (code === 'order') return t('baseline.err.order')
    if (code === 'invalid') return t('baseline.err.invalid')
    const r = rangeOf(field, form)
    return r ? t('baseline.err.range', r[0], r[1]) : t('baseline.err.invalid')
  }

  async function save() {
    const clientErrors = validate(form)
    if (Object.keys(clientErrors).length) { setErrors(clientErrors); return }
    setSaving(true); setServerError(null)
    try {
      const res = await api.monitoring.saveHttpBaseline(monitorId, toBody(form))
      if (res?.success) {
        toast.success(t('baseline.saved'))
        onSaved?.(res.data)
        onClose?.()
        return
      }
      const field = res?.field || res?.errorInfo?.field
      if (field) setErrors((e) => ({ ...e, [field]: null, [`__srv_${field}`]: res?.error || res?.message }))
      setServerError(res?.error || t('baseline.saveFailed'))
    } catch (e) {
      setServerError(e?.message || t('baseline.saveFailed'))
    } finally { setSaving(false) }
  }

  // Sunucu alan mesajı varsa onu, yoksa istemci kodunu göster
  function fieldError(field) {
    const srv = errors[`__srv_${field}`]
    return srv || errText(field)
  }

  const dirLabels = { up: t('baseline.dir.up'), down: t('baseline.dir.down'), both: t('baseline.dir.both') }
  const seasonLabels = { none: t('baseline.season.none'), daily: t('baseline.season.daily'), weekly: t('baseline.season.weekly') }

  return (
    <ModalShell open onClose={onClose} title={t('baseline.title')} icon={Activity} size="md" busy={saving}
      footer={<>
        <Button variant="secondary" onClick={onClose}>{t('baseline.close')}</Button>
        <Button data-slot="baseline-save" onClick={save} disabled={saving || loading || !dirty}>{t('baseline.save')}</Button>
      </>}>
      {loading && <LoadingBlock label={t('baseline.loading')} size={16} />}
      {loadError && !loading && (
        <div data-slot="baseline-load-error"><AlertBanner tone="danger" role="alert">{loadError}</AlertBanner></div>
      )}
      {!loading && !loadError && (
        <div className="flex flex-col gap-4" data-slot="baseline-body">
          {monitorName && <p className="text-xs font-medium text-foreground [overflow-wrap:anywhere]" data-slot="baseline-monitor">{monitorName}</p>}
          <p className="text-sm text-muted-foreground">{t('baseline.intro')}</p>

          <label className="flex items-center justify-between gap-3 rounded-lg border px-3 py-2.5 pointer-coarse:min-h-10"
            data-slot="baseline-enable">
            <span className="min-w-0">
              <span className="block text-sm font-medium">{t('baseline.enable')}</span>
              <span className="block text-xs text-muted-foreground">{t('baseline.enableHint')}</span>
            </span>
            <Switch checked={form.enabled} onCheckedChange={(v) => set('enabled', v)} aria-label={t('baseline.enable')} />
          </label>

          {form.enabled && (
            <>
              <div data-slot="baseline-readiness">
                <AlertBanner tone={ready.tone === 'ok' ? 'success' : ready.tone === 'info' ? 'info' : 'muted'}>
                  {ready.state === 'building' ? t('baseline.ready.building', ready.n, ready.min)
                    : ready.state === 'ready' ? t('baseline.ready.ready', ready.n)
                    : t('baseline.ready.off')}
                </AlertBanner>
              </div>

              <Field label={t('baseline.direction')} hint={t('baseline.directionHint')} error={fieldError('direction')}
                name="direction">
                {() => (
                  <SegmentedControl value={form.direction} onChange={(v) => set('direction', v)} ariaLabel={t('baseline.direction')}
                    options={DIRECTIONS.map((d) => ({ value: d, label: dirLabels[d] }))} />
                )}
              </Field>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Field label={t('baseline.kWarning')} error={fieldError('k_warning')} name="k_warning">
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} type="number" step="0.1" min="1" max="10"
                      value={form.k_warning} onChange={(e) => set('k_warning', e.target.value)} data-slot="baseline-k-warning" />
                  )}
                </Field>
                <Field label={t('baseline.kCritical')} error={fieldError('k_critical')} name="k_critical">
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} type="number" step="0.1" min="1" max="10"
                      value={form.k_critical} onChange={(e) => set('k_critical', e.target.value)} data-slot="baseline-k-critical" />
                  )}
                </Field>
                <Field label={t('baseline.window')} hint={t('baseline.windowHint')} error={fieldError('window')} name="window">
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} type="number" step="1" min="10" max="200"
                      value={form.window} onChange={(e) => set('window', e.target.value)} data-slot="baseline-window" />
                  )}
                </Field>
                <Field label={t('baseline.minSamples')} hint={t('baseline.minSamplesHint')} error={fieldError('min_samples')}
                  name="min_samples">
                  {({ id, describedBy, invalid }) => (
                    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} type="number" step="1" min="5"
                      value={form.min_samples} onChange={(e) => set('min_samples', e.target.value)} data-slot="baseline-min-samples" />
                  )}
                </Field>
              </div>

              <Field label={t('baseline.season')} hint={t('baseline.seasonHint')} error={fieldError('season')} name="season">
                {() => (
                  <SegmentedControl value={form.season} onChange={(v) => set('season', v)} ariaLabel={t('baseline.season')}
                    options={SEASONS.map((s) => ({ value: s, label: seasonLabels[s] }))} />
                )}
              </Field>
            </>
          )}

          {serverError && <div data-slot="baseline-save-error"><AlertBanner tone="danger" role="alert">{serverError}</AlertBanner></div>}
        </div>
      )}
    </ModalShell>
  )
}
