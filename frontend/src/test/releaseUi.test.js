import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  fmtDuration, groupChanges, changeGroup, isNewVersion, readLastSeenVersion, writeLastSeenVersion,
  LAST_SEEN_KEY, shortSha, bumpIcon, DEPLOY_KIND_STYLE,
} from '../utils/releaseUi.js'

const U = { d: 'g', h: 's', m: 'd' }

describe('releaseUi — süre biçimi', () => {
  it('60 sn altı → "az önce"; saat/dakika; gün+saat (gün varken dakika düşer)', () => {
    expect(fmtDuration(5, U, 'az önce')).toBe('az önce')
    expect(fmtDuration(90, U)).toBe('1d')
    expect(fmtDuration(3600 * 4 + 60 * 12, U)).toBe('4s 12d')
    expect(fmtDuration(86400 * 3 + 3600 * 4 + 60 * 12, U)).toBe('3g 4s')
    expect(fmtDuration(-1, U)).toBe('')
    expect(fmtDuration(undefined, U)).toBe('')
  })
})

describe('releaseUi — değişiklik gruplama', () => {
  it('feat → fix → other sırası, tavan ve "daha" sayısı', () => {
    const changes = [
      { type: 'fix', subject: 'a' }, { type: 'feat', subject: 'b' }, { type: 'docs', subject: 'c' },
      { type: 'feat', subject: 'd' }, { type: 'feat', subject: 'e' },
    ]
    const g = groupChanges(changes, 2)
    expect(g.map(x => x.key)).toEqual(['feat', 'fix', 'other'])
    expect(g[0].items.length).toBe(2)
    expect(g[0].more).toBe(1)
    expect(changeGroup('perf')).toBe('other')
    expect(groupChanges([])).toEqual([])
  })
})

describe('releaseUi — yeni sürüm damgası (E1)', () => {
  beforeEach(() => { try { localStorage.clear() } catch {} })

  it('ilk ziyarette damga yok → yeni sayılmaz; damga farklıysa yeni', () => {
    expect(isNewVersion('', '20.53.2')).toBe(false)
    expect(isNewVersion('20.53.2', '20.53.2')).toBe(false)
    expect(isNewVersion('20.53.1', '20.53.2')).toBe(true)
  })

  it('depolama okur/yazar; boş değer yazılmaz', () => {
    writeLastSeenVersion('20.53.2')
    expect(readLastSeenVersion()).toBe('20.53.2')
    expect(localStorage.getItem(LAST_SEEN_KEY)).toBe('20.53.2')
    writeLastSeenVersion('')
    expect(readLastSeenVersion()).toBe('20.53.2')
  })

  it('depolama fırlatırsa çökmez (storage-disabled sözleşmesi)', () => {
    const orig = Object.getOwnPropertyDescriptor(window, 'localStorage')
    Object.defineProperty(window, 'localStorage', { configurable: true, get() { throw new Error('blocked') } })
    try {
      expect(readLastSeenVersion()).toBe('')
      expect(() => writeLastSeenVersion('1.0.0')).not.toThrow()
    } finally {
      if (orig) Object.defineProperty(window, 'localStorage', orig)
    }
  })
})

describe('releaseUi — küçük yardımcılar', () => {
  it('shortSha 8 karakter; bumpIcon bilinmeyende yedek ikon; her tür stilinde ikon+ton', () => {
    expect(shortSha('0123456789abcdef')).toBe('01234567')
    expect(shortSha(null)).toBe('')
    expect(typeof bumpIcon('major')).toBe('object')
    expect(bumpIcon('weird')).toBe(bumpIcon(undefined))
    for (const k of ['FIRST_SEEN', 'UPGRADE', 'RESTART', 'ROLLBACK', 'CHANGED', 'UNKNOWN', 'RELEASE']) {
      expect(DEPLOY_KIND_STYLE[k].icon).toBeTruthy()
      expect(typeof DEPLOY_KIND_STYLE[k].tone).toBe('string')
    }
    vi.restoreAllMocks()
  })
})
