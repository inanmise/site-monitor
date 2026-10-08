import { describe, it, expect, beforeAll, afterEach, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { extractCore } from '../components/manualcert/extract/core.js'
import { extractCertificates } from '../components/manualcert/extract/index.js'
import {
  applyExtractProgress, applyUploadProgress, canCancel, coarsePercent, enterStage, failRun, stageLabelKey, stageMeter, stagePercent,
  stagesFor, startRun,
} from '../components/manualcert/wizard/uploadProgressModel.js'
import { forgePfx, keyPem, makeChain, makeRoot, zipBytes } from './helpers/certFixtures.js'

/**
 * YÜKLEME DURUMU (2026-10-08, kullanıcı isteği: "manuel yükleme yaparken yükleme durumunu gösterelim") — saf model,
 * tarayıcıdaki ayıklayıcının ilerleme bildirimleri (ZIP "n / N", PKCS#12 anahtar türetme), Web Worker ilerleme
 * mesajları ve "Vazgeç" (Worker sonlandırılır). İlerleme yalnız sayaç / biçim taşır — parola ya da anahtar ASLA.
 */

let chain
let other
beforeAll(() => {
  chain = makeChain('api.example.test')
  other = makeRoot('Example Independent Root')
})

describe('uploadProgressModel — aşamalar', () => {
  it('aşama listesi sabit: dosya analizi 4, metin 3, kayıt 2 aşama; ilk aşama etkin, diğerleri bekliyor', () => {
    expect(stagesFor('analyze', 'file')).toEqual(['read', 'extract', 'upload', 'analyze'])
    expect(stagesFor('analyze', 'text')).toEqual(['extract', 'upload', 'analyze'])
    expect(stagesFor('save')).toEqual(['upload', 'save'])
    const r = startRun({ kind: 'analyze', source: 'file', now: 1000 })
    expect(r).toMatchObject({ status: 'running', active: 'read', startedAt: { read: 1000 } })
    expect(r.state).toEqual({ read: 'active', extract: 'pending', upload: 'pending', analyze: 'pending' })
  })

  it('aşamalar YALNIZ ileri gider: öncekiler tamam olur; geç gelen eski bildirim bitmiş aşamayı yeniden açmaz', () => {
    let r = startRun({ kind: 'analyze', source: 'file', now: 0 })
    r = applyExtractProgress(r, { phase: 'extract' }, 10)
    expect(r.state).toMatchObject({ read: 'done', extract: 'active' })
    r = enterStage(r, 'upload', 20)
    expect(r.state).toMatchObject({ read: 'done', extract: 'done', upload: 'active', analyze: 'pending' })
    const same = applyExtractProgress(r, { phase: 'read' }, 30)
    expect(same).toBe(r)                                   // geriye gidiş yok sayılır
    expect(enterStage(r, 'nope', 40)).toBe(r)
    // Ayıklama "read"i atlayıp doğrudan ZIP bildirirse "read" yine TAMAM sayılır
    const z = applyExtractProgress(startRun({ kind: 'analyze', source: 'file', now: 0 }), { phase: 'zip', done: 3, total: 12 }, 5)
    expect(z.state).toMatchObject({ read: 'done', extract: 'active' })
    expect(z.extract.zip).toEqual({ done: 3, total: 12 })
    expect(stagePercent(z)).toBe(25)
    expect(stageMeter(z)).toEqual({ value: 3, max: 12 })
  })

  it('yükleme: GERÇEK bayt yüzdesi; toplam bilinmiyorsa belirsiz; "sent" sunucu aşamasına geçirir', () => {
    let r = enterStage(startRun({ kind: 'analyze', source: 'text', now: 0 }), 'upload', 1)
    r = applyUploadProgress(r, { phase: 'upload', loaded: 300, total: 1200 }, 2)
    expect(stagePercent(r)).toBe(25)
    expect(stageMeter(r)).toEqual({ value: 300, max: 1200 })
    const unknown = applyUploadProgress(enterStage(startRun({ kind: 'save', now: 0 }), 'upload', 1), { phase: 'upload', loaded: 77, total: null }, 2)
    expect(stagePercent(unknown)).toBeNull()
    expect(stageMeter(unknown)).toBeNull()
    expect(unknown.active).toBe('upload')
    const viaSent = applyUploadProgress(r, { phase: 'sent' }, 4)
    expect(viaSent.active).toBe('analyze')
    expect(viaSent.state).toMatchObject({ upload: 'done', analyze: 'active' })
    expect(stagePercent(viaSent)).toBeNull()               // sunucu aşaması belirsiz
    // Tüm baytlar gittiyse "sent" beklenmez (Chromium küçük gövdede upload.onload'u yanıtla verir); taşma kırpılır
    const full = applyUploadProgress(r, { phase: 'upload', loaded: 5000, total: 1200 }, 3)
    expect(full.active).toBe('analyze')
    expect(full.upload).toEqual({ loaded: 1200, total: 1200 })
    expect(canCancel(applyUploadProgress(enterStage(startRun({ kind: 'save', now: 0 }), 'upload'), { phase: 'upload', loaded: 9, total: 9 }))).toBe(false)
    // Sunucu aşamasındayken geç gelen yükleme baytı geri götürmez
    expect(applyUploadProgress(full, { phase: 'upload', loaded: 1, total: 2 }, 5).active).toBe('analyze')
  })

  it('iptal: analiz her an; kayıt YALNIZ gövde giderken (istek sunucuya ulaşınca kilitli)', () => {
    const a = startRun({ kind: 'analyze', source: 'file', now: 0 })
    expect(canCancel(a)).toBe(true)
    expect(canCancel(applyUploadProgress(enterStage(a, 'upload'), { phase: 'sent' }))).toBe(true)
    const s = startRun({ kind: 'save', saveKind: 'created', now: 0 })
    expect(canCancel(s)).toBe(true)
    const sent = applyUploadProgress(s, { phase: 'sent' })
    expect(sent.active).toBe('save')
    expect(canCancel(sent)).toBe(false)
    expect(canCancel(null)).toBe(false)
  })

  it('başarısız koşu: etkin aşama "error", koşu "failed", kaldığı aşama bilinir; artık ilerleme kabul etmez', () => {
    let r = enterStage(startRun({ kind: 'save', saveKind: 'renewed', now: 0 }), 'save', 5)
    r = failRun(r)
    expect(r).toMatchObject({ status: 'failed', failedStage: 'save', state: { upload: 'done', save: 'error' } })
    expect(applyUploadProgress(r, { phase: 'upload', loaded: 1, total: 2 })).toBe(r)
    expect(canCancel(r)).toBe(false)
    expect(stageLabelKey(r, 'save')).toEqual(['mcert.prog.stage.saveVersion'])
    expect(stageLabelKey({ saveKind: 'batch', count: 3 }, 'save')).toEqual(['mcert.prog.stage.saveBatch', 3])
    expect(stageLabelKey({ saveKind: 'created' }, 'save')).toEqual(['mcert.prog.stage.save'])
    expect(stageLabelKey(r, 'upload')).toEqual(['mcert.prog.stage.upload'])
  })

  it('ekran okuyucu yüzdesi kaba adımlarla (her bayt güncellemesinde konuşmaz)', () => {
    expect([0, 24, 25, 61, 99, 100].map((p) => coarsePercent(p))).toEqual([0, 0, 25, 50, 75, 100])
    expect(coarsePercent(null)).toBeNull()
  })
})

describe('ayıklayıcı ilerlemesi (core.js) — yalnız sayaç / biçim; parola ve anahtar YOK', () => {
  const run = (bytes, name, password = '') => {
    const events = []
    return extractCore({ bytes, name, password }, { subtle: webcrypto.subtle, onProgress: (p) => events.push(p) })
      .then((r) => ({ r, events }))
  }

  it('ZIP: "0 / N" ile başlar, girdi başına artar, "N / N" ile biter', async () => {
    const zip = zipBytes({ 'leaf.pem': chain.leaf.pem, 'root.pem': other.pem, 'leaf.key': keyPem(chain.leaf.keys) })
    const { r, events } = await run(zip, 'paket.zip')
    expect(r.format).toBe('ZIP')
    const zipEvents = events.filter((e) => e.phase === 'zip')
    expect(zipEvents.map((e) => `${e.done}/${e.total}`)).toEqual(['0/3', '1/3', '2/3', '3/3'])
  })

  it('PKCS#12: anahtar türetme (kdf) başında ve bittiğinde bildirim; ilerleme metninde parola geçmez', async () => {
    const pfx = forgePfx(chain.leaf.keys, [chain.leaf.cert], 'Gizli-Parola-1')
    const { r, events } = await run(pfx, 'sunucu.pfx', 'Gizli-Parola-1')
    expect(r.format).toBe('PKCS12')
    const steps = events.filter((e) => e.phase === 'pkcs12').map((e) => e.step)
    expect(steps[0]).toBe('kdf')
    expect(steps[steps.length - 1]).toBe('done')
    expect(JSON.stringify(events)).not.toContain('Gizli-Parola-1')
  })

  it('JKS: depo biçimi bildirilir; boş dosya → EMPTY (sunucuya boş gövde gitmez)', async () => {
    const { r } = await run(new Uint8Array(0), 'bos.pem')
    expect(r.unsupported).toEqual({ reason: 'EMPTY' })
    const { events } = await run(new Uint8Array([0xfe, 0xed, 0xfe, 0xed, 0, 0, 0, 2, 0, 0, 0, 0]), 'bos.jks')
    expect(events).toContainEqual({ phase: 'keystore', format: 'JKS' })
  })

  it('dinleyici hatası ayıklamayı durdurmaz', async () => {
    const r = await extractCore({ bytes: new TextEncoder().encode(chain.leaf.pem), name: 'a.pem' },
      { onProgress: () => { throw new Error('dinleyici') } })
    expect(r.entries).toHaveLength(1)
  })
})

describe('extractCertificates — Web Worker ilerleme mesajları ve "Vazgeç"', () => {
  const realWorker = globalThis.Worker
  afterEach(() => { globalThis.Worker = realWorker })

  /** Sahte Worker: postMessage'tan sonra ilerleme mesajları, `hold` değilse sonuç gönderir. */
  function fakeWorker({ hold = false, result = { format: 'ZIP', entries: [], notes: [] } } = {}) {
    const made = []
    globalThis.Worker = class {
      constructor(url, opts) { this.url = String(url); this.opts = opts; this.terminated = false; this.sent = null; made.push(this) }
      postMessage(msg) {
        this.sent = msg
        setTimeout(() => {
          if (this.terminated) return
          this.onmessage?.({ data: { type: 'progress', progress: { phase: 'zip', done: 1, total: 2 } } })
          if (!hold) this.onmessage?.({ data: result })
        }, 0)
      }
      terminate() { this.terminated = true }
    }
    return made
  }

  it('ilerleme mesajları dinleyiciye iletilir (read → extract → zip), son mesaj sonuçtur; Worker sonlandırılır', async () => {
    const made = fakeWorker()
    const events = []
    const file = new File([chain.leaf.pem], 'a.zip')
    const out = await extractCertificates({ file, password: 'p' }, { onProgress: (p) => events.push(p) })
    expect(out.format).toBe('ZIP')
    expect(events.map((e) => e.phase)).toEqual(['read', 'extract', 'zip'])
    expect(made[0].url).toContain('extract.worker')
    expect(made[0].opts).toEqual({ type: 'module' })
    expect(made[0].terminated).toBe(true)
  })

  it('"Vazgeç" (AbortSignal): Worker hemen sonlandırılır, sonuç CANCELLED; zaten iptal edilmişse Worker hiç kurulmaz', async () => {
    const made = fakeWorker({ hold: true })
    const ctrl = new AbortController()
    const p = extractCertificates({ file: new File([chain.leaf.pem], 'a.pem') }, { signal: ctrl.signal })
    await vi.waitFor(() => expect(made[0]?.sent).toBeTruthy())
    ctrl.abort()
    const out = await p
    expect(out.unsupported).toEqual({ reason: 'CANCELLED' })
    expect(out.entries).toEqual([])
    expect(made[0].terminated).toBe(true)
    const pre = new AbortController()
    pre.abort()
    const out2 = await extractCertificates({ file: new File(['x'], 'b.pem') }, { signal: pre.signal })
    expect(out2.unsupported).toEqual({ reason: 'CANCELLED' })
    expect(made).toHaveLength(1)
  })

  it('süre aşımı → TIMEOUT + saniye (alan hatası süreyi söyler)', async () => {
    fakeWorker({ hold: true })
    const out = await extractCertificates({ file: new File([chain.leaf.pem], 'a.pem') }, { timeoutMs: 20 })
    expect(out.unsupported).toEqual({ reason: 'TIMEOUT', seconds: 0 })
  })
})
