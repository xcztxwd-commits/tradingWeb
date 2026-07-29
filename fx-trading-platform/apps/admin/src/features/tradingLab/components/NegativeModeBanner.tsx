export function NegativeModeBanner() {
  return (
    <aside className="trading-lab-negative-mode" role="alert">
      <strong>负向模式</strong>
      <p>
        此模式仅记录动作的 <code>expectedError</code> 预期错误元数据与实际结果，
        不自动判定本地计算是否成功。
      </p>
    </aside>
  )
}
