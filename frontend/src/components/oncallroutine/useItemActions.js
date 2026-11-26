/**
 * Nöbet Rutinleri — madde işaretleri (tik / atla / bölümü tamamla) için iyimser istek kuyruğu.
 *
 * Kurallar (sözleşme §9.5):
 *  - Tıklama ANINDA ekrana yazılır (`applyOptimistic`); istek `expected_state` = SUNUCUNUN son onayladığı durum ile gider.
 *  - Madde başına tek istek uçuşta; uçuştayken gelen yeni tıklama sıraya girer (son niyet kazanır) ve önceki bitince o anki
 *    onaylı durumla gönderilir.
 *  - Hata → sunucunun son onaylı maddesine geri dönülür + sunucu metni; 409 ITEM_CHANGED → sunucunun `current`'ı yazılır ve
 *    kimin işaretlediği söylenir; 409 DAY_COMPLETED → gün yeniden okunur; sonucu belirsiz yanıt (zaman aşımı / ağ) → gün
 *    yeniden okunur (yazma körlemesine tekrarlanmaz).
 *  - Takım / gün değişince uçuştaki yanıtlar düşürülür (`epoch`).
 *  - Yoklama koruması: tekli kuyruktaki VE toplu istekteki maddeler uçuşta sayılır (`inFlightKeys`); yazımı biten madde
 *    "yerleşme sırası" ile anılır (`settleMark` / `settledSince`) — ondan önce çıkmış bir yoklama yanıtı onaylı durumu
 *    eski hâliyle ezmez. Sıra artan bir sayaçtır, saat DEĞİL: `Date.now()` geri gidebilir (NTP) ve sabit saatte (test) hep
 *    aynıdır — o zaman bir kez yerleşen her madde sonsuza dek "okumadan sonra yerleşti" sayılır, yoklama satırı güncelleyemezdi.
 */
import { useCallback, useEffect, useRef } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { isUnsureResult } from '../../utils/unsureResult.js'
import { applyOptimistic, codeOf, findItem, hmOf, keyOf, replaceItem, thrownResult } from './routineModel.js'
import { serverText } from './routineUi.jsx'

/**
 * @param {object} p
 * @param {string|number} p.teamId
 * @param {string} p.contextDate   isteklerin `date` bağlamı (maddenin görünür olduğu gün)
 * @param {{current: object}} p.sheetRef   güncel sayfa (ekrandaki, iyimser hâliyle)
 * @param {(fn: Function) => void} p.setSheet
 * @param {() => void} p.reload    sessiz yeniden okuma
 * @param {(day: object|null) => void} [p.onDayCompleted]  409 DAY_COMPLETED geldiğinde
 * @param {(res: object) => void} [p.onChanged]   başarılı yazmadan sonra (rozet tazeleme vb.)
 * @param {string} [p.me]  kendi görünen adı (iyimser "siz")
 * @param {(item: object) => string|null} [p.contextDateOf]  madde başına bağlam günü (hafta görünümü: haftalık / aylık
 *        madde o haftanın bugüne kadarki son iş gününde görünür); verilmezse `contextDate`
 */
