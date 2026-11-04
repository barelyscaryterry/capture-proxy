import { useCallback, useEffect, useState } from 'react'
import { beerApi } from './api'
import { AddBeerForm } from './components/AddBeerForm'
import { BeerDetail } from './components/BeerDetail'
import { BeerList } from './components/BeerList'
import type { Beer } from './types'

type View = { kind: 'empty' } | { kind: 'add' } | { kind: 'detail'; id: string }

export default function App() {
  const [beers, setBeers] = useState<Beer[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<View>({ kind: 'empty' })

  const load = useCallback(() => {
    setLoading(true)
    setError(null)
    beerApi
      .list()
      .then(setBeers)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  useEffect(load, [load])

  const upsert = (beer: Beer) =>
    setBeers((list) => [...list.filter((b) => b.id !== beer.id), beer])

  return (
    <div className="app">
      <header>
        <h1>Beer Cellar</h1>
        <div className="header-actions">
          <button className="secondary" onClick={load} disabled={loading}>
            Refresh
          </button>
          <button onClick={() => setView({ kind: 'add' })}>Add beer</button>
        </div>
      </header>

      {error && (
        <div className="banner error" role="alert">
          {error}
          <button className="secondary" onClick={load}>
            Retry
          </button>
        </div>
      )}

      <main>
        {loading && beers.length === 0 ? (
          <section className="panel muted">Loading beers…</section>
        ) : (
          <BeerList
            beers={beers}
            selectedId={view.kind === 'detail' ? view.id : null}
            onSelect={(id) => setView({ kind: 'detail', id })}
          />
        )}

        {view.kind === 'add' && (
          <AddBeerForm
            onCancel={() => setView({ kind: 'empty' })}
            onCreated={(beer) => {
              upsert(beer)
              setView({ kind: 'detail', id: beer.id })
            }}
          />
        )}
        {view.kind === 'detail' && <BeerDetail id={view.id} onUpdated={upsert} />}
        {view.kind === 'empty' && (
          <section className="panel muted placeholder">Pick a beer to see its details.</section>
        )}
      </main>
    </div>
  )
}
