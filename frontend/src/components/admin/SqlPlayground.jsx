import { useState, useEffect, useCallback, useRef } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import {
  Play, Download, History, BookOpen, ChevronRight, ChevronDown,
  Database, Loader2, AlertCircle, X,
} from 'lucide-react'

const DEFAULT_QUERY = ''

export default function SqlPlayground() {
  const t = useT()
  const toast = useToast()

  const [tables, setTables]         = useState([])
  const [expandedTable, setExpanded] = useState(null)
  const [columnsMap, setColumnsMap] = useState({})
  const [sql, setSql]               = useState(DEFAULT_QUERY)
  const [running, setRunning]       = useState(false)
  const [result, setResult]         = useState(null)
  const [rightTab, setRightTab]     = useState('samples')
  const [samples, setSamples]       = useState([])
  const [history, setHistory]       = useState([])
  const editorRef = useRef(null)

  useEffect(() => {
    api.admin.sqlListTables().then(r => r?.success && setTables(r.data ?? []))
    api.admin.sqlSamples().then(r => r?.success && setSamples(r.data ?? []))
    loadHistory()
  }, [])

  const loadHistory = () =>
    api.admin.sqlHistory().then(r => r?.success && setHistory(r.data ?? []))

  const toggleTable = async (name) => {
    if (expandedTable === name) { setExpanded(null); return }
    setExpanded(name)
    if (!columnsMap[name]) {
      const r = await api.admin.sqlListColumns(name)
      if (r?.success) setColumnsMap(m => ({ ...m, [name]: r.data ?? [] }))
    }
  }

  const onTableClick = (name) => {
    setSql(`SELECT *\nFROM ${name}\nLIMIT 100;`)
    setTimeout(() => editorRef.current?.focus(), 0)
  }

  const onColumnClick = (col, e) => {
    e.stopPropagation()
    navigator.clipboard?.writeText(col)
    toast.success(t('sql.colCopied', col))
  }

  const run = useCallback(async () => {
    if (!sql.trim() || running) return
    setRunning(true)
    setResult(null)
    const r = await api.admin.sqlExecute(sql)
    setRunning(false)
    setResult(r)
    loadHistory()
    if (r?.error) toast.error(r.error)
  }, [sql, running, toast])

  const onEditorKeyDown = (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      run()
    }
  }

  const exportCsv = () => {
    if (!result?.rows?.length) return
    const cols = Object.keys(result.rows[0])
    const escape = (v) => {
      const s = v == null ? '' : String(v)
      return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
    }
    const csv = [
      cols.join(','),
      ...result.rows.map(r => cols.map(c => escape(r[c])).join(',')),
    ].join('\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `query-${Date.now()}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  const clearEditor = () => setSql('')

  const cols = result?.rows?.length ? Object.keys(result.rows[0]) : []

  return (
    <div className="sqlpg">
      <div className="sqlpg-pane sqlpg-schema">
        <div className="sqlpg-pane-header">
          <Database size={14} /> {t('sql.schema')}
          <span className="sqlpg-tables-count">{tables.length}</span>
        </div>
        <div className="sqlpg-tree">
          {tables.map(tbl => (
            <div key={tbl.table_name}>
              <button
                className={`sqlpg-table-btn${expandedTable === tbl.table_name ? ' is-expanded' : ''}`}
                onClick={() => onTableClick(tbl.table_name)}
              >
                <span
                  className="sqlpg-table-chev"
                  onClick={(e) => { e.stopPropagation(); toggleTable(tbl.table_name) }}
                  title={t('sql.toggleCols')}
                >
                  {expandedTable === tbl.table_name
                    ? <ChevronDown size={12} />
                    : <ChevronRight size={12} />}
                </span>
                <span className="sqlpg-table-name">{tbl.table_name}</span>
              </button>
              {expandedTable === tbl.table_name && (columnsMap[tbl.table_name] ?? []).map(c => (
                <button
                  key={c.column_name}
                  className="sqlpg-col-btn"
                  onClick={(e) => onColumnClick(c.column_name, e)}
                  title={`${c.data_type}${c.is_nullable === 'YES' ? ' · NULL' : ''}`}
                >
                  <span className="sqlpg-col-name">{c.column_name}</span>
                  <span className="sqlpg-col-type">{c.data_type}</span>
                </button>
              ))}
            </div>
          ))}
        </div>
      </div>

      <div className="sqlpg-pane sqlpg-main">
        <div className="sqlpg-toolbar">
          <button
            className="btn btn-primary sqlpg-run"
            onClick={run}
            disabled={running || !sql.trim()}
          >
            {running ? <Loader2 size={14} className="spin" /> : <Play size={14} />}
            {t('sql.run')}
            <kbd className="sqlpg-kbd">Ctrl+Enter</kbd>
          </button>
          <button
            className="btn btn-secondary"
            onClick={exportCsv}
            disabled={!result?.rows?.length}
          >
            <Download size={14} /> {t('sql.exportCsv')}
          </button>
          <button
            className="btn btn-secondary"
            onClick={clearEditor}
            disabled={!sql}
            title={t('sql.clear')}
          >
            <X size={14} /> {t('sql.clear')}
          </button>
          <span className="sqlpg-limit-hint">{t('sql.limitHint')}</span>
        </div>

        <textarea
          ref={editorRef}
          className="sqlpg-editor"
          value={sql}
          onChange={e => setSql(e.target.value)}
          onKeyDown={onEditorKeyDown}
          placeholder={t('sql.editorPlaceholder')}
          spellCheck={false}
        />

        {result && (
          <div className="sqlpg-result">
            <div className="sqlpg-result-meta">
              {result.error ? (
                <span className="sqlpg-result-err">
                  <AlertCircle size={13} /> {result.error}
                </span>
              ) : (
                <>
                  <strong>{t('sql.rowsCount', result.rowCount)}</strong>
                  <span className="sqlpg-result-dur">· {result.durationMs} ms</span>
                  {result.executedSql && (
                    <span className="sqlpg-executed-sql" title={result.executedSql}>
                      · {result.executedSql.length > 80
                          ? result.executedSql.substring(0, 80) + '…'
                          : result.executedSql}
                    </span>
                  )}
                </>
              )}
            </div>
            {!result.error && cols.length > 0 && (
              <div className="sqlpg-result-table-wrap">
                <table className="sqlpg-result-table">
                  <thead>
                    <tr>{cols.map(c => <th key={c}>{c}</th>)}</tr>
                  </thead>
                  <tbody>
                    {result.rows.map((row, i) => (
                      <tr key={i}>
                        {cols.map(c => (
                          <td key={c} title={row[c] == null ? 'NULL' : String(row[c])}>
                            {row[c] == null
                              ? <em className="sqlpg-null">NULL</em>
                              : String(row[c])}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {!result.error && result.rowCount === 0 && (
              <div className="sqlpg-result-empty">{t('sql.noRows')}</div>
            )}
          </div>
        )}
      </div>

      <div className="sqlpg-pane sqlpg-side">
        <div className="sqlpg-side-tabs">
          <button
            className={`sqlpg-side-tab${rightTab === 'samples' ? ' is-active' : ''}`}
            onClick={() => setRightTab('samples')}
          >
            <BookOpen size={13} /> {t('sql.samples')}
          </button>
          <button
            className={`sqlpg-side-tab${rightTab === 'history' ? ' is-active' : ''}`}
            onClick={() => setRightTab('history')}
          >
            <History size={13} /> {t('sql.history')}
          </button>
        </div>
        <div className="sqlpg-side-list">
          {rightTab === 'samples' && samples.map((s, i) => (
            <button
              key={i}
              className="sqlpg-item"
              onClick={() => setSql(s.sql)}
              title={s.sql}
            >
              <div className="sqlpg-item-label">{s.label}</div>
              <div className="sqlpg-item-preview">{s.sql.substring(0, 80)}…</div>
            </button>
          ))}
          {rightTab === 'samples' && samples.length === 0 && (
            <div className="sqlpg-side-empty">{t('sql.noSamples')}</div>
          )}

          {rightTab === 'history' && history.map(h => (
            <button
              key={h.id}
              className={`sqlpg-item${!h.success ? ' is-error' : ''}`}
              onClick={() => setSql(h.sqlText)}
              title={h.sqlText}
            >
              <div className="sqlpg-item-label">
                <strong>{h.executedBy}</strong>
                <span className="sqlpg-item-meta">
                  {h.rowCount ?? '—'} · {h.durationMs ?? 0} ms
                </span>
              </div>
              <div className="sqlpg-item-preview">
                {(h.sqlText ?? '').substring(0, 80)}…
              </div>
              <div className="sqlpg-item-date">{formatDate(h.executedAt)}</div>
            </button>
          ))}
          {rightTab === 'history' && history.length === 0 && (
            <div className="sqlpg-side-empty">{t('sql.noHistory')}</div>
          )}
        </div>
      </div>
    </div>
  )
}