export function useItemActions({ teamId, contextDate, sheetRef, setSheet, reload, onDayCompleted, onChanged, me, contextDateOf }) {
  const t = useT()
  const toast = useToast()
  const queues = useRef(new Map())
  const bulkKeys = useRef(new Set())
  const afterBulk = useRef(new Map())   // toplu istek sürerken o maddeye gelen tekli niyet (bitince gönderilir)
  const settled = useRef(new Map())   // anahtar → son yazımın tamamlandığı yerleşme sırası
  const settleSeq = useRef(0)
  const epoch = useRef(0)
  const ctx = useRef({ teamId, contextDate })
  const cbs = useRef({})
  cbs.current = { reload, onDayCompleted, onChanged, me, t, toast, dateOf: contextDateOf }

  useEffect(() => {
    if (ctx.current.teamId !== teamId || ctx.current.contextDate !== contextDate) {
      epoch.current += 1
      queues.current = new Map()
      bulkKeys.current = new Set()
      afterBulk.current = new Map()
      settled.current = new Map()
      // `settleSeq` SIFIRLANMAZ: eski bağlamda alınmış bir okuma işareti yeni bağlamın yerleşmelerinden büyük kalmasın
    }
    ctx.current = { teamId, contextDate }
  }, [teamId, contextDate])
  useEffect(() => () => { epoch.current += 1 }, [])

  /** Yazımı biten maddeyi "yerleşti" olarak anar (yeni sıra); harita sınırlı kalır (en eski yerleşmeler budanır). */
  const markSettled = useCallback((keys) => {
    const seq = (settleSeq.current += 1)
    const m = settled.current
    for (const k of keys) m.set(k, seq)
    if (m.size > 500) for (const [k, at] of m) if (seq - at > 250) m.delete(k)
  }, [])

  const applyServer = useCallback((data) => {
    if (!data) return
    setSheet((s) => {
      let next = data.item ? replaceItem(s, data.item) : s
      if (data.day && next) next = { ...next, day: { ...next.day, ...data.day } }
      if (data.progress && next) next = { ...next, progress: { ...next.progress, ...data.progress } }
      return next
    })
  }, [setSheet])

  const fail = useCallback((key, q, res) => {
    const { t: tt, toast: ts, reload: rl, onDayCompleted: dc } = cbs.current
    const code = codeOf(res)
    if (code === 'ITEM_CHANGED' && res?.current) {
      q.confirmed = res.current
      q.next = null
      setSheet((s) => replaceItem(s, res.current))
      const who = res.current.state_by_name
      const at = hmOf(res.current.state_at)
      ts.info(who && at ? tt('or.task.changedBy', who, at) : tt('or.task.changedMeanwhile'), 6000)
      return
    }
    if (code === 'DAY_COMPLETED' || code === 'DAY_LOCKED' || code === 'ROUTINE_PAUSED') {
      q.next = null
      setSheet((s) => replaceItem(s, q.confirmed))
      ts.error(serverText(res, tt, 'or.task.saveFailed'), { details: res })
      if (code === 'DAY_COMPLETED') dc?.(res?.day || null)
      q.reloaded = true
      rl?.()
      return
    }
    if (isUnsureResult(res)) {
      q.next = null
      ts.info(tt('or.task.unsure'), 6000)
      q.reloaded = true
      rl?.()
      return
    }
    q.next = null
    setSheet((s) => replaceItem(s, q.confirmed))
    ts.error(serverText(res, tt, 'or.task.saveFailed'), { details: res })
  }, [setSheet])

  const run = useCallback(async (key, intent) => {
    const q = queues.current.get(key)
    if (!q) return
    const myEpoch = epoch.current
    q.running = true
    q.reloaded = false
    try {
      const base = q.confirmed
      const same = base.state === intent.state
        && (intent.state !== 'SKIPPED' || (base.skip_reason || '') === (intent.skipReason || ''))
      if (!same) {
        let res
        try {
          res = await api.oncallRoutines.setItemState(ctx.current.teamId, {
            date: cbs.current.dateOf?.(base) || ctx.current.contextDate,
            task_id: base.task_id,
            period_kind: base.period_kind,
            period_key: base.period_key,
            state: intent.state,
            expected_state: base.state === 'MISSED' ? 'MISSED' : base.state,
            skip_reason: intent.state === 'SKIPPED' ? intent.skipReason : null,
          })
        } catch (e) {
          res = thrownResult(e)
        }
        if (myEpoch !== epoch.current) return
        if (res === null) return
        if (res?.success) {
          const data = res.data || {}
          if (data.item) q.confirmed = data.item
          if (!q.next) applyServer(data)
          else setSheet((s) => (data.day && s ? { ...s, day: { ...s.day, ...data.day } } : s))
          cbs.current.onChanged?.(data)
        } else {
          fail(key, q, res)
        }
      }
    } finally {
      if (myEpoch === epoch.current) {
        q.running = false
        if (q.next) {
          const n = q.next
          q.next = null
          run(key, n)
        } else {
          // Kuyruk boşaldı: ekranda iyimser kalan madde sunucunun son onaylısıyla eşitlenir
          setSheet((s) => {
            const cur = findItem(s, key)
            return cur?.optimistic ? replaceItem(s, q.confirmed) : s
          })
          queues.current.delete(key)
          // Yeniden okuma istenen sonuçta (belirsiz / gün kapandı) yerleşme anılmaz: o okumanın yanıtı sunucu durumunu getirir
          if (!q.reloaded) markSettled([key])
        }
      }
    }
  }, [applyServer, fail, markSettled, setSheet])

  /** Bir maddenin durumunu değiştirir (DONE | PENDING | SKIPPED). */
  const setState = useCallback((item, state, { skipReason = null } = {}) => {
    if (!item) return
    const key = keyOf(item)
    const intent = { state, skipReason }
    const optimistic = (s) => applyOptimistic(s, key, state, {
      skipReason, me: cbs.current.me, nowIso: new Date().toISOString(),
    })
    // Bölüm tamamlama isteği bu maddeyi taşırken tekli niyet ekrana yazılır ve SIRAYA girer; toplu yanıt gelince sunucunun
    // onayladığı durumla gönderilir (son niyet kazanır)
    if (bulkKeys.current.has(key)) {
      afterBulk.current.set(key, intent)
      setSheet(optimistic)
      return
    }
    let q = queues.current.get(key)
    if (!q) {
      const current = findItem(sheetRef.current, key) || item
      q = { running: false, next: null, confirmed: current.optimistic ? item : current }
      queues.current.set(key, q)
    }
    setSheet(optimistic)
    if (q.running) { q.next = intent; return }
    run(key, intent)
  }, [run, setSheet, sheetRef])

  /** Uçuştaki maddelerin anahtarları — tekli kuyruk + toplu istek (yoklama birleştirmesi bunları ezmez). */
  const inFlightKeys = useCallback(() => new Set([...queues.current.keys(), ...bulkKeys.current]), [])
  /** Şu anki yerleşme sırası — okuma çıkarken alınır, `settledSince`'e verilir. */
  const settleMark = useCallback(() => settleSeq.current, [])
  /** `mark` alındıktan SONRA yazımı tamamlanan maddeler (o okumadan önce çıkmış yoklama yanıtı onları ezmez). */
  const settledSince = useCallback((mark) => {
    const out = new Set()
    for (const [k, at] of settled.current) if (at > mark) out.add(k)
    return out
  }, [])

  /**
   * Toplu durum (bölümü tamamla / geri al). `items` = sayfadaki maddeler; uçuştaki maddeler atlanır. Dönüş: sunucunun
   * değiştirdiği maddelerin listesi (geri alma için) ya da null (istek reddedildi).
   */
  const bulk = useCallback(async (items, state) => {
    const free = (items || []).filter((it) => !queues.current.has(keyOf(it)) && !bulkKeys.current.has(keyOf(it)))
    if (!free.length) return []
    const myEpoch = epoch.current
    const before = new Map(free.map((it) => [keyOf(it), it]))
    for (const k of before.keys()) bulkKeys.current.add(k)
    const confirmed = new Map()   // toplu yanıttan sonra sunucunun onayladığı madde (anahtar → madde)
    const meta = { reloaded: false }
    try {
      return await runBulk(free, before, myEpoch, state, confirmed, meta)
    } finally {
      if (myEpoch === epoch.current) {
        for (const k of before.keys()) bulkKeys.current.delete(k)
        if (!meta.reloaded) markSettled(before.keys())
        // Toplu istek sürerken tıklanan maddeler: niyet, sunucunun onayladığı durumla gönderilir
        for (const k of before.keys()) {
          const intent = afterBulk.current.get(k)
          if (!intent) continue
          afterBulk.current.delete(k)
          const base = confirmed.get(k)
          if (!base) continue   // oturum bitti / yanıt düşürüldü: gönderilmez
          const q = { running: false, next: null, confirmed: base }
          queues.current.set(k, q)
          setSheet((s) => applyOptimistic(s, k, intent.state, {
            skipReason: intent.skipReason, me: cbs.current.me, nowIso: new Date().toISOString(),
          }))
          run(k, intent)
        }
      } else {
        for (const k of before.keys()) afterBulk.current.delete(k)
      }
    }
  }, [markSettled, run, setSheet]) // eslint-disable-line react-hooks/exhaustive-deps

  const runBulk = async (free, before, myEpoch, state, confirmed, meta) => {
    setSheet((s) => {
      let next = s
      for (const it of free) next = applyOptimistic(next, keyOf(it), state, { me: cbs.current.me, nowIso: new Date().toISOString() })
      return next
    })
    let res
    try {
      res = await api.oncallRoutines.bulkItemState(ctx.current.teamId, {
        date: cbs.current.dateOf?.(free[0]) || ctx.current.contextDate,
        state,
        items: free.map((it) => ({ task_id: it.task_id, period_kind: it.period_kind, period_key: it.period_key, expected_state: it.state })),
      })
    } catch (e) {
      res = thrownResult(e)
    }
    if (myEpoch !== epoch.current || res === null) return null
    const { t: tt, toast: ts, reload: rl, onDayCompleted: dc } = cbs.current
    if (!res?.success) {
      // Sonucu belirsizse sıradaki tekli niyetler gönderilmez (gün yeniden okunur); kesin retteyse eski onaylı durumla gider
      if (!isUnsureResult(res)) for (const [k, it] of before) confirmed.set(k, it)
      setSheet((s) => {
        let next = s
        for (const it of before.values()) next = replaceItem(next, it)
        return next
      })
      if (isUnsureResult(res)) { ts.info(tt('or.task.unsure'), 6000); meta.reloaded = true; rl?.(); return null }
      ts.error(serverText(res, tt, 'or.sec.bulkFailed'), { details: res })
      if (codeOf(res) === 'DAY_COMPLETED') { dc?.(res?.day || null); meta.reloaded = true; rl?.() }
      return null
    }
    const results = res.data?.results || []
    const rk = (r) => `${r.period_kind}:${r.period_key}:${r.task_id}`
    const changed = results.filter((r) => r.status === 'done').map((r) => r.item || before.get(rk(r)))
    const failed = results.filter((r) => r.status === 'failed').length
    for (const [k, it] of before) {
      const r = results.find((x) => rk(x) === k)
      confirmed.set(k, r?.item || it)
    }
    setSheet((s) => {
      let next = s
      for (const r of results) {
        const k = rk(r)
        if (r.item) next = replaceItem(next, r.item)
        else if (before.has(k)) next = replaceItem(next, before.get(k))
      }
      // Sonucu dönmeyen madde (beklenmez) iyimser kalmasın
      for (const [k, it] of before) {
        if (!results.some((r) => rk(r) === k)) next = replaceItem(next, it)
      }
      if (res.data?.progress && next) next = { ...next, progress: { ...next.progress, ...res.data.progress } }
      return next
    })
    if (failed > 0) ts.error(tt('or.sec.someFailed', failed), 6000)
    cbs.current.onChanged?.(res.data)
    return changed.filter(Boolean)
  }

  return { setState, bulk, inFlightKeys, settleMark, settledSince }
}
