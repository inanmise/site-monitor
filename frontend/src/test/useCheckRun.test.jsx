import { describe, it, expect, vi } from 'vitest'
import { StrictMode } from 'react'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'

/**
 * Sayfa düzeyi toplu kontrol akışının sözleşmesi.
 *
 * <p>Kapıların üçü gerçek hata sınıflarını çiviliyor: takım süzgecinin `team_name` ile
 * çalışması (envanter türevi Port/DNS satırları `team_id` null taşır ve kimlikle süzülürse
 * SESSİZCE kapsam dışı kalır), eşzamanlılık tavanının aşılmaması (senaryo havuzu 2) ve
 * koşum ortasında unmount edilen sayfanın ölü bileşene setState çağırmaması.
 */

/** Elle çözülebilen promise — eşzamanlılık ve iptal ölçümü için. */
function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}

function Harness({ items, runOne, concurrency }) {
  const c = useCheckRun({ items, runOne, concurrency })
  return (
    <div>
      <button onClick={() => c.start(null, null)}>start</button>
      <button onClick={() => c.start(['Takım A'], 'Takım A')}>startTeamA</button>
      <button onClick={c.cancel}>cancel</button>
      <span data-testid="running">{String(c.running)}</span>
      <span data-testid="done">{String(!!c.run?.done)}</span>
      <span data-testid="total">{c.run?.total ?? ''}</span>
      <span data-testid="rows">{(c.run?.rows || []).map(r => `${r.monitor.name}:${r.ok ? 'ok' : 'fail'}:${r.error || ''}`).join('|')}</span>
    </div>
  )
}

const ok = (data = {}) => ({ ok: true, data })

