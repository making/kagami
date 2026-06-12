import { useBrowseRepository } from '../hooks/useApi';
import { LoadingSpinner } from './ui/LoadingSpinner';
import { Alert, AlertDescription } from './ui/Alert';
import { FileIcon } from './ui/FileIcon';
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
      <div className="flex justify-center items-center h-64 border border-line border-t-0 bg-paper">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="error" className="border-t-0">
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

  const handleDelete = async (entry: RepositoryEntry) => {
    const confirmed = window.confirm(
      `Are you sure you want to delete ${entry.type === 'directory' ? 'directory' : 'file'} "${entry.name}"?${
        entry.type === 'directory' ? ' This will delete all contents recursively.' : ''
      }`
    );

    if (!confirmed) return;

    try {
      const deletePath = entry.type === 'directory' ? `${entry.path}/` : entry.path;
      const response = await fetch(`/artifacts/${repositoryId}/${deletePath}`, {
        method: 'DELETE',
      });

      if (!response.ok) {
        throw new Error(`Failed to delete: ${response.status} ${response.statusText}`);
      }

      // Refresh the current directory
      window.location.reload();
    } catch (error) {
      alert(`Failed to delete ${entry.name}: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  };

  const fileActionButtonClass =
    'registry-label text-[9.5px] border border-line bg-transparent text-ink-2 px-2.5 py-1 cursor-pointer font-mono transition-colors hover:bg-ink hover:border-ink hover:text-white';

  return (
    <div className="border border-line border-t-0 bg-paper">
      {/* Parent directory navigation */}
      {result.parentPath !== null && (
        <div
          className="grid grid-cols-[64px_minmax(0,1fr)] items-center border-b border-line cursor-pointer registry-row-hover"
          onClick={() => onNavigate(result.parentPath || '')}
        >
          <div className="px-4 py-3 border-r border-line text-center">
            <FileIcon fileName=".." type="directory" />
          </div>
          <div className="px-4 py-3 text-[13px] font-medium">../ Parent directory</div>
        </div>
      )}

      {/* Directory entries */}
      {result.entries.length === 0 ? (
        <div className="px-6 py-8 text-center text-ink-3 text-sm">
          This directory is empty.
        </div>
      ) : (
        result.entries.map((entry, index) => (
          <div
            key={entry.path}
            className={`group grid grid-cols-[64px_minmax(0,1fr)_auto] items-center border-b border-line last:border-b-0 registry-row-hover registry-rise ${
              entry.type === 'directory' ? 'cursor-pointer' : ''
            }`}
            style={{ animationDelay: `${0.04 + Math.min(index, 15) * 0.04}s` }}
            onClick={() => entry.type === 'directory' ? handleEntryClick(entry) : undefined}
          >
            <div className="px-4 py-3 border-r border-line text-center overflow-hidden">
              <FileIcon fileName={entry.name} type={entry.type} />
            </div>
            <div className="px-4 py-3 min-w-0">
              <div className="text-[13px] font-medium truncate">{entry.name}</div>
            </div>
            <div className="flex items-center gap-6 px-4 py-3">
              <span className="text-[11.5px] text-ink-2 text-right min-w-[70px]">
                {entry.type === 'file' && entry.size !== undefined ? formatFileSize(entry.size) : ''}
              </span>
              <span className="hidden md:inline text-[11.5px] text-ink-2 text-right min-w-[110px]">
                {formatRelativeTime(entry.lastModified)}
              </span>
              <div className="flex gap-1.5 opacity-25 group-hover:opacity-100 transition-opacity">
                {entry.type === 'file' && (
                  <>
                    <button
                      className={fileActionButtonClass}
                      onClick={(e) => {
                        e.stopPropagation();
                        onShowFileInfo(entry);
                      }}
                    >
                      Info
                    </button>
                    <button
                      className={fileActionButtonClass}
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDownload(entry);
                      }}
                    >
                      Get
                    </button>
                  </>
                )}
                <button
                  className={`${fileActionButtonClass} hover:bg-accent hover:border-accent`}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDelete(entry);
                  }}
                >
                  Del
                </button>
              </div>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
