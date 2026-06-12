import { ButtonHTMLAttributes, forwardRef } from 'react';
import { cn } from '../../utils/cn';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
}

const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'primary', size = 'md', ...props }, ref) => {
    return (
      <button
        className={cn(
          // Base styles: sharp corners, mono uppercase, registry look
          'inline-flex items-center justify-center font-mono uppercase tracking-[0.14em] transition-colors cursor-pointer',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
          'disabled:pointer-events-none disabled:opacity-50',

          // Variants
          variant === 'primary' &&
            'bg-gradient-to-r from-accent to-magenta text-white font-semibold hover:brightness-110',
          variant === 'secondary' &&
            'border border-line bg-transparent text-ink-2 hover:bg-ink hover:border-ink hover:text-white',
          variant === 'ghost' && 'text-ink-2 hover:bg-ink hover:text-white',

          // Sizes
          size === 'sm' && 'h-8 px-3 text-[10px]',
          size === 'md' && 'h-10 px-5 text-xs',
          size === 'lg' && 'h-12 px-7 text-sm',

          className
        )}
        ref={ref}
        {...props}
      />
    );
  }
);

Button.displayName = 'Button';

export { Button };
