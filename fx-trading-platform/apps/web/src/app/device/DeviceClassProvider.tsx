import {
  createContext,
  useContext,
  useState,
  useSyncExternalStore,
  type ReactNode
} from 'react'

import { createDeviceClassStore, type DeviceClass, type DeviceClassStore } from './deviceClass'

const DeviceClassContext = createContext<DeviceClass>('pc')

type DeviceClassProviderProps = {
  children: ReactNode
  store?: DeviceClassStore
}

export function DeviceClassProvider({ children, store }: DeviceClassProviderProps) {
  const [deviceStore] = useState(() => store ?? createDeviceClassStore())
  const deviceClass = useSyncExternalStore(
    deviceStore.subscribe,
    deviceStore.getSnapshot,
    deviceStore.getServerSnapshot
  )

  return (
    <DeviceClassContext.Provider value={deviceClass}>
      {children}
    </DeviceClassContext.Provider>
  )
}

export function useDeviceClass() {
  return useContext(DeviceClassContext)
}
