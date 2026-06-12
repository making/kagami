import { HTMLAttributes, forwardRef, useEffect } from 'react';
import { cn } from '../../utils/cn';
import { X } from 'lucide-react';

interface DialogProps extends HTMLAttributes<HTMLDivElement> {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const Dialog = forwardRef<HTMLDivElement, DialogProps>(
  ({ className, open, onOpenChange, children, ...props }, ref) => {
    useEffect(() => {
      const handleEscape = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
          onOpenChange(false);
        }
      };

      if (open) {
        document.addEventListener('keydown', handleEscape);
        document.body.style.overflow = 'hidden';
      } else {
        document.body.style.overflow = 'unset';
      }

      return () => {
        document.removeEventListener('keydown', handleEscape);
        document.body.style.overflow = 'unset';
      };
    }, [open, onOpenChange]);

    if (!open) return null;

    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center">
        {/* Backdrop */}
        <div 
          className="absolute inset-0 bg-black/50 backdrop-blur-sm" 
          onClick={() => onOpenChange(false)}
        />
        
        {/* Dialog Content */}
        <div
          ref={ref}
          className={cn(
            'relative bg-paper shadow-2xl border border-line max-w-2xl w-full mx-4 max-h-[90vh] overflow-hidden',
            className
          )}
          {...props}
        >
          {children}
        </div>
      </div>
    );
  }
);
Dialog.displayName = 'Dialog';

const DialogHeader = forwardRef<HTMLDivElement, HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn('flex items-center justify-between px-6 py-4 border-b-2 border-b-ink', className)}
      {...props}
    />
  )
);
DialogHeader.displayName = 'DialogHeader';

const DialogTitle = forwardRef<HTMLHeadingElement, HTMLAttributes<HTMLHeadingElement>>(
  ({ className, ...props }, ref) => (
    <h2
      ref={ref}
      className={cn('font-sans text-lg font-bold text-ink', className)}
      {...props}
    />
  )
);
DialogTitle.displayName = 'DialogTitle';

interface DialogCloseProps extends HTMLAttributes<HTMLButtonElement> {
  onClick?: () => void;
}

const DialogClose = forwardRef<HTMLButtonElement, DialogCloseProps>(
  ({ className, onClick, ...props }, ref) => (
    <button
      ref={ref}
      type="button"
      className={cn(
        'p-2 text-ink-3 hover:text-white hover:bg-ink transition-colors cursor-pointer',
        className
      )}
      onClick={onClick}
      {...props}
    >
      <X className="h-4 w-4" />
    </button>
  )
);
DialogClose.displayName = 'DialogClose';

const DialogContent = forwardRef<HTMLDivElement, HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn('p-6 overflow-y-auto max-h-[calc(90vh-8rem)]', className)}
      {...props}
    />
  )
);
DialogContent.displayName = 'DialogContent';

export { Dialog, DialogHeader, DialogTitle, DialogClose, DialogContent };