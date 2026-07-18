import { useTranslation } from 'react-i18next'
import { useAccountData } from '@fx-platform/frontend-core'

import { translateCoreMessage } from './translateCoreMessage'

export function useTranslatedAccountData() {
  const { t } = useTranslation()
  const accountData = useAccountData()
  return {
    ...accountData,
    sessionError: translateCoreMessage(accountData.sessionError, t)
  }
}
