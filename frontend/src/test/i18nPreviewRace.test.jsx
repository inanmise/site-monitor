import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

/**
 * Dil önizlemelerinde SON SEÇİM KAZANIR (2026-11-06 inceleme DÜŞÜK-7): Giriş Yöntemleri ve Sistem Bakımı önizlemesinde
 * TR seçilir (sözlük iniyor) → hemen EN seçilir (anında geçer) → TR sonradan inince önizleme EN'de kalmalı; eskiden geç
 * inen TR `setLang('tr')` ile son seçimi eziyordu. Yükleyici taklit edilir: TR'nin indirmesini test bırakır.
 */
const gate = { trLoaded: false, release: null }

vi.mock('../i18n/index.jsx', async (importActual) => {
  const actual = await importActual()
  return {
    ...actual,
    isLanguageLoaded: (lang) => (actual.dictLang(lang) === 'tr' ? gate.trLoaded : true),
    loadLanguage: (lang) => (actual.dictLang(lang) === 'tr' && !gate.trLoaded
      ? new Promise((resolve) => { gate.release = () => { gate.trLoaded = true; resolve({}) } })
      : Promise.resolve({})),
  }
})

const { default: LoginMethodsPreview } = await import('../components/admin/loginmethods/LoginMethodsPreview.jsx')
const { default: SysMaintPreview } = await import('../components/admin/sysmaint/SysMaintPreview.jsx')

const CASES = [
  ['Giriş Yöntemleri', 'lm-preview', () => <LoginMethodsPreview form={{}} status={{}} />],
  ['Sistem Bakımı', 'sysmaint-preview', () => <SysMaintPreview current={null} serverNowMs={Date.now()} />],
]

beforeEach(() => {
  gate.trLoaded = false
  gate.release = null
  localStorage.setItem('site-monitor-lang', 'en')
})

describe.each(CASES)('%s önizlemesi — dil seçimi yarışı', (_name, slot, ui) => {
  it('TR (iniyor) → EN (anında) → TR sonradan iner: önizleme EN kalır, meşgul göstergesi kapanır', async () => {
    render(ui())
    const root = () => document.querySelector(`[data-slot="${slot}"]`)
    expect(root()).toHaveAttribute('data-lang', 'en')
    fireEvent.click(screen.getByRole('button', { name: 'TR' }))
    expect(gate.release).toBeTypeOf('function')                               // TR indirmesi başladı
    expect(screen.getByText(EN['nav.langLoading'])).toBeInTheDocument()       // meşgul göstergesi
    expect(root()).toHaveAttribute('data-lang', 'en')                          // sahne inene kadar eski dilde
    expect(screen.getByRole('button', { name: 'TR' })).toHaveAttribute('aria-pressed', 'true')   // seçici son seçimi gösterir
    fireEvent.click(screen.getByRole('button', { name: 'EN' }))               // gerçek bir değişiklik (no-op değil)
    expect(root()).toHaveAttribute('data-lang', 'en')
    expect(screen.getByRole('button', { name: 'EN' })).toHaveAttribute('aria-pressed', 'true')
    await act(async () => { gate.release() })
    expect(root()).toHaveAttribute('data-lang', 'en')                          // geç inen TR son seçimi EZMEZ
    expect(screen.getByRole('button', { name: 'EN' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByText(EN['nav.langLoading'])).toBeNull()               // meşgul göstergesi kalmadı
  })

  it('TR seçilip beklenirse inince TR olur (tek seçim normal akış)', async () => {
    render(ui())
    const root = () => document.querySelector(`[data-slot="${slot}"]`)
    fireEvent.click(screen.getByRole('button', { name: 'TR' }))
    await act(async () => { gate.release() })
    expect(root()).toHaveAttribute('data-lang', 'tr')
  })
})
