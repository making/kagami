import { Lock } from 'lucide-react';

interface LockIconProps {
  className?: string;
}

export function LockIcon({ className = "h-4 w-4" }: LockIconProps) {
  return <Lock className={`${className} text-red-600`} />;
}