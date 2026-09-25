import { useState, useEffect, useMemo } from 'react'
import { BookOpen, Plus, Pencil, Trash2, ExternalLink } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useDialog } from './ui/Dialog.jsx'
import './CertRenewalGuide.css'
import { Button } from '@/components/shadcn/button'

const emptyForm = { category: '', title: '', url: '', description: '', sortOrder: 0 }

function normalizeUrl(raw) {
  if (!raw) return '#'
  const s = String(raw).trim()
  if (/^[a-z][a-z0-9+.-]*:/i.test(s)) return s   // has any URL scheme (http, https, mailto, ftp, file, ...)
  if (s.startsWith('//')) return 'https:' + s    // protocol-relative
  return 'https://' + s.replace(/^\/+/, '')
}

export default function CertRenewalGuide({ isAdmin }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [links, setLinks] = useState([])
  const [loading, setLoading] = useState(true)
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try {
      const res = await api.guideLinks.list()
      if (res?.success) setLinks(res.data || [])
    } finally {
      setLoading(false)
    }
  }

  const grouped = useMemo(() => {
    const g = {}
    for (const link of links) {
      const cat = link.category || '—'
      if (!g[cat]) g[cat] = []
      g[cat].push(link)
    }
    return g
  }, [links])

  function openAdd() {
    setForm(emptyForm)
    setMsg(null)
    setModal('add')
  }

  function openEdit(link) {
    setForm({
      category:    link.category || '',
      title:       link.title || '',
      url:         link.url || '',
      description: link.description || '',
      // API yaniti SNAKE_CASE doner; link.sortOrder DAIMA undefined'di, yani duzenleme
      // formu kayitli siralamayi hic gostermiyordu.
      sortOrder:   link.sort_order ?? 0,
    })
    setMsg(null)
    setModal(link)
  }

  async function save() {
    if (!form.category.trim() || !form.title.trim() || !form.url.trim()) {
      setMsg(t('guide.formRequired'))
      return
    }
    setSaving(true)
    try {
      const payload = {
        category:    form.category.trim(),
        title:       form.title.trim(),
        url:         form.url.trim(),
        description: form.description.trim(),
        // Uc @RequestBody GuideLink ile bagliyor (Jackson SNAKE_CASE): camelCase anahtar
        // sessizce dusuyordu. Entity varsayilani 0 oldugu icin null-kontrolu de GECIYOR ve
        // her duzenleme siralamayi 0'a ceviriyordu.
        sort_order:  Number(form.sortOrder) || 0,
      }
      const res = modal === 'add'
        ? await api.guideLinks.create(payload)
        : await api.guideLinks.update(modal.id, payload)
      if (res?.success) {
        setModal(null)
        load()
      } else {
        setMsg(res?.error || 'Error')
      }
    } finally {
      setSaving(false)
    }
  }

  async function del(link) {
    const ok = await showConfirm({
      title: t('guide.deleteTitle'),
      message: t('guide.deleteMsg', link.title),
      variant: 'danger',
      confirmText: t('guide.deleteConfirm'),
      cancelText: t('guide.deleteCancel'),
    })
    if (!ok) return
    const res = await api.guideLinks.delete(link.id)
    if (res?.success) load()
  }

  return (
    <div className="guide-root">
      <header className="guide-header">
        <div>
          <h2>{t('guide.title')}</h2>
          <p className="guide-intro">{t('guide.intro')}</p>
        </div>
        {isAdmin && (
          <Button onClick={openAdd}>
            <Plus size={16} />
            {t('guide.addLink')}
          </Button>
        )}
      </header>

      {loading ? (
        <div className="guide-empty">{t('guide.loading')}</div>
      ) : Object.keys(grouped).length === 0 ? (
        <div className="guide-empty">
          <BookOpen size={28} style={{ opacity: .5, marginBottom: 8 }} />
          <div>{t('guide.empty')}</div>
        </div>
      ) : (
        Object.entries(grouped).map(([cat, items]) => (
          <section key={cat} className="guide-section">
            <h3 className="guide-cat">{cat}</h3>
            <ul className="guide-list">
              {items.map(link => (
                <li key={link.id} className="guide-item">
                  <div className="guide-item-body">
                    <a
                      href={normalizeUrl(link.url)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="guide-link"
                    >
                      {link.title}
                      <ExternalLink size={13} />
                    </a>
                    <a
                      href={normalizeUrl(link.url)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="guide-url"
                    >
                      {link.url}
                    </a>
                    {link.description && (
                      <p className="guide-desc">{link.description}</p>
                    )}
                  </div>
                  {isAdmin && (
                    <div className="guide-actions">
                      <Button
                        variant="secondary" size="sm"
                        onClick={() => openEdit(link)}
                        title={t('guide.edit')}
                      >
                        <Pencil size={13} />
                      </Button>
                      <Button
                        variant="destructive" size="sm"
                        onClick={() => del(link)}
                        title={t('guide.delete')}
                      >
                        <Trash2 size={13} />
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          </section>
        ))
      )}

      {modal !== null && (
        <div className="modal-overlay" onClick={() => setModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <div className="modal-icon-hdr modal-icon-hdr--user">
              <div className="modal-icon-hdr-badge">
                <BookOpen size={20} />
              </div>
              <h3>{modal === 'add' ? t('guide.addTitle') : t('guide.editTitle')}</h3>
            </div>
            <div className="form-grid">
              <label>
                <span>{t('guide.formCategory')} <span className="req-star">*</span></span>
                <input
                  value={form.category}
                  onChange={(e) => setForm({ ...form, category: e.target.value })}
                  placeholder="Netscaler / WAF / Yardımcı Araçlar"
                />
              </label>
              <label>
                <span>{t('guide.formTitle')} <span className="req-star">*</span></span>
                <input
                  value={form.title}
                  onChange={(e) => setForm({ ...form, title: e.target.value })}
                />
              </label>
              <label>
                <span>{t('guide.formUrl')} <span className="req-star">*</span></span>
                <input
                  value={form.url}
                  onChange={(e) => setForm({ ...form, url: e.target.value })}
                  placeholder="https://... veya \\sunucu\paylasim"
                />
              </label>
              <label>{t('guide.formDescription')}
                <textarea
                  rows={3}
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                />
              </label>
              <label>{t('guide.formSortOrder')}
                <input
                  type="number"
                  value={form.sortOrder}
                  onChange={(e) => setForm({ ...form, sortOrder: e.target.value })}
                />
              </label>
            </div>
            {msg && <div className="alert-msg alert-msg--err" style={{ marginTop: 8 }}>{msg}</div>}
            <div className="modal-actions">
              <Button variant="secondary" onClick={() => setModal(null)}>{t('guide.cancel')}</Button>
              <Button onClick={save} disabled={saving}>
                {saving ? t('guide.saving') : t('guide.save')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
