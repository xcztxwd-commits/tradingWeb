import styles from './BottomAccountPanel.module.css'

export function EmptyState({ label }: { label: string }) {
  return <div className={styles.empty}>{label}</div>
}
