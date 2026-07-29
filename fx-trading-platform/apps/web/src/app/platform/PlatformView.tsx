import { Suspense, type ReactNode } from 'react'

import { useDeviceClass } from '../device/DeviceClassProvider'
import { selectPlatformComponent, type PlatformComponent } from './platformSelection'
import styles from './PlatformView.module.css'

export { selectPlatformComponent } from './platformSelection'

type PlatformViewProps<T> = {
  model: T
  pc: PlatformComponent<T>
  mobile: PlatformComponent<T>
  fallback: ReactNode
}

export function PlatformView<T>({ model, pc, mobile, fallback }: PlatformViewProps<T>) {
  const deviceClass = useDeviceClass()
  const ActiveView = selectPlatformComponent(deviceClass, pc, mobile)

  return (
    <div className={styles.root} data-platform-view={deviceClass}>
      <Suspense fallback={fallback}>
        <ActiveView model={model} />
      </Suspense>
    </div>
  )
}
