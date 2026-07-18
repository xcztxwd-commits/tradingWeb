import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { ThemeProvider } from '@fx-platform/ui'

import { App } from './app/App'
import { DeviceClassProvider } from './app/device/DeviceClassProvider'
import './i18n'
import '@fx-platform/ui/theme.css'
import './styles.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <ThemeProvider>
        <DeviceClassProvider>
          <App />
        </DeviceClassProvider>
      </ThemeProvider>
    </BrowserRouter>
  </React.StrictMode>
)
