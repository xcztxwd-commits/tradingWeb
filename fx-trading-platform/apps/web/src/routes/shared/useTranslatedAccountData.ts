import { useTranslation } from 'react-i18next'
import { useAccountData } from '@fx-platform/frontend-core'

import { translateCoreMessage } from './translateCoreMessage'

export function useTranslatedAccountData(enabled = true) {
  const { t } = useTranslation()
  const accountData = useAccountData({ enabled })
  return {
    ...accountData,
    sessionError: translateCoreMessage(accountData.sessionError, t)
  }
}

export type TranslatedAccountData = ReturnType<typeof useTranslatedAccountData>
