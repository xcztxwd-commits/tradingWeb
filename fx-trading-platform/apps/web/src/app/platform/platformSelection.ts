import type { ComponentType, LazyExoticComponent } from 'react'

import type { DeviceClass } from '../device/deviceClass.ts'

export type PlatformComponent<T> = ComponentType<{ model: T }>
  | LazyExoticComponent<ComponentType<{ model: T }>>

export function selectPlatformComponent<T>(
  deviceClass: DeviceClass,
  pc: PlatformComponent<T>,
  mobile: PlatformComponent<T>
) {
  return deviceClass === 'mobile' ? mobile : pc
}
