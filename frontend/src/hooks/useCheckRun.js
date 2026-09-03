import { useCallback, useEffect, useRef, useState } from 'react'
import { runWithConcurrency } from '../utils/concurrentQueue.js'
import { NO_TEAM } from '../components/check/CheckTeamPicker.jsx'
import { useT } from '../i18n/index.jsx'

/**
 * Sayfa düzeyi "Şimdi Kontrol Et" akışı: takım seçici durumu, sınırlı eşzamanlı fan-out,
 * akan sonuç satırları, Durdur ve unmount güvenliği.
 *
 * <p><b>Neden hook:</b> aynı blok dokuz izleme sayfasına girecek. Bu projede o kopyanın bedeli
 * ölçüldü (bkz. {@code useTeamOptions} not defteri): izleme sayfalarına dokunan commit'lerin
 * altıda biri dört ya da daha fazla sayfaya AYNI ANDA dokunmak zorunda kaldı ve bir kopyada
 * düzeltilen hata diğerlerine taşınmadı. Sayfa yalnız "tek monitörü nasıl çalıştırırım"
 * sorusunu cevaplar; sıra, eşzamanlılık, iptal ve ilerleme burada tek yerde durur.
 *
 * <p>Toplu koşum SUNUCUDA bir toplu uç değildir — panodaki sertifika akışı gibi istemci
 * tarafı fan-out'tur ve zaten var olan tekil tetikleme uçlarını kullanır.
 *
 * @param {Object} opts
 * @param {Array} opts.items  aday izlemeler — sayfa bunları KENDİ izin süzgecinden geçirmiş olmalı
 * @param {Function} opts.runOne  (monitor) => Promise<{ok, data?, error?}|undefined> — tek koşum
 * @param {number} [opts.concurrency=6]  aynı anda en fazla kaç kontrol
 * @returns {{pickerOpen: boolean, openPicker: Function, closePicker: Function,
 *           start: Function, run: Object|null, running: boolean, cancel: Function, close: Function}}
 */
export function useCheckRun({ items, runOne, concurrency = 6 }) {
  const t = useT()
  const [pickerOpen, setPickerOpen] = useState(false)
  const [run, setRun] = useState(null)
  const [running, setRunning] = useState(false)

  const cancelRef = useRef(false)
  const runningRef = useRef(false)
  // Aday liste ve koşum fonksiyonu her render'da değişir; ref'te tutulur ki `start` sabit kalsın
  // ve koşum başladıktan sonra araya giren bir render kuyruğu değiştirmesin.
  const itemsRef = useRef(items)
  itemsRef.current = items
  const runOneRef = useRef(runOne)
  runOneRef.current = runOne

  // Koşum ortasında sekme değişirse sayfa unmount olur (sekmeler bir switch, router keep-alive
  // YOK). O anda uçuşta kalan istekler ölü bileşene setState çağırırdı — React 18 bunu artık
  // uyarmıyor, yani hata SESSİZ kalırdı. `cancelRef` ayrıca yeni gönderimi de durdurur.
  const aliveRef = useRef(true)
  useEffect(() => {
    // Bayrak KURULUMDA da geri kaldırılır. React.StrictMode geliştirmede her efekti
    // kur → temizle → kur diye iki kez çalıştırır; yalnız temizlikte indirmek bileşeni
    // ikinci kurulumdan sonra KALICI olarak "ölü" bırakıyordu. Sonuç sinsiydi: kontroller
    // gerçekten koşuyor ve kartlara işleniyordu (o yol bu bayrağa bakmıyor) ama koşum
    // tablosuna tek satır düşmüyor ve koşum hiç "bitti"ye geçmiyordu — kullanıcı 0/2'de
    // donmuş bir ilerleme görüyordu.
    aliveRef.current = true
    return () => { aliveRef.current = false; cancelRef.current = true }
  }, [])

  const openPicker = useCallback(() => setPickerOpen(true), [])
  const closePicker = useCallback(() => setPickerOpen(false), [])
  /** Durdur: YENİ iş başlatılmaz, uçuştakiler tamamlanır ve satırlarını yazar. */
  const cancel = useCallback(() => { cancelRef.current = true }, [])
  const close = useCallback(() => { cancelRef.current = true; setRun(null) }, [])

  const start = useCallback(async (teamKeys, teamLabel) => {
    if (runningRef.current) return
    const keys = Array.isArray(teamKeys) && teamKeys.length ? teamKeys : null
    const all = itemsRef.current || []
    // Takım anahtarı `team_name` — `team_id` DEĞİL. Envanterden türeyen Port/DNS satırlarında
    // team_id null, team_name dolu; kimlikle süzmek onları sessizce kapsam dışı bırakırdı.
    const scoped = keys ? all.filter(m => keys.includes(m.team_name || NO_TEAM)) : all
    // Kuyruk adına göre sıralı girer (satırlar yine TAMAMLANMA sırasında düşer): yavaş bir
    // kontrol arkasındakileri bekletmesin ama kuyruk sırası öngörülebilir olsun.
    const queue = [...scoped].sort((a, b) => (a?.name || '').localeCompare(b?.name || '', 'tr'))
    if (!queue.length) return

    runningRef.current = true
    cancelRef.current = false
    setRunning(true)
    setRun({
      rows: [], total: queue.length, done: false,
      teamLabel: teamLabel || null, startedAt: Date.now(), finishedAt: null,
    })

    async function checkOne(monitor) {
      const startAt = new Date()
      const t0 = Date.now()
      let ok = false, data = null, error = null
      try {
        const res = await runOneRef.current(monitor)
        // `undefined`: monitör ZATEN çalışıyordu (useRunningChecks aynı kimliği ikinci kez
        // sıraya almaz). Sonuç yok ama satır yazılmalı, yoksa ilerleme sayacı hiç dolmaz.
        if (res === undefined) error = t('mon.checkAlreadyRunning')
        else {
          ok = !!res?.ok
          data = res?.data ?? null
          if (!ok) error = res?.error || null
        }
      } catch (e) { error = e?.message || null }

      const d = data || {}
      // Sunucunun ölçtüğü süre daha doğru (ağ gecikmesi hariç); yoksa istemci kronometresi.
      // Senaryo izlemesi `duration_ms` yazar, diğerleri `response_ms`.
      const serverMs = Number.isFinite(d.response_ms) ? d.response_ms
        : Number.isFinite(d.duration_ms) ? d.duration_ms : null
      const ms = serverMs ?? (Date.now() - t0)
      // Boş hata satırı okunmaz: 401 gövdesiz döner, mesajsız kırmızı satır bilgi taşımaz.
      if (!ok && !error) error = t('mon.checkAllRowFailed')

      if (!aliveRef.current) return
      // Fonksiyonel güncelleme ŞART: eşzamanlı işçiler birbirinin eklediği satırı ezmemeli.
      setRun(cr => (cr ? { ...cr, rows: [...cr.rows, { monitor, start: startAt, end: new Date(), ms, ok, data, error }] } : cr))
    }

    try {
      await runWithConcurrency(queue, checkOne, {
        limit: concurrency,
        shouldStop: () => cancelRef.current,
      })
    } finally {
      runningRef.current = false
      if (aliveRef.current) {
        setRunning(false)
        setRun(cr => (cr ? { ...cr, done: true, finishedAt: Date.now() } : cr))
      }
    }
  }, [concurrency, t])

  return { pickerOpen, openPicker, closePicker, start, run, running, cancel, close }
}

export default useCheckRun
