import { ChevronRight, Home } from 'lucide-react';
import { Button } from './ui/Button';

interface BreadcrumbProps {
  repositoryId: string;
  currentPath: string;
  onNavigate: (path: string) => void;
}

export function Breadcrumb({ repositoryId, currentPath, onNavigate }: BreadcrumbProps) {
  const pathParts = currentPath ? currentPath.split('/').filter(Boolean) : [];
  
  return (
    <nav className="flex items-center space-x-1 text-sm text-gray-600">
      <Button
        variant="ghost"
        size="sm"
        onClick={() => onNavigate('')}
        className="flex items-center space-x-1 px-2 py-1"
      >
        <Home className="h-4 w-4" />
        <span>{repositoryId}</span>
      </Button>
      
      {pathParts.map((part, index) => {
        const pathToHere = pathParts.slice(0, index + 1).join('/');
        const isLast = index === pathParts.length - 1;
        
        return (
          <div key={index} className="flex items-center space-x-1">
            <ChevronRight className="h-4 w-4 text-gray-400" />
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onNavigate(pathToHere)}
              className={`px-2 py-1 ${isLast ? 'font-medium text-gray-900' : ''}`}
              disabled={isLast}
            >
              {part}
            </Button>
          </div>
        );
      })}
    </nav>
  );
}