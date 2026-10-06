import type { ButtonHTMLAttributes } from 'react'

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'accent'
  shimmer?: boolean
}

export default function Button({ variant = 'primary', shimmer = false, className = '', children, ...props }: Props) {
  return (
    <button className={`button button--${variant} ${className}`} {...props}>
      <span className="relative z-10">{children}</span>
      {shimmer && !props.disabled && <span className="button-shimmer" aria-hidden="true" />}
    </button>
  )
}
