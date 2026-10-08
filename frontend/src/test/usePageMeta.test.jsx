import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const branding = { data: {} }
vi.mock('../api/client', () => ({
  api: withApiFallback({ getBranding: vi.fn(async () => ({ success: true, data: branding.data })) }),
  formatDate: (s) => s,
}))

import { usePageMeta } from '../hooks/usePageMeta.js'
import { useLanguage } from '../i18n/index.jsx'
import { BrandingProvider } from '../contexts/BrandingProvider.jsx'
import { VALID_TABS, NOT_FOUND_TAB } from '../utils/appRoutes.js'
import { appMetaKey, applyPageMeta, formatTitle, pageMetaKey, tabMetaKey } from '../utils/pageMeta.js'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'

const description = () => document.head.querySelector('meta[name="description"]')?.getAttribute('content')

function Probe({ metaKey }) {
  usePageMeta(metaKey)
  const { toggle } = useLanguage()
  return <button type="button" onClick={toggle}>lang</button>
}

beforeEach(() => {
  localStorage.clear()
  document.title = ''
  document.head.querySelectorAll('meta[name="description"]').forEach((m) => m.remove())
  branding.data = {}
})

describe('usePageMeta', () => {
  it('başlık "<Sayfa> · SiteMonitor" + meta açıklaması yazılır (meta etiketi yoksa oluşturulur)', () => {
    render(<Probe metaKey={tabMetaKey('dashboard')} />)
    expect(document.title).toBe('Dashboard · SiteMonitor')
    expect(description()).toBe(EN['meta.tab.dashboard.description'])
  })

  it('anahtar değişince (sekme geçişi) güncellenir', () => {
    const { rerender } = render(<Probe metaKey={tabMetaKey('dashboard')} />)
    rerender(<Probe metaKey={tabMetaKey('monitoring')} />)
    expect(document.title).toBe('Monitoring Overview · SiteMonitor')
    expect(description()).toBe(EN['meta.tab.monitoring.description'])
  })

  it('dil değişince başlık ve açıklama yeni dile geçer', async () => {
    render(<Probe metaKey={tabMetaKey('alerthistory')} />)
    expect(document.title).toBe('Alert History · SiteMonitor')
    fireEvent.click(screen.getByRole('button', { name: 'lang' }))
    await waitFor(() => expect(document.title).toBe('Alarm Geçmişi · SiteMonitor'))
    expect(description()).toBe(TR['meta.tab.alerthistory.description'])
  })

  it('beyaz-etiket tab_title marka kısmını değiştirir (BrandingProvider artık başlığı ezmez)', async () => {
    branding.data = { tab_title: 'ACME İzleme' }
    render(<BrandingProvider><Probe metaKey={pageMetaKey('login')} /></BrandingProvider>)
    await waitFor(() => expect(document.title).toBe('Sign in · ACME İzleme'))
  })

  it('sözlükte olmayan anahtar ham basılmaz: yalnız marka, açıklamaya dokunulmaz', () => {
    applyPageMeta('Önceki', 'önceki açıklama')
    render(<Probe metaKey="meta.tab.yok-boyle-bir-sekme" />)
    expect(document.title).toBe('SiteMonitor')
    expect(description()).toBe('önceki açıklama')
  })
})

describe('pageMeta yardımcıları', () => {
  const base = { authChecked: true, user: 'u', mustChangePwd: false, tab: 'dashboard', validTabs: VALID_TABS }

  it('appMetaKey: açılış / giriş bildirimleri / parola / sekme / bulunamadı / erişim yok', () => {
    expect(appMetaKey({ ...base, authChecked: false })).toBe('meta.page.loading')
    expect(appMetaKey({ ...base, user: null })).toBe('meta.page.login')
    expect(appMetaKey({ ...base, user: null, sessionExpired: true })).toBe('meta.page.sessionExpired')
    expect(appMetaKey({ ...base, user: null, sessionExpired: true, maintenance: true })).toBe('meta.page.maintenance')
    expect(appMetaKey({ ...base, user: null, maintenance: true, accountInactive: true })).toBe('meta.page.accountInactive')
    expect(appMetaKey({ ...base, mustChangePwd: true })).toBe('meta.page.changePassword')
    expect(appMetaKey({ ...base, tab: 'storms' })).toBe('meta.tab.storms')
    expect(appMetaKey({ ...base, tab: NOT_FOUND_TAB })).toBe('meta.page.notFound')
    expect(appMetaKey({ ...base, tab: 'settings', restricted: true })).toBe('meta.page.restricted')
  })

  it('formatTitle: boş marka → SiteMonitor; boş sayfa → yalnız marka', () => {
    expect(formatTitle('Yardım', '')).toBe('Yardım · SiteMonitor')
    expect(formatTitle('', 'ACME')).toBe('ACME')
    expect(formatTitle('  Help ', ' ACME ')).toBe('Help · ACME')
  })

  it('applyPageMeta aynı değeri yeniden yazmaz, tek meta etiketi kullanır', () => {
    act(() => { applyPageMeta('A · SiteMonitor', 'açıklama bir') })
    act(() => { applyPageMeta('B · SiteMonitor', 'açıklama iki') })
    expect(document.head.querySelectorAll('meta[name="description"]')).toHaveLength(1)
    expect(description()).toBe('açıklama iki')
    expect(document.title).toBe('B · SiteMonitor')
  })
})
