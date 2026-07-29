type ClassToken = string | false | null | undefined

export function cssModuleClasses(
  styles: Readonly<Record<string, string>>,
  ...tokens: ClassToken[]
) {
  return tokens
    .filter((token): token is string => Boolean(token))
    .flatMap((token) => token.split(/\s+/u))
    .map((token) => {
      const className = styles[token]
      if (!className) throw new Error(`Missing CSS Module class: ${token}`)
      return className
    })
    .join(' ')
}
