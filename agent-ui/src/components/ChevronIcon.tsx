/**
 * 折叠/展开箭头图标——Sidebar 和 QuestionNav 共用。
 *
 * @author 陈龙
 * @since 2026-06-01
 */

interface Props {
  direction: 'left' | 'right'
  size?: number
}

export default function ChevronIcon({ direction, size = 16 }: Props) {
  const points = direction === 'left' ? '15 18 9 12 15 6' : '9 18 15 12 9 6'
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points={points} />
    </svg>
  )
}
