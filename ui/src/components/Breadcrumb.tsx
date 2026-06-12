interface BreadcrumbProps {
  repositoryId: string;
  currentPath: string;
  onNavigate: (path: string) => void;
}

export function Breadcrumb({ repositoryId, currentPath, onNavigate }: BreadcrumbProps) {
  const pathParts = currentPath ? currentPath.split('/').filter(Boolean) : [];

  return (
    <nav className="flex items-center gap-1 flex-wrap font-mono text-xs">
      <span className="text-accent font-semibold mr-2">&gt;</span>

      {pathParts.length === 0 ? (
        <span className="bg-gradient-to-r from-accent to-magenta text-white px-2 py-0.5 font-medium">
          {repositoryId}
        </span>
      ) : (
        <button
          onClick={() => onNavigate('')}
          className="text-ink-2 px-1 py-0.5 cursor-pointer hover:bg-ink hover:text-white transition-colors"
        >
          {repositoryId}
        </button>
      )}

      {pathParts.map((part, index) => {
        const pathToHere = pathParts.slice(0, index + 1).join('/');
        const isLast = index === pathParts.length - 1;

        return (
          <div key={index} className="flex items-center gap-1">
            <span className="text-ink-3">/</span>
            {isLast ? (
              <span className="bg-gradient-to-r from-accent to-magenta text-white px-2 py-0.5 font-medium">
                {part}
              </span>
            ) : (
              <button
                onClick={() => onNavigate(pathToHere)}
                className="text-ink-2 px-1 py-0.5 cursor-pointer hover:bg-ink hover:text-white transition-colors"
              >
                {part}
              </button>
            )}
          </div>
        );
      })}
    </nav>
  );
}
