import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import CertificateCard from '../components/CertificateCard.jsx'
import DashboardFilters, { DASH_FILTER_DEFAULTS } from '../components/dashboard/DashboardFilters.jsx'
import { PAUSED_TONE, baseTone, certTone, isPausedCert } from '../components/certcard/certCardModel.js'

/**
 * Pasif (izlemesi durdurulmuş) sertifika kartları (2026-10-08, kullanıcı: "aktif olmayan sertifikalar kartlarda
 * gösterilmiyor; kullanıcı aktif ya da pasif kartları görmeli, süzebilmeli" + "kartın pasif olduğunu kart görünümünden
 * anlamamız lazım"). Pasif kart: "Pasif" rozeti (duraklat simgesi), kesikli kenar, rozetin altında "İzleme durduruldu"
 * şeridi, gri kahraman; bayat gerekçe/bildirim çipleri ve "Şimdi kontrol et" yok.
 */
const cert = (over = {}) => ({
  domain: 'pasif.example.com', subject: 'CN=pasif.example.com', issuer_cn: 'Test CA', issuer: 'Test CA',
  not_after: '2026-10-01T00:00:00', checked_at: '2026-09-01T00:00:00', status: 'valid', warning: true,
  days_remaining: -5, alert_level: 'expired', ...over,
})

describe('certCardModel — pasif ton', () => {
  it('paused=true → "paused" tonu; temel ton son kontrolden (dolmuş); paused yoksa bugünkü ton', () => {
    expect(PAUSED_TONE).toBe('paused')
    expect(isPausedCert(cert({ paused: true }))).toBe(true)
    expect(certTone(cert({ paused: true }))).toBe('paused')
    expect(baseTone(cert({ paused: true }))).toBe('expired')
    expect(certTone(cert())).toBe('expired')
    expect(isPausedCert(null)).toBe(false)
  })

  it('yeni metinlerin TR + EN karşılığı var', () => {
    const keys = ['certcard.paused', 'certcard.pausedNote', 'certcard.pausedHowTo', 'dash.flt.activity', 'dash.flt.actAll', 'dash.flt.actActive', 'dash.flt.actPaused']
    expect(keys.filter((k) => !TR[k] || !EN[k])).toEqual([])
  })
})

describe('CertificateCard — pasif kart görünümden anlaşılır', () => {
  it('rozet "Paused" + duraklat simgesi, kök data-status=paused, kesikli kenar, "izleme durduruldu" şeridi', () => {
    const { container } = render(<CertificateCard cert={cert({ paused: true })} onClick={() => {}} onEdit={() => {}} />)
    const card = container.querySelector('[data-slot="card"]')
    expect(card).toHaveAttribute('data-status', 'paused')
    expect(card).toHaveAttribute('data-paused', 'true')
    expect(card.className).toMatch(/border-dashed/)
    const badge = container.querySelector('[data-slot="cert-status"]')
    expect(badge).toHaveTextContent('Paused')
    expect(badge.querySelector('svg.lucide-circle-pause')).not.toBeNull()
    const note = container.querySelector('[data-slot="cert-paused-note"]')
    expect(note).toHaveTextContent('Monitoring is paused — details are from the last check.')
    expect(note).toHaveTextContent('tick "Active monitoring" under Edit')
  })

  it('kahraman gri ama bilgi korunur: "5" + "days since expiry" (son kontrolün tonu); bayat gerekçe çipi yok', () => {
    const { container } = render(<CertificateCard cert={cert({ paused: true })} onClick={() => {}} />)
    const hero = container.querySelector('[data-slot="cert-hero"]')
    expect(hero).toHaveAttribute('data-tone', 'paused')
    expect(container.querySelector('[data-slot="cert-days"]')).toHaveTextContent('5')
    expect(container.querySelector('[data-slot="cert-days"]').className).toMatch(/text-muted-foreground/)
    expect(container.querySelector('[data-slot="cert-reasons"]')).toBeNull()
  })

  it('Düzenle yetkisi yoksa "Düzenle\'den etkinleştirin" yönlendirmesi çıkmaz; Şimdi kontrol et düğmesi verilmez', () => {
    const { container } = render(<CertificateCard cert={cert({ paused: true })} onClick={() => {}} onDelete={() => {}} />)
    expect(container.querySelector('[data-slot="cert-paused-howto"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /check now/i })).toBeNull()
  })

  it('aktif kart DEĞİŞMEZ: pasif şeridi / data-paused yok, ton son kontrolden', () => {
    const { container } = render(<CertificateCard cert={cert()} onClick={() => {}} />)
    const card = container.querySelector('[data-slot="card"]')
    expect(card).toHaveAttribute('data-status', 'expired')
    expect(card).not.toHaveAttribute('data-paused')
    expect(container.querySelector('[data-slot="cert-paused-note"]')).toBeNull()
  })
})

describe('DashboardFilters — İzleme süzgeci', () => {
  const ACT = [
    { value: 'all', label: 'All (5)' }, { value: 'active', label: 'Active (3)' }, { value: 'paused', label: 'Paused (2)' },
  ]
  function Harness({ withActivity = true, onChange = vi.fn() }) {
    const [v, setV] = useState({ ...DASH_FILTER_DEFAULTS, platform: [] })
    const setters = Object.fromEntries(Object.keys(DASH_FILTER_DEFAULTS).map((k) => [k, (x) => { onChange(k, x); setV((p) => ({ ...p, [k]: x })) }]))
    return (
      <DashboardFilters values={v} setters={setters} options={withActivity ? { activity: ACT } : {}}
        onClearAll={() => setV({ ...DASH_FILTER_DEFAULTS, platform: [] })} shown={5} total={5} />
    )
  }

  it('hap "Monitoring: All (5)"; Pasif seçilince değer + kaldırılabilir çip', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', { name: 'Monitoring: All (5)' }))
    fireEvent.click(screen.getByRole('option', { name: 'Paused (2)' }))
    expect(onChange).toHaveBeenCalledWith('activity', 'paused')
    const chips = screen.getByRole('group', { name: 'Active filters' })
    fireEvent.click(within(chips).getByRole('button', { name: 'Remove filter: Monitoring: Paused (2)' }))
    expect(screen.getByRole('button', { name: 'Monitoring: All (5)' })).toBeInTheDocument()
  })

  it('seçenek verilmezse (eski çağıran) İzleme hapı ve çipi yok', () => {
    render(<Harness withActivity={false} />)
    expect(screen.queryByRole('button', { name: /^Monitoring:/ })).toBeNull()
    expect(screen.queryByRole('group', { name: 'Active filters' })).toBeNull()
  })
})
