import { useState, type FormEvent } from 'react'
import type { CreateBeerRequest } from '../types'

interface Props {
  initial?: CreateBeerRequest
  submitLabel: string
  onSubmit: (values: CreateBeerRequest) => Promise<void>
  onCancel?: () => void
}

const EMPTY: CreateBeerRequest = { name: '', brewery: '', style: '', abv: 5 }

// Shared by AddBeerForm (POST) and EditBeer (PATCH).
export function BeerForm({ initial = EMPTY, submitLabel, onSubmit, onCancel }: Props) {
  const [values, setValues] = useState(initial)
  const [abvText, setAbvText] = useState(String(initial.abv))
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const set = (field: keyof CreateBeerRequest) => (value: string) =>
    setValues((v) => ({ ...v, [field]: value }))

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const abv = Number(abvText)
    if (!values.name.trim() || !values.brewery.trim() || !values.style.trim()) {
      setError('Name, brewery and style are all required.')
      return
    }
    if (abvText.trim() === '' || Number.isNaN(abv) || abv < 0) {
      setError('ABV must be a number, 0 or more.')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await onSubmit({
        name: values.name.trim(),
        brewery: values.brewery.trim(),
        style: values.style.trim(),
        abv,
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="beer-form" onSubmit={handleSubmit} noValidate>
      <label>
        Name
        <input value={values.name} onChange={(e) => set('name')(e.target.value)} />
      </label>
      <label>
        Brewery
        <input value={values.brewery} onChange={(e) => set('brewery')(e.target.value)} />
      </label>
      <label>
        Style
        <input
          value={values.style}
          onChange={(e) => set('style')(e.target.value)}
          placeholder="IPA, Stout, Pilsner…"
        />
      </label>
      <label>
        ABV (%)
        <input
          inputMode="decimal"
          value={abvText}
          onChange={(e) => setAbvText(e.target.value)}
        />
      </label>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        {onCancel && (
          <button type="button" className="secondary" onClick={onCancel} disabled={submitting}>
            Cancel
          </button>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Saving…' : submitLabel}
        </button>
      </div>
    </form>
  )
}
