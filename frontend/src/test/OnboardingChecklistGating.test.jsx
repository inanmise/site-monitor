import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { TourProvider } from '../components/tour/TourProvider.jsx'
import OnboardingChecklist from '../components/tour/OnboardingChecklist.jsx'
import TourPageChip from '../components/tour/TourPageChip.jsx'
import WelcomeCard from '../components/tour/WelcomeCard.jsx'
import { visibleChecklistItems, checklistProgress } from '../components/tour/tourEngine.js'
import { CHECKLIST_ITEMS } from '../components/tour/tourSteps.js'

/**
 * Başlangıç listesi (H paketi 2026-11-26, deneyim 3 + 10):
 *  - Haftalık Raporlar modülü takımda kapalıysa `report`, sertifikası olmadığı KESİNSE `card` gösterilmez ve sayılmaz
 *    (yapamayacağı madde listeyi hiç bitirtmiyordu); sertifika durumu bilinmiyorsa (null) madde kalır;
 *  - kapat düğmesi, madde bağlantıları, sayfa turu çipi ve karşılama kartı "bir daha gösterme" dokunmatikte 40 px
 *    (sınıf sözleşmesi — gerçek ölçüm e2e/onboarding-touch.spec.js).
 */
vi.mock('../api/client', () => ({ api: {} }))

const keys = (items) => items.map((i) => i.key)
const ctx = (over) => ({ role: 'USER', globalAdmin: false, canWrite: false, mustChangePwd: false, tab: 'dashboard', ready: true, ...over })

describe('visibleChecklistItems', () => {
  it('rapor modülü açık + sertifika var → altı madde, AYNI dizi döner', () => {
    expect(visibleChecklistItems(CHECKLIST_ITEMS, { weeklyReportsVisible: true, hasCertificates: true })).toBe(CHECKLIST_ITEMS)
  })
  it('rapor modülü kapalı → report yok; sertifika bilinmiyor (null) → card KALIR', () => {
    expect(keys(visibleChecklistItems(CHECKLIST_ITEMS, { weeklyReportsVisible: false, hasCertificates: null })))
      .toEqual(['tour', 'card', 'all', 'monitor', 'help'])
  })
  it('sertifikası olmadığı kesin (false) → card yok', () => {
    expect(keys(visibleChecklistItems(CHECKLIST_ITEMS, { weeklyReportsVisible: true, hasCertificates: false })))
      .toEqual(['tour', 'all', 'monitor', 'report', 'help'])
  })
  it('varsayılan (bilgi yok): rapor gizli, kart görünür; bozuk girdi boş dizi', () => {
    expect(keys(visibleChecklistItems(CHECKLIST_ITEMS))).toEqual(['tour', 'card', 'all', 'monitor', 'help'])
    expect(visibleChecklistItems(null)).toEqual([])
  })
  it('sayım yalnız gösterilen maddeler: gizli report işaretli olsa da sayılmaz; kalanlar bitince tamam', () => {
    const items = visibleChecklistItems(CHECKLIST_ITEMS, { weeklyReportsVisible: false, hasCertificates: false })
    expect(checklistProgress(items, { tour: true, report: true })).toMatchObject({ done: 1, total: 4, complete: false })
    expect(checklistProgress(items, { tour: true, all: true, monitor: true, help: true }).complete).toBe(true)
  })
})

describe('OnboardingChecklist — gösterilen maddeler ve dokunmatik boyut', () => {
  const state = (checklist) => ({ status: 'completed', version: 99, checklist })

  it('rapor modülü kapalı + sertifikasız takım: iki madde yok, sayaç 1/4', () => {
    const { container } = render(<TourProvider ctx={ctx()} tourState={state({ tour: true })} onPersist={() => {}}>
      <OnboardingChecklist weeklyReportsVisible={false} hasCertificates={false} />
    </TourProvider>)
    const items = [...container.querySelectorAll('[data-checklist-item]')].map((li) => li.getAttribute('data-checklist-item'))
    expect(items).toEqual(['tour', 'all', 'monitor', 'help'])
    expect(container.querySelector('[data-slot="onboarding-checklist-count"]').textContent).toBe('1/4')
  })

  it('rapor modülü açık + sertifika var: altı madde, sayaç 1/6', () => {
    const { container } = render(<TourProvider ctx={ctx()} tourState={state({ tour: true })} onPersist={() => {}}>
      <OnboardingChecklist weeklyReportsVisible hasCertificates />
    </TourProvider>)
    expect(container.querySelectorAll('[data-checklist-item]')).toHaveLength(6)
    expect(container.querySelector('[data-slot="onboarding-checklist-count"]').textContent).toBe('1/6')
  })

  it('gösterilen maddelerin hepsi bitince panel yok (gizli report bitmemiş olsa da)', () => {
    render(<TourProvider ctx={ctx()} tourState={state({ tour: true, all: true, monitor: true, help: true })} onPersist={() => {}}>
      <OnboardingChecklist weeklyReportsVisible={false} hasCertificates={false} />
    </TourProvider>)
    expect(screen.queryByText(/Başlangıç listesi|Getting started/)).toBeNull()
  })

  it('kapat düğmesi ve madde bağlantıları dokunmatikte 40 px (sınıf)', () => {
    const { container } = render(<TourProvider ctx={ctx()} tourState={state({ tour: true })} onPersist={() => {}}>
      <OnboardingChecklist weeklyReportsVisible hasCertificates />
    </TourProvider>)
    expect(screen.getByLabelText(/Listeyi gizle|Hide this list/).className).toMatch(/pointer-coarse:size-10/)
    const links = container.querySelectorAll('[data-checklist-item]:not([data-done]) button')
    expect(links.length).toBeGreaterThan(0)
    links.forEach((b) => expect(b.className).toMatch(/pointer-coarse:min-h-10/))
  })

  it('sayfa turu çipi: Başlat ve kapat dokunmatikte 40 px (sınıf)', () => {
    render(<TourProvider ctx={ctx({ tab: 'all' })} tourState={{ status: 'completed', version: 99 }} onPersist={() => {}}><TourPageChip tab="all" /></TourProvider>)
    expect(screen.getByLabelText(/Turu kapat|Close the tour/).className).toMatch(/pointer-coarse:size-10/)
    const start = screen.getByRole('button', { name: /Bu sayfada tur|Tour this page/ })
    expect(start.className).toMatch(/pointer-coarse:h-10/)
  })

  it('karşılama kartı "bir daha gösterme" dokunmatikte 40 px (sınıf)', () => {
    render(<WelcomeCard onStart={() => {}} onLater={() => {}} onNever={() => {}} />)
    expect(screen.getByText(/Bir daha gösterme|Don't show again/).closest('button').className).toMatch(/pointer-coarse:min-h-10/)
  })
})
