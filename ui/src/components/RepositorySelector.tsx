import { useState } from 'react';
import { useRepositories } from '../hooks/useApi';
import { LoadingSpinner } from './ui/LoadingSpinner';
import { Alert, AlertDescription } from './ui/Alert';
import { Button } from './ui/Button';
import { LockIcon } from './ui/LockIcon';
import { Database, Calendar, HardDrive, Package, Settings } from 'lucide-react';
import { formatFileSize, formatRelativeTime } from '../utils/format';
import { RepositoryConfigDialog } from './RepositoryConfigDialog';
import type { RepositoryInfo } from '../types/api';

interface RepositorySelectorProps {
  onSelectRepository: (repositoryId: string) => void;
  selectedRepository?: string;
}

export function RepositorySelector({ onSelectRepository, selectedRepository }: RepositorySelectorProps) {
  const { repositories, isLoading, error } = useRepositories();
  const [configDialog, setConfigDialog] = useState<{
    open: boolean;
    repository: RepositoryInfo | null;
  }>({
    open: false,
    repository: null,
  });

  const handleShowConfig = (repo: RepositoryInfo, event: React.MouseEvent) => {
    event.stopPropagation(); // Prevent repository selection
    setConfigDialog({
      open: true,
      repository: repo,
    });
  };

  const handleCloseConfig = () => {
    setConfigDialog({
      open: false,
      repository: null,
    });
  };

  if (isLoading) {
    return (
      <div className="flex justify-center items-center h-32">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (error) {
    const is401Error = error.message.includes('401');
    return (
      <Alert variant="error">
        <AlertDescription>
          Failed to load repositories: {error.message}
          {is401Error && (
            <div className="mt-3">
              <a 
                href="/login" 
                className="inline-flex items-center px-3 py-2 text-sm font-medium text-white bg-red-600 border border-transparent rounded-md hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
              >
                Go to Login
              </a>
            </div>
          )}
        </AlertDescription>
      </Alert>
    );
  }

  if (repositories.length === 0) {
    return (
      <Alert>
        <AlertDescription>
          No repositories configured. Please check your server configuration.
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <div className="space-y-6">
      <div className="text-center space-y-2">
        <h2 className="text-2xl font-bold bg-gradient-to-r from-red-600 to-pink-600 bg-clip-text text-transparent">
          Select Repository
        </h2>
        <p className="text-gray-500 text-sm">Choose a repository to explore</p>
      </div>
      
      <div className="space-y-4">
        {repositories.map((repo) => (
          <div
            key={repo.id}
            className={`group relative overflow-hidden rounded-xl border border-gray-200 bg-white cursor-pointer transition-all duration-300 hover:shadow-xl hover:shadow-red-500/10 hover:border-red-300 ${
              selectedRepository === repo.id ? 'ring-2 ring-red-500 shadow-lg' : ''
            }`}
            onClick={() => onSelectRepository(repo.id)}
          >            
            <div className="px-6 py-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-4">
                  <div className="p-2 rounded-lg bg-gradient-to-br from-red-500 to-red-600 shadow-lg">
                    <Database className="h-5 w-5 text-white" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center space-x-2">
                      <h3 className="font-semibold text-gray-900 text-lg group-hover:text-red-700 transition-colors">
                        {repo.id}
                      </h3>
                      {repo.isPrivate && (
                        <LockIcon className="h-4 w-4" />
                      )}
                    </div>
                    <div className="text-sm text-gray-500 truncate mt-1 font-mono">
                      {repo.url}
                    </div>
                  </div>
                </div>
                
                <div className="flex items-center space-x-4 text-sm text-gray-600">
                  <div className="flex items-center space-x-2 px-3 py-2 rounded-lg bg-gray-50 group-hover:bg-white/80 transition-colors">
                    <Package className="h-4 w-4 text-orange-500" />
                    <span className="font-medium">{repo.artifactCount.toLocaleString()}</span>
                    <span className="text-xs text-gray-400">artifacts</span>
                  </div>
                  
                  <div className="flex items-center space-x-2 px-3 py-2 rounded-lg bg-gray-50 group-hover:bg-white/80 transition-colors">
                    <HardDrive className="h-4 w-4 text-green-500" />
                    <span className="font-medium">{formatFileSize(repo.totalSize)}</span>
                  </div>
                  
                  {repo.lastUpdated && (
                    <div className="flex items-center space-x-2 px-3 py-2 rounded-lg bg-gray-50 group-hover:bg-white/80 transition-colors">
                      <Calendar className="h-4 w-4 text-red-500" />
                      <span className="font-medium">{formatRelativeTime(repo.lastUpdated)}</span>
                    </div>
                  )}
                  
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={(e) => handleShowConfig(repo, e)}
                    className="flex items-center space-x-1 px-3 py-2 rounded-lg bg-gray-50 group-hover:bg-white/80 transition-colors text-gray-600 hover:text-red-700"
                  >
                    <Settings className="h-4 w-4" />
                    <span className="text-xs">Config</span>
                  </Button>
                </div>
              </div>
            </div>
            
            {/* Bottom border gradient */}
            <div className="absolute bottom-0 left-0 right-0 h-1 bg-gradient-to-r from-red-500 to-pink-500 transform scale-x-0 group-hover:scale-x-100 transition-transform duration-300" />
          </div>
        ))}
      </div>

      <RepositoryConfigDialog
        open={configDialog.open}
        onOpenChange={handleCloseConfig}
        repository={configDialog.repository}
      />
    </div>
  );
}