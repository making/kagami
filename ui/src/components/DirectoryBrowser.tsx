import { useBrowseRepository } from '../hooks/useApi';
import { LoadingSpinner } from './ui/LoadingSpinner';
import { Alert, AlertDescription } from './ui/Alert';
import { Button } from './ui/Button';
import { FileIcon } from './ui/FileIcon';
import { ArrowUp, Download, Info } from 'lucide-react';
import { formatFileSize, formatRelativeTime } from '../utils/format';
import type { RepositoryEntry } from '../types/api';

interface DirectoryBrowserProps {
  repositoryId: string;
  currentPath: string;
  onNavigate: (path: string) => void;
  onShowFileInfo: (entry: RepositoryEntry) => void;
}

export function DirectoryBrowser({ 
  repositoryId, 
  currentPath, 
  onNavigate,
  onShowFileInfo 
}: DirectoryBrowserProps) {
  const { result, isLoading, error } = useBrowseRepository(repositoryId, currentPath);

  if (isLoading) {
    return (
      <div className="flex justify-center items-center h-64">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="error">
        <AlertDescription>
          Failed to browse directory: {error.message}
        </AlertDescription>
      </Alert>
    );
  }

  if (!result) {
    return null;
  }

  const handleEntryClick = (entry: RepositoryEntry) => {
    if (entry.type === 'directory') {
      onNavigate(entry.path);
    }
  };

  const handleDownload = (entry: RepositoryEntry) => {
    if (entry.type === 'file') {
      const downloadUrl = `/artifacts/${repositoryId}/${entry.path}`;
      window.open(downloadUrl, '_blank');
    }
  };

  return (
    <div className="space-y-4">
      {/* Parent directory navigation */}
      {result.parentPath !== null && (
        <div className="pb-2 border-b">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onNavigate(result.parentPath || '')}
            className="flex items-center space-x-2"
          >
            <ArrowUp className="h-4 w-4" />
            <span>Parent Directory</span>
          </Button>
        </div>
      )}

      {/* Directory entries */}
      {result.entries.length === 0 ? (
        <Alert>
          <AlertDescription>
            This directory is empty.
          </AlertDescription>
        </Alert>
      ) : (
        <div className="space-y-3">
          {result.entries.map((entry) => (
            <div
              key={entry.path}
              className={`group relative overflow-hidden rounded-xl border border-gray-200 bg-white transition-all duration-300 hover:shadow-xl hover:shadow-blue-500/10 hover:border-blue-300 ${
                entry.type === 'directory' ? 'cursor-pointer' : ''
              }`}
              onClick={() => entry.type === 'directory' ? handleEntryClick(entry) : undefined}
            >
              <div className="px-6 py-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center space-x-4">
                    <FileIcon fileName={entry.name} type={entry.type} className="h-6 w-6" />
                    <div className="flex-1 min-w-0">
                      <h3 className={`font-semibold text-gray-900 group-hover:text-blue-700 transition-colors truncate ${
                        entry.type === 'directory' ? 'text-base' : 'text-sm'
                      }`}>
                        {entry.name}
                      </h3>
                      <div className="text-sm text-gray-500 mt-1">
                        {entry.type === 'file' && entry.size !== undefined && (
                          <span className="inline-flex items-center px-2 py-1 rounded-md bg-gray-50 text-xs font-medium text-gray-600 mr-2">
                            {formatFileSize(entry.size)}
                          </span>
                        )}
                        <span className="text-xs">{formatRelativeTime(entry.lastModified)}</span>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center space-x-2">
                    {entry.type === 'file' && (
                      <>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={(e) => {
                            e.stopPropagation();
                            onShowFileInfo(entry);
                          }}
                          className="flex items-center space-x-1 px-3 py-2 rounded-lg bg-gray-50 group-hover:bg-white/80 transition-colors"
                        >
                          <Info className="h-4 w-4" />
                          <span className="text-xs">Info</span>
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={(e) => {
                            e.stopPropagation();
                            handleDownload(entry);
                          }}
                          className="flex items-center space-x-1 px-3 py-2 rounded-lg bg-gray-50 group-hover:bg-white/80 transition-colors"
                        >
                          <Download className="h-4 w-4" />
                          <span className="text-xs">Download</span>
                        </Button>
                      </>
                    )}
                  </div>
                </div>
              </div>
              
              {/* Bottom border gradient */}
              <div className="absolute bottom-0 left-0 right-0 h-1 bg-gradient-to-r from-blue-500 to-purple-500 transform scale-x-0 group-hover:scale-x-100 transition-transform duration-300" />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}