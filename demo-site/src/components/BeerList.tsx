import { useMemo, useState } from 'react'
import type { Beer } from '../types'

interface Props {
  beers: Beer[]
  selectedId: string | null
  onSelect: (id: string) => void
}

type Sort = 'name' | 'abv-desc' | 'abv-asc'

// test-service has no query parameters on GET /api/beers, so filtering and sorting happen here.
export function BeerList({ beers, selectedId, onSelect }: Props) {
  const [search, setSearch] = useState('')
  const [style, setStyle] = useState('')
  const [sort, setSort] = useState<Sort>('name')

  const styles = useMemo(() => [...new Set(beers.map((b) => b.style))].sort(), [beers])

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase()
    return beers
      .filter((b) => !style || b.style === style)
      .filter((b) => !q || b.name.toLowerCase().includes(q) || b.brewery.toLowerCase().includes(q))
      .sort((a, b) =>
        sort === 'name'
          ? a.name.localeCompare(b.name)
          : sort === 'abv-desc'
            ? b.abv - a.abv
            : a.abv - b.abv,
      )
  }, [beers, search, style, sort])

  return (
    <section className="panel list-panel">
      <div className="filters">
        <input
          type="search"
          placeholder="Search name or brewery"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select value={style} onChange={(e) => setStyle(e.target.value)} aria-label="Style">
          <option value="">All styles</option>
          {styles.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <select value={sort} onChange={(e) => setSort(e.target.value as Sort)} aria-label="Sort">
          <option value="name">Name A–Z</option>
          <option value="abv-desc">Strongest first</option>
          <option value="abv-asc">Lightest first</option>
        </select>
      </div>

      {visible.length === 0 ? (
        <p className="muted empty">
          {beers.length === 0 ? 'No beers yet. Add the first one.' : 'No beers match those filters.'}
        </p>
      ) : (
        <ul className="beer-list">
          {visible.map((b) => (
            <li key={b.id}>
              <button
                className={b.id === selectedId ? 'beer-row selected' : 'beer-row'}
                onClick={() => onSelect(b.id)}
              >
                <span className="beer-name">{b.name}</span>
                <span className="muted">{b.brewery}</span>
                <span className="tag">{b.style}</span>
                <span className="abv">{b.abv}%</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
