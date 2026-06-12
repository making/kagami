import { useState } from 'react';
import { useRepositories } from '../hooks/useApi';
import { LoadingSpinner } from './ui/LoadingSpinner';
import { Alert, AlertDescription } from './ui/Alert';
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
      <Alert variant="error" className="mt-6">
        <AlertDescription>
          Failed to load repositories: {error.message}
          {is401Error && (
            <div className="mt-3">
              <a
                href="/login"
                className="inline-flex items-center px-4 py-2 registry-label font-semibold text-white bg-gradient-to-r from-accent to-magenta no-underline hover:brightness-110"
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
      <Alert className="mt-6">
        <AlertDescription>
          No repositories configured. Please check your server configuration.
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <>
      <div className="border border-line border-t-0 bg-paper">
        {/* Panel head */}
        <div className="flex justify-between items-center px-6 py-3.5 border-b-2 border-b-ink">
          <h2 className="registry-label font-semibold flex items-center gap-3 before:content-[''] before:w-[9px] before:h-[9px] before:bg-gradient-to-r before:from-accent before:to-magenta">
            Repositories
          </h2>
          <span className="registry-label text-ink-3">
            {repositories.length} entries / select to browse
          </span>
        </div>

        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b border-line">
              <th className="text-left registry-label text-[9.5px] text-ink-3 font-medium px-6 py-2.5">
                Repository
              </th>
              <th className="text-right registry-label text-[9.5px] text-ink-3 font-medium px-6 py-2.5">
                Artifacts
              </th>
              <th className="text-right registry-label text-[9.5px] text-ink-3 font-medium px-6 py-2.5">
                Size
              </th>
              <th className="text-right registry-label text-[9.5px] text-ink-3 font-medium px-6 py-2.5">
                Updated
              </th>
              <th></th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {repositories.map((repo, index) => (
              <tr
                key={repo.id}
                className={`group border-b border-line last:border-b-0 cursor-pointer registry-row-hover registry-rise ${
                  selectedRepository === repo.id ? 'bg-wash shadow-[inset_3px_0_0_var(--color-accent)]' : ''
                }`}
                style={{ animationDelay: `${0.04 + index * 0.06}s` }}
                onClick={() => onSelectRepository(repo.id)}
              >
                <td className="px-6 py-4.5">
                  <div className="font-sans font-bold text-base tracking-[-0.01em] flex items-center gap-2.5 group-hover:text-accent transition-colors">
                    {repo.id}
                    {repo.isPrivate && (
                      <span className="registry-label text-[8.5px] font-mono font-normal text-magenta border border-magenta px-1.5 py-0.5">
                        Private
                      </span>
                    )}
                  </div>
                  <div className="text-[11px] text-ink-3 mt-1">{repo.url}</div>
                </td>
                <td className="px-6 py-4.5 text-right">
                  <span className="font-semibold text-[13.5px]">
                    {repo.artifactCount.toLocaleString()}
                  </span>
                </td>
                <td className="px-6 py-4.5 text-right whitespace-nowrap">
                  <span className="font-semibold text-[13.5px]">{formatFileSize(repo.totalSize)}</span>
                </td>
                <td className="px-6 py-4.5 text-right whitespace-nowrap">
                  {repo.lastUpdated ? (
                    <span className="font-semibold text-[13.5px]">
                      {formatRelativeTime(repo.lastUpdated)}
                    </span>
                  ) : (
                    <span className="text-ink-3">&mdash;</span>
                  )}
                </td>
                <td className="px-6 py-4.5 text-right">
                  <button
                    onClick={(e) => handleShowConfig(repo, e)}
                    className="registry-label border border-line bg-transparent px-3 py-1.5 cursor-pointer text-ink-2 font-mono transition-colors hover:bg-ink hover:text-white hover:border-ink"
                  >
                    Config
                  </button>
                </td>
                <td className="w-14 pr-6 text-right text-ink-3 transition-all group-hover:text-accent group-hover:translate-x-0.5">
                  &rarr;
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <RepositoryConfigDialog
        open={configDialog.open}
        onOpenChange={handleCloseConfig}
        repository={configDialog.repository}
      />
    </>
  );
}
