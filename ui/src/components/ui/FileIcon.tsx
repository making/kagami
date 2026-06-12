import { cn } from '../../utils/cn';

interface FileIconProps {
  fileName: string;
  type: 'file' | 'directory';
  className?: string;
}

/**
 * Renders the entry type as a small uppercase label (e.g. DIR, JAR, POM),
 * following the technical registry design language.
 */
export function FileIcon({ fileName, type, className }: FileIconProps) {
  const extension = fileName.split('.').pop()?.toLowerCase() || 'file';
  // Keep the label short so it fits in the narrow type column
  const label = type === 'directory' ? 'dir' : extension.length > 6 ? extension.slice(0, 5) + '…' : extension;

  return (
    <span
      className={cn(
        'registry-label font-mono',
        type === 'directory' ? 'text-accent font-semibold' : 'text-ink-3',
        className
      )}
    >
      {label}
    </span>
  );
}
