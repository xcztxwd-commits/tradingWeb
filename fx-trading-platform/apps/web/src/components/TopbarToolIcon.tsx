import type { ImgHTMLAttributes } from 'react'

import bellIcon from '../assets/topbar-c-icons/bell.svg'
import downloadIcon from '../assets/topbar-c-icons/download.svg'
import globeIcon from '../assets/topbar-c-icons/globe.svg'
import moonIcon from '../assets/topbar-c-icons/moon.svg'
import searchIcon from '../assets/topbar-c-icons/search.svg'
import supportIcon from '../assets/topbar-c-icons/support.svg'
import userIcon from '../assets/topbar-c-icons/user.svg'
import walletIcon from '../assets/topbar-c-icons/wallet.svg'
import styles from './TopbarToolIcon.module.css'

export type TopbarToolIconName =
  | 'search'
  | 'user'
  | 'wallet'
  | 'bell'
  | 'support'
  | 'download'
  | 'globe'
  | 'moon'

type TopbarToolIconProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'alt' | 'src'> & {
  name: TopbarToolIconName
  size?: number
}

const iconSources: Record<TopbarToolIconName, string> = {
  search: searchIcon,
  user: userIcon,
  wallet: walletIcon,
  bell: bellIcon,
  support: supportIcon,
  download: downloadIcon,
  globe: globeIcon,
  moon: moonIcon
}

export function TopbarToolIcon({ className, name, size = 30, ...props }: TopbarToolIconProps) {
  return (
    <img
      alt=""
      aria-hidden="true"
      className={[styles.image, className].filter(Boolean).join(' ')}
      draggable={false}
      height={size}
      src={iconSources[name]}
      width={size}
      {...props}
    />
  )
}
