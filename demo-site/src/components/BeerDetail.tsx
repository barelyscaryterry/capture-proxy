import { useEffect, useState } from 'react'
import { beerApi } from '../api'
import type { Beer } from '../types'
import { EditBeer } from './EditBeer'

interface Props {
  id: string
  onUpdated: (beer: Beer) => void
}

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

// Always re-fetches by id (GET /api/beers/{id}) so the detail view reflects the backend,
// not whatever the list happened to load.
export function BeerDetail({ id, onUpdated }: Props) {
  const [beer, setBeer] = useState<Beer | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)

  useEffect(() => {
    let cancelled = false
    setBeer(null)
    setError(null)
    setEditing(false)
    beerApi
      .get(id)
      .then((b) => !cancelled && setBeer(b))
      .catch((e: Error) => !cancelled && setError(e.message))
    return () => {
      cancelled = true
    }
  }, [id])

  if (error) {
    return (
      <section className="panel">
        <p className="error" role="alert">
          {error}
        </p>
      </section>
    )
  }
  if (!beer) {
    return <section className="panel muted">Loading…</section>
  }

  return (
    <section className="panel">
      {editing ? (
        <>
          <h2>Edit {beer.name}</h2>
          <EditBeer
            beer={beer}
            onCancel={() => setEditing(false)}
            onSaved={(saved) => {
              setBeer(saved)
              setEditing(false)
              onUpdated(saved)
            }}
          />
        </>
      ) : (
        <>
          <div className="detail-header">
            <div>
              <h2>{beer.name}</h2>
              <p className="muted">{beer.brewery}</p>
            </div>
            <button className="secondary" onClick={() => setEditing(true)}>
              Edit
            </button>
          </div>
          <dl className="facts">
            <div>
              <dt>Style</dt>
              <dd>{beer.style}</dd>
            </div>
            <div>
              <dt>ABV</dt>
              <dd>{beer.abv}%</dd>
            </div>
            <div>
              <dt>Added</dt>
              <dd>{dateFormat.format(new Date(beer.createdAt))}</dd>
            </div>
            <div>
              <dt>Updated</dt>
              <dd>{dateFormat.format(new Date(beer.updatedAt))}</dd>
            </div>
          </dl>
          <p className="muted small">ID {beer.id}</p>
        </>
      )}
    </section>
  )
}