describe('useCheckRun', () => {
  it('takım süzgeci team_name ile çalışır — team_id null olan envanter satırı KAPSAMDA kalır', async () => {
    // Bu değişikliğin dayandığı regresyon: Port/DNS izlemeleri çift kaynaklı, envanterden
    // türeyenlerde team_id NULL ama team_name dolu. team_id ile süzmek onları düşürürdü.
    const items = [
      { id: 1, name: 'envanter', team_id: null, team_name: 'Takım A' },
      { id: 2, name: 'standalone', team_id: 7, team_name: 'Takım B' },
    ]
    const runOne = vi.fn(async () => ok())
    render(<Harness items={items} runOne={runOne} />)

    fireEvent.click(screen.getByText('startTeamA'))
    await waitFor(() => expect(screen.getByTestId('done')).toHaveTextContent('true'))

    expect(runOne).toHaveBeenCalledTimes(1)
    expect(runOne.mock.calls[0][0].name).toBe('envanter')
    expect(screen.getByTestId('total')).toHaveTextContent('1')
  })

  it('takım seçilmemişse tüm adaylar koşar', async () => {
    const items = [{ id: 1, name: 'a' }, { id: 2, name: 'b' }, { id: 3, name: 'c' }]
    const runOne = vi.fn(async () => ok())
    render(<Harness items={items} runOne={runOne} />)

    fireEvent.click(screen.getByText('start'))
    await waitFor(() => expect(screen.getByTestId('done')).toHaveTextContent('true'))
    expect(runOne).toHaveBeenCalledTimes(3)
  })

  it('eşzamanlılık tavanı AŞILMAZ (senaryo havuzu gibi sunucu sınırları için)', async () => {
    const gates = [deferred(), deferred(), deferred(), deferred()]
    let inFlight = 0, peak = 0
    const items = gates.map((_, i) => ({ id: i, name: `m${i}` }))
    const runOne = async (m) => {
      inFlight += 1; peak = Math.max(peak, inFlight)
      await gates[m.id].promise
      inFlight -= 1
      return ok()
    }
    render(<Harness items={items} runOne={runOne} concurrency={2} />)

    fireEvent.click(screen.getByText('start'))
    await waitFor(() => expect(peak).toBe(2))
    await act(async () => { gates.forEach(g => g.resolve()) })
    await waitFor(() => expect(screen.getByTestId('done')).toHaveTextContent('true'))
    expect(peak).toBe(2)
  })

  it('Durdur: yeni iş başlamaz, uçuştaki tamamlanır, koşum done biter', async () => {
    const gate = deferred()
    const items = [{ id: 0, name: 'a' }, { id: 1, name: 'b' }, { id: 2, name: 'c' }]
    const runOne = vi.fn(async (m) => { if (m.id === 0) await gate.promise; return ok() })
    render(<Harness items={items} runOne={runOne} concurrency={1} />)

    fireEvent.click(screen.getByText('start'))
    await waitFor(() => expect(runOne).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByText('cancel'))
    await act(async () => { gate.resolve() })

    await waitFor(() => expect(screen.getByTestId('done')).toHaveTextContent('true'))
    // Uçuştaki satırını YAZAR (iş gerçekten koştu), arkasındakiler hiç başlamaz.
    expect(runOne).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('rows')).toHaveTextContent('a:ok')
    expect(screen.getByTestId('running')).toHaveTextContent('false')
  })

  it('runOne reddederse satır hata olur ve kuyruk DURMAZ', async () => {
    const items = [{ id: 1, name: 'a' }, { id: 2, name: 'b' }]
    const runOne = vi.fn(async (m) => { if (m.name === 'a') throw new Error('boom'); return ok() })
    render(<Harness items={items} runOne={runOne} />)

    fireEvent.click(screen.getByText('start'))
    await waitFor(() => expect(screen.getByTestId('done')).toHaveTextContent('true'))
    const rows = screen.getByTestId('rows').textContent
    expect(rows).toContain('a:fail:boom')
    expect(rows).toContain('b:ok')
  })

  it('undefined dönüşü (izleme zaten çalışıyor) çökmez, mesajlı satır yazar', async () => {
    const items = [{ id: 1, name: 'a' }]
    render(<Harness items={items} runOne={async () => undefined} />)

    fireEvent.click(screen.getByText('start'))
    await waitFor(() => expect(screen.getByTestId('done')).toHaveTextContent('true'))
    expect(screen.getByTestId('rows').textContent).toMatch(/a:fail:.*(zaten kontrol ediliyor|already being checked)/i)
  })

  it('ok:false ve mesajsız yanıt (401) boş satır bırakmaz', async () => {
    const items = [{ id: 1, name: 'a' }]
    render(<Harness items={items} runOne={async () => ({ ok: false, error: null, data: null })} />)

    fireEvent.click(screen.getByText('start'))
    await waitFor(() => expect(screen.getByTestId('done')).toHaveTextContent('true'))
    expect(screen.getByTestId('rows').textContent).toMatch(/a:fail:(Kontrol başarısız|Check failed)/)
  })

  it('koşum ortasında unmount: ölü bileşene setState YOK', async () => {
    // Sekmeler bir switch, router keep-alive yok — sekme değişince sayfa unmount olur ve
    // uçuştaki istekler geri döndüğünde ölü bileşene yazmaya çalışırdı (React 18 bunu artık
    // uyarmıyor, yani hata sessiz kalırdı).
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const gate = deferred()
    const items = [{ id: 1, name: 'a' }]
    const { unmount } = render(<Harness items={items} runOne={async () => { await gate.promise; return ok() }} />)

    fireEvent.click(screen.getByText('start'))
    unmount()
    await act(async () => { gate.resolve() })

    expect(errSpy).not.toHaveBeenCalled()
    errSpy.mockRestore()
  })

  it('StrictMode: çift kurulan efekt koşumu ÖLDÜRMEZ', async () => {
    // Sahada çıkan hata: StrictMode efektleri kur → temizle → kur diye iki kez çalıştırıyor.
    // "Yaşıyor" bayrağı yalnız temizlikte indirilip kurulumda geri kaldırılmayınca bileşen
    // kalıcı olarak ölü sayılıyordu: kontroller gerçekten koşup kartlara işleniyor ama koşum
    // tablosuna tek satır düşmüyor ve ilerleme 0/N'de donuyordu. Testler bunu KAÇIRMIŞTI
    // çünkü testing-library varsayılanı StrictMode kullanmıyor.
    const items = [{ id: 1, name: 'a' }, { id: 2, name: 'b' }]
    const runOne = vi.fn(async () => ok())
    render(<StrictMode><Harness items={items} runOne={runOne} /></StrictMode>)

    fireEvent.click(screen.getByText('start'))
    await waitFor(() => expect(screen.getByTestId('done')).toHaveTextContent('true'))
    const rows = screen.getByTestId('rows').textContent
    expect(rows).toContain('a:ok')
    expect(rows).toContain('b:ok')
    expect(screen.getByTestId('running')).toHaveTextContent('false')
  })

  it('aday yokken koşum HİÇ başlamaz', async () => {
    const runOne = vi.fn()
    render(<Harness items={[]} runOne={runOne} />)
    fireEvent.click(screen.getByText('start'))
    await waitFor(() => expect(screen.getByTestId('running')).toHaveTextContent('false'))
    expect(runOne).not.toHaveBeenCalled()
  })
})
