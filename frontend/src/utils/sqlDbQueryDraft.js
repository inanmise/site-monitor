import { personalKey } from './personalStorage.js'

/**
 * SQL Playground → Veritabanı sorgu izlemesi taslağı (2026-11-13, D4). Tek kullanımlık: Playground SQL'i
 * `sessionStorage`'a (kullanıcıya bağlı anahtar) yazar ve `?tab=dbquery`'ye gider; izleme sayfası taslağı BİR KEZ okur,
 * anahtarı siler ve yeni izleme formunu SQL dolu açar (bağlantı / kurallar formda seçilir; "amaç" boş). Bayat taslak
 * ({@link DRAFT_TTL_MS}) yok sayılır. Parametreli (`:ad`) sorgu aktarılmaz — çağıran engeller.
 */
export const DBQ_DRAFT_KEY = 'sm.dbq.fromSql'
export const DRAFT_TTL_MS = 10 * 60 * 1000
export const DRAFT_SQL_MAX = 100_000

function session() {
  try { return globalThis.sessionStorage || null } catch { return null }
}

/** Taslağı yazar. @returns başarılı mı */
export function writeDbQueryDraft(sql, now = Date.now()) {
  const text = String(sql ?? '')
  if (!text.trim() || text.length > DRAFT_SQL_MAX) return false
  try {
    session()?.setItem(personalKey(DBQ_DRAFT_KEY), JSON.stringify({ sql: text, at: now, from: 'sqlplayground' }))
    return true
  } catch { return false }
}

/** Taslağı alır ve SİLER (tek kullanım). Geçersiz / bayat / yoksa null. */
export function takeDbQueryDraft(now = Date.now()) {
  const s = session()
  if (!s) return null
  const key = personalKey(DBQ_DRAFT_KEY)
  let raw = null
  try { raw = s.getItem(key); s.removeItem(key) } catch { return null }
  if (!raw) return null
  try {
    const d = JSON.parse(raw)
    if (!d || typeof d.sql !== 'string' || !d.sql.trim() || d.sql.length > DRAFT_SQL_MAX) return null
    if (!Number.isFinite(d.at) || now - d.at > DRAFT_TTL_MS || d.at - now > 60_000) return null
    return { sql: d.sql, from: d.from === 'sqlplayground' ? 'sqlplayground' : null }
  } catch { return null }
}
