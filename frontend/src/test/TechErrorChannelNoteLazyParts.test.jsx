import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * Kanal notu tembel parçaları (2026-11-20): görüntüleyici ve düzenleyici süre sınırlı `importWithDeadline` ile iner.
 * Parça inemezse (bayat paket, ağ, süre aşımı) çevreleyen ekran ÇÖKMEZ: yerinde "yüklenemedi" + "Tekrar dene". Başarısız
 * yükleme önbelleğe alınmaz; aynı anda bekleyen iki bileşen TEK isteğe katılır; inen parça sonraki açılışta eşzamanlı çizilir.
 *
 * Parça önbelleği modül düzeyindedir (dosya boyunca yaşar): ilk test "view", ikinci test "editor" parçasını kullanır.
 */
const loader = vi.hoisted(() => ({ next: null }))
vi.mock('../utils/importWithDeadline.js', async (importOriginal) => {
  const mod = await importOriginal()
  // Gerçek yükleyici (`() => import('./ChannelNoteView.jsx')`) HİÇ çağrılmaz — test sonucu kendisi verir
  return { ...mod, importWithDeadline: vi.fn(() => loader.next()) }
})
import { importWithDeadline } from '../utils/importWithDeadline.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { LazyNoteEditor, LazyNoteView } from '../components/techerror/notes/lazyParts.jsx'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
const slot = (name) => document.querySelector(`[data-slot="${name}"]`)
const slots = (name) => document.querySelectorAll(`[data-slot="${name}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
})

describe('kanal notu tembel parçaları', () => {
  it('görüntüleyici: yüklenirken iskelet; varsayılan dışa aktarımı olmayan modül "yüklenemedi"; "Tekrar dene" yeniden ister ve çizer', async () => {
    const first = deferred()
    loader.next = () => first.promise
    render(<LazyNoteView body="Qlik › Kanal özeti" />)
    expect(slot('te-note-view-part-loading')).not.toBeNull()
    expect(slot('te-note-view-part-loading')).toHaveAttribute('role', 'status')
    expect(importWithDeadline).toHaveBeenCalledTimes(1)

    // Bozuk parça (varsayılan dışa aktarım yok): çökmez, yerinde hata
    await act(async () => { first.resolve({}) })
    await waitFor(() => expect(slot('te-note-view-part-failed')).not.toBeNull())
    expect(slot('te-note-view-part-loading')).toBeNull()
    expect(screen.getByText('Not bileşeni yüklenemedi')).toBeInTheDocument()
    expect(screen.getByText('Sayfa güncellendi; yenileyip tekrar deneyin.')).toBeInTheDocument()

    // Başarısızlık önbelleğe alınmaz: yeniden dene yeni istek atar
    loader.next = () => Promise.resolve({ default: (props) => <div data-testid="stub-view">{props.body}</div> })
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    expect(await screen.findByTestId('stub-view')).toHaveTextContent('Qlik › Kanal özeti')
    expect(importWithDeadline).toHaveBeenCalledTimes(2)
    expect(slot('te-note-view-part-failed')).toBeNull()
  })

  it('inen görüntüleyici sonraki açılışta eşzamanlı çizilir (yeni istek yok, iskelet yok)', () => {
    loader.next = () => { throw new Error('yeniden istenmemeli') }
    render(<LazyNoteView body="ikinci açılış" />)
    expect(screen.getByTestId('stub-view')).toHaveTextContent('ikinci açılış')
    expect(slot('te-note-view-part-loading')).toBeNull()
    expect(importWithDeadline).not.toHaveBeenCalled()
  })

  it('düzenleyici: aynı anda bekleyen iki bileşen TEK isteğe katılır; süre aşımı kendi metnini yazar; sonraki hata ve başarı', async () => {
    const first = deferred()
    loader.next = () => first.promise
    render(
      <>
        <LazyNoteEditor channelId={10} />
        <LazyNoteEditor channelId={20} />
      </>,
    )
    expect(slots('te-note-editor-part-loading')).toHaveLength(2)
    expect(importWithDeadline).toHaveBeenCalledTimes(1)

    // Süre sınırı (CHUNK_TIMEOUT): "zamanında inmedi" — "sayfa güncellendi" denmez
    await act(async () => { first.reject(Object.assign(new Error('zaman aşımı'), { code: 'CHUNK_TIMEOUT' })) })
    await waitFor(() => expect(slots('te-note-editor-part-failed')).toHaveLength(2))
    expect(screen.getAllByText('Bileşen zamanında inmedi. Bağlantınızı kontrol edip yeniden deneyin.')).toHaveLength(2)
    expect(screen.queryByText('Sayfa güncellendi; yenileyip tekrar deneyin.')).toBeNull()

    // İkinci deneme: bayat paket (404) — metin değişir, ham hata metni yazılmaz
    loader.next = () => Promise.reject(new TypeError('Failed to fetch dynamically imported module: /assets/ChannelNoteEditor-abc.js'))
    fireEvent.click(screen.getAllByRole('button', { name: 'Tekrar dene' })[0])
    await waitFor(() => expect(importWithDeadline).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Sayfa güncellendi; yenileyip tekrar deneyin.')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('Failed to fetch')

    // Üçüncü deneme başarılı: basılan bileşen çizilir; öteki kendi "Tekrar dene"siyle (yeni istek atmadan) çizilir
    loader.next = () => Promise.resolve({ default: (props) => <div data-testid="stub-editor">{`kanal ${props.channelId}`}</div> })
    fireEvent.click(screen.getAllByRole('button', { name: 'Tekrar dene' })[0])
    await waitFor(() => expect(screen.getAllByTestId('stub-editor').length).toBeGreaterThanOrEqual(1))
    expect(importWithDeadline).toHaveBeenCalledTimes(3)
    const left = screen.queryAllByRole('button', { name: 'Tekrar dene' })
    if (left.length) fireEvent.click(left[0])
    await waitFor(() => expect(screen.getAllByTestId('stub-editor')).toHaveLength(2))
    expect(importWithDeadline).toHaveBeenCalledTimes(3)
    expect(screen.getAllByTestId('stub-editor').map((el) => el.textContent)).toEqual(['kanal 10', 'kanal 20'])
  })
})
