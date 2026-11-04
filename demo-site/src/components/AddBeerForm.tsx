import { beerApi } from '../api'
import type { Beer } from '../types'
import { BeerForm } from './BeerForm'

interface Props {
  onCreated: (beer: Beer) => void
  onCancel: () => void
}

export function AddBeerForm({ onCreated, onCancel }: Props) {
  return (
    <section className="panel">
      <h2>Add a beer</h2>
      <BeerForm
        submitLabel="Add beer"
        onCancel={onCancel}
        onSubmit={async (values) => onCreated(await beerApi.create(values))}
      />
    </section>
  )
}
