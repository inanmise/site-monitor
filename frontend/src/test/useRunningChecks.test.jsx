import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react'
import { useRunningChecks } from '../hooks/useRunningChecks.js'

/**
 * EŞZAMANLI KONTROLLER — uzun süren bir kontrol diğer kartları bekletmemeli.
 *
 * <p>Her izleme sayfası tek bir {@code checking} kimliği tutuyordu. İkinci karta basınca o
 * kimlik değişiyor, BİRİNCİ kartın "çalışıyor" göstergesi kayboluyordu; önce biten kontrol de
 * {@code setChecking(null)} yapıp hâlâ sürenin kilidini açıyordu. İstekler zaten paralel
 * gidiyordu — engel yalnız arayüzün tek-kimlik varsayımıydı.
 */

/** Söz (promise) dışarıdan çözülebilsin: "hâlâ sürüyor" anını test edebilmenin tek yolu. */
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function Harness({ jobs }) {
  const { isRunning, track, runningCount } = useRunningChecks()
  return (
    <div>
      <span data-testid="count">{runningCount}</span>
      {Object.keys(jobs).map(id => (
        <button key={id} data-testid={`btn-${id}`} data-running={isRunning(id) ? 'yes' : 'no'}
          // Reddi YUTUYORUZ: track hatayi bilerek YUKARI birakiyor (sayfa isterse gorsun),
          // ama bu kosum duzeneginde yakalanmazsa vitest "unhandled rejection" uyarisi verir.
          onClick={() => { track(id, () => jobs[id].promise).catch(() => {}) }}>
          {id}
        </button>
      ))}
    </div>
  )
}

const state = (id) => screen.getByTestId(`btn-${id}`).getAttribute('data-running')

describe('useRunningChecks', () => {
  it('İKİ kontrol aynı anda çalışabilir; biri bitince diğeri SÜRMEYE devam eder', async () => {
    const a = deferred(); const b = deferred()
    render(<Harness jobs={{ a, b }} />)

    fireEvent.click(screen.getByTestId('btn-a'))
    await waitFor(() => expect(state('a')).toBe('yes'))

    // Asıl şikâyet: uzun süren A biterken B'ye basılabilmeli.
    fireEvent.click(screen.getByTestId('btn-b'))
    await waitFor(() => expect(state('b')).toBe('yes'))
    expect(state('a')).toBe('yes')
    expect(screen.getByTestId('count').textContent).toBe('2')

    // B önce biterse A'nın göstergesi/kilidi ETKİLENMEMELİ (eski tek-kimlik hatası buydu).
    await act(async () => { b.resolve('ok'); await b.promise })
    await waitFor(() => expect(state('b')).toBe('no'))
    expect(state('a')).toBe('yes')
    expect(screen.getByTestId('count').textContent).toBe('1')

    await act(async () => { a.resolve('ok'); await a.promise })
    await waitFor(() => expect(state('a')).toBe('no'))
    expect(screen.getByTestId('count').textContent).toBe('0')
  })

  it('AYNI kimlik ikinci kez sıraya girmez (hızlı çift tıklama)', async () => {
    const a = deferred()
    let calls = 0
    const job = { promise: a.promise }
    render(<Harness jobs={{ a: { get promise() { calls++; return job.promise } } }} />)

    fireEvent.click(screen.getByTestId('btn-a'))
    await waitFor(() => expect(state('a')).toBe('yes'))
    fireEvent.click(screen.getByTestId('btn-a'))
    fireEvent.click(screen.getByTestId('btn-a'))

    // Küme state'i asenkron güncellendiği için ikinci tıklama `isRunning` henüz false görebilir;
    // koruma bu yüzden ayrı bir ref'te duruyor.
    expect(calls).toBe(1)
    await act(async () => { a.resolve('ok'); await a.promise })
  })

  it('İŞ PATLASA da kimlik kümede KALMAZ (aksi halde kart kalıcı kilitlenirdi)', async () => {
    const a = deferred()
    render(<Harness jobs={{ a }} />)

    fireEvent.click(screen.getByTestId('btn-a'))
    await waitFor(() => expect(state('a')).toBe('yes'))

    // Bu projede bir kez yaşandı: kontrol düğmesi ReferenceError atınca altındaki
    // setChecking(null) hiç çalışmadı ve düğme sayfa yenilenene kadar kilitli kaldı.
    await act(async () => {
      a.reject(new Error('ağ yok'))
      await a.promise.catch(() => {})
    })

    await waitFor(() => expect(state('a')).toBe('no'))
    expect(screen.getByTestId('count').textContent).toBe('0')
  })
})
