import type { LucideIcon } from 'lucide-react'
import './PageHeader.css'

interface PageHeaderProps {
  icon: LucideIcon
  title: string
  subtitle?: string
  children?: React.ReactNode
}

export default function PageHeader({ icon: Icon, title, subtitle, children }: PageHeaderProps) {
  return (
    <div className="page-header">
      <div className="page-header-left">
        <Icon size={20} className="page-header-icon" />
        <div>
          <h2>{title}</h2>
          {subtitle && <p>{subtitle}</p>}
        </div>
      </div>
      {children && <div className="page-header-right">{children}</div>}
    </div>
  )
}
