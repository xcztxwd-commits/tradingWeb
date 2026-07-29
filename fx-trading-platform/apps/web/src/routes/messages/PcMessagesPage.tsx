import { MessageCenterContent } from './MessageCenterContent.tsx'
import type { MessageRouteModel } from './messageRouteModel.ts'

export function PcMessagesPage({ model }: { model: MessageRouteModel }) {
  return <MessageCenterContent model={model} platform="pc" />
}
