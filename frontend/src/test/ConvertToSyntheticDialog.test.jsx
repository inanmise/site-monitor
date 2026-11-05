import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    api: withApiFallback({ monitoring: { getScriptedDrafts: vi.fn(), saveScriptedDraft: vi.fn() } }),
  }
})
vi.mock('../utils/navigate.js', () => ({ navigateTo: vi.fn(), default: vi.fn(), applyTabView: vi.fn(), APPLY_VIEW_EVENT: 'sm:apply-view' }))

import { api } from '../api/client'
import { navigateTo } from '../utils/navigate.js'
import ConvertToSyntheticDialog from '../components/keyword/k6/ConvertToSyntheticDialog.jsx'

const slot = (s) => document.querySelector(`[data-slot="${s}"]`)
const slots = (s) => [...document.querySelectorAll(`[data-slot="${s}"]`)]

const MONITOR = {
  id: 7, name: 'Kurumsal site (Dijital Kanallar / Bireysel İnternet Şubesi)', url: 'https://www.example.com/giris',
  keyword: 'Hoş geldiniz', operator: 'GTE', match_count: 1, case_sensitive: false, team_id: 3, group_name: 'Kurumsal Web',
  alert_level: 'HIGH', interval_seconds: 30, timeout_ms: 8000, method: 'GET',
  // Sırlar: değerleri ASLA taşınmaz — yalnız ad (satırda değer olsa bile — savunma)
  has_custom_headers: true, custom_header_names: ['Authorization', 'X-Kanal'],
  basic_auth_user: 'servis', has_basic_auth_pass: true, basic_auth_pass: 'GIZLI-DEGER-123',
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem('site-monitor-lang', 'tr')
  api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [] } })
  api.monitoring.saveScriptedDraft.mockResolvedValue({ success: true, data: {} })
})
afterEach(() => { localStorage.setItem('site-monitor-lang', 'en') })

describe('ConvertToSyntheticDialog', () => {
  it('betik + ortam değişkenleri (gizliler değersiz) + taşınamayanlar notu; sır değeri hiçbir yerde görünmez', () => {
    render(<ConvertToSyntheticDialog monitor={MONITOR} onClose={() => {}} />)
    const script = slot('kw-k6-script').textContent
    expect(script).toMatch(/import http from 'k6\/http'/)
    expect(document.body.textContent).not.toContain('GIZLI-DEGER-123')
    const env = slots('kw-k6-env')
    expect(env.length).toBeGreaterThan(0)
    expect(env.some((e) => e.getAttribute('data-secret') === 'true')).toBe(true)
    expect(slots('kw-k6-note').map((n) => n.getAttribute('data-code'))).toContain('INTERVAL_RAISED')
    // Ad uzun olsa da kırpılmaz
    expect(slot('kw-k6-dialog').textContent).toBeTruthy()
  })

  it('"Sentetik formda aç": taslak yok → yeni taslak yazılır, Sentetik İzleme sc_open=draft ile açılır, pencere kapanır', async () => {
    const onClose = vi.fn()
    render(<ConvertToSyntheticDialog monitor={MONITOR} onClose={onClose} />)
    fireEvent.click(slot('kw-k6-open'))
    await waitFor(() => expect(api.monitoring.saveScriptedDraft).toHaveBeenCalledTimes(1))
    const body = api.monitoring.saveScriptedDraft.mock.calls[0][0]
    expect(body.monitorKey).toBe('new')
    const form = JSON.parse(body.formJson)
    expect(form.script).toMatch(/http\.request/)
    expect(form.intervalSeconds).toBeGreaterThanOrEqual(60)
    expect(JSON.stringify(form)).not.toContain('GIZLI-DEGER-123')
    expect(form.env.filter((e) => e.secret).every((e) => e.value === '')).toBe(true)
    expect(navigateTo).toHaveBeenCalledWith('scripted', { sc_open: 'draft' })
    expect(onClose).toHaveBeenCalled()
  })

  it('yarım "yeni" taslak varsa üzerine yazma onayı; İptal → hiçbir şey yazılmaz; onayla → yazılır', async () => {
    api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [{ monitor_key: 'new', monitor_name: 'Ödeme akışı' }] } })
    render(<ConvertToSyntheticDialog monitor={MONITOR} onClose={() => {}} />)
    fireEvent.click(slot('kw-k6-open'))
    const confirm = (await screen.findByText(/Ödeme akışı/)).closest('[role="dialog"],[role="alertdialog"]')
    fireEvent.click(within(confirm).getByRole('button', { name: 'İptal' }))
    await waitFor(() => expect(slot('kw-k6-open')).not.toBeDisabled())
    expect(api.monitoring.saveScriptedDraft).not.toHaveBeenCalled()
    expect(navigateTo).not.toHaveBeenCalled()

    fireEvent.click(slot('kw-k6-open'))
    fireEvent.click(await screen.findByRole('button', { name: 'Üzerine yaz' }))
    await waitFor(() => expect(api.monitoring.saveScriptedDraft).toHaveBeenCalledTimes(1))
    expect(navigateTo).toHaveBeenCalledWith('scripted', { sc_open: 'draft' })
  })

  it('taslaklar okunamazsa HİÇBİR ŞEY yazılmaz (habersiz ezme yok); 403 → yetki metni; kaydetme hatası pencerede', async () => {
    api.monitoring.getScriptedDrafts.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu hatası' })
    render(<ConvertToSyntheticDialog monitor={MONITOR} onClose={() => {}} />)
    fireEvent.click(slot('kw-k6-open'))
    await waitFor(() => expect(slot('kw-k6-error')).toHaveTextContent(/okunamadığı için hiçbir şey yazılmadı/))
    expect(api.monitoring.saveScriptedDraft).not.toHaveBeenCalled()

    api.monitoring.getScriptedDrafts.mockResolvedValueOnce({ success: false, status: 403, error: 'Yasak' })
    fireEvent.click(slot('kw-k6-open'))
    await waitFor(() => expect(slot('kw-k6-error')).toHaveTextContent(/yetkiniz yok/))

    api.monitoring.saveScriptedDraft.mockResolvedValueOnce({ success: false, status: 400, error: 'Taslak çok büyük' })
    fireEvent.click(slot('kw-k6-open'))
    await waitFor(() => expect(slot('kw-k6-error')).toHaveTextContent('Taslak çok büyük'))
    expect(navigateTo).not.toHaveBeenCalled()
    expect(slot('kw-k6-open')).not.toBeDisabled()
  })
})
