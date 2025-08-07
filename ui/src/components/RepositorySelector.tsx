import { useRepositories } from '../hooks/useApi';
import { LoadingSpinner } from './ui/LoadingSpinner';
import { Alert, AlertDescription } from './ui/Alert';
import { Database, Calendar, HardDrive, Package } from 'lucide-react';
import { formatFileSize, formatRelativeTime } from '../utils/format';

interface RepositorySelectorProps {
  onSelectRepository: (repositoryId: string) => void;
  selectedRepository?: string;
}

export function RepositorySelector({ onSelectRepository, selectedRepository }: RepositorySelectorProps) {
  const { repositories, isLoading, error } = useRepositories();

  if (isLoading) {
    return (
      <div className="flex justify-center items-center h-32">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="error">
        <AlertDescription>
          Failed to load repositories: {error.message}
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
        <h2 className="text-2xl font-bold bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
          Select Repository
        </h2>
        <p className="text-gray-500 text-sm">Choose a repository to explore</p>
      </div>
      
      <div className="space-y-4">
        {repositories.map((repo) => (
          <div
            key={repo.id}
            className={`group relative overflow-hidden rounded-xl border border-gray-200 bg-white cursor-pointer transition-all duration-300 hover:shadow-xl hover:shadow-blue-500/10 hover:border-blue-300 ${
              selectedRepository === repo.id ? 'ring-2 ring-blue-500 shadow-lg' : ''
            }`}
            onClick={() => onSelectRepository(repo.id)}
          >            
            <div className="px-6 py-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-4">
                  <div className="p-2 rounded-lg bg-gradient-to-br from-blue-500 to-blue-600 shadow-lg">
                    <Database className="h-5 w-5 text-white" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <h3 className="font-semibold text-gray-900 text-lg group-hover:text-blue-700 transition-colors">
                      {repo.id}
                    </h3>
                    <div className="text-sm text-gray-500 truncate mt-1 font-mono">
                      {repo.url}
                    </div>
                  </div>
                </div>
                
                <div className="flex items-center space-x-8 text-sm text-gray-600">
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
                      <Calendar className="h-4 w-4 text-blue-500" />
                      <span className="font-medium">{formatRelativeTime(repo.lastUpdated)}</span>
                    </div>
                  )}
                </div>
              </div>
            </div>
            
            {/* Bottom border gradient */}
            <div className="absolute bottom-0 left-0 right-0 h-1 bg-gradient-to-r from-blue-500 to-purple-500 transform scale-x-0 group-hover:scale-x-100 transition-transform duration-300" />
          </div>
        ))}
      </div>
    </div>
  );
}