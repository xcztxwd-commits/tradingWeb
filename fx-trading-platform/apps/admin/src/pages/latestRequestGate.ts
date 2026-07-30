export type LatestRequestToken = Readonly<{
  generation: number
}>

export function createLatestRequestGate() {
  let generation = 0

  return {
    begin(): LatestRequestToken {
      generation += 1
      return { generation }
    },
    isCurrent(token: LatestRequestToken) {
      return token.generation === generation
    }
  }
}
