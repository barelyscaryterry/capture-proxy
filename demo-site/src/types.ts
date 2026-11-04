// Mirrors test-service's Beer / CreateBeerRequest / PatchBeerRequest records.
export interface Beer {
  id: string
  name: string
  brewery: string
  style: string
  abv: number
  createdAt: string
  updatedAt: string
}

export interface CreateBeerRequest {
  name: string
  brewery: string
  style: string
  abv: number
}

export type PatchBeerRequest = Partial<CreateBeerRequest>
