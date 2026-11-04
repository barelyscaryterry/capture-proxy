import { beerApi } from '../api'
import type { Beer } from '../types'
import { BeerForm } from './BeerForm'

interface Props {
  beer: Beer
  onSaved: (beer: Beer) => void
  onCancel: () => void
}

export function EditBeer({ beer, onSaved, onCancel }: Props) {
  return (
    <BeerForm
      initial={{ name: beer.name, brewery: beer.brewery, style: beer.style, abv: beer.abv }}
      submitLabel="Save changes"
      onCancel={onCancel}
      onSubmit={async (values) => {
        // PATCH only what changed - test-service applies non-null fields only.
        const changes = Object.fromEntries(
          Object.entries(values).filter(([key, value]) => beer[key as keyof Beer] !== value),
        )
        onSaved(Object.keys(changes).length ? await beerApi.patch(beer.id, changes) : beer)
      }}
    />
  )
}
