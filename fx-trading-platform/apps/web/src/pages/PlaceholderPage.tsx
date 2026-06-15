import { useTranslation } from 'react-i18next'

type Props = {
  title: string
}

export function PlaceholderPage({ title }: Props) {
  const { t } = useTranslation()

  return (
    <section className="page-placeholder">
      <h1>{title}</h1>
      <p>{t('common.placeholderPageMessage')}</p>
    </section>
  )
}
