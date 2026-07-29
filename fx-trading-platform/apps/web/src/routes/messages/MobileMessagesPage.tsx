import { MessageCenterContent } from './MessageCenterContent.tsx'
import type { MessageRouteModel } from './messageRouteModel.ts'

export function MobileMessagesPage({ model }: { model: MessageRouteModel }) {
  return <MessageCenterContent model={model} platform="mobile" />
}
