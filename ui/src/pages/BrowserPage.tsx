import { useState } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import { Breadcrumb } from '../components/Breadcrumb';
import { DirectoryBrowser } from '../components/DirectoryBrowser';
import { FileInfoModal } from '../components/FileInfoModal';
import { Header } from '../components/Header';
import type { RepositoryEntry } from '../types/api';

export function BrowserPage() {
  const { repositoryId } = useParams<{ repositoryId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [selectedFile, setSelectedFile] = useState<RepositoryEntry | null>(null);

  if (!repositoryId) {
    navigate('/');
    return null;
  }

  // Extract path from URL after /browse/{repositoryId}
  const pathMatch = location.pathname.match(/^\/browse\/[^/]+(?:\/(.*))?$/);
  const currentPath = pathMatch?.[1] || '';

  const handleNavigate = (path: string) => {
    if (path) {
      navigate(`/browse/${encodeURIComponent(repositoryId)}/${path}`);
    } else {
      navigate(`/browse/${encodeURIComponent(repositoryId)}`);
    }
  };

  const handleShowFileInfo = (entry: RepositoryEntry) => {
    setSelectedFile(entry);
  };

  const handleCloseFileInfo = () => {
    setSelectedFile(null);
  };

  return (
    <div>
      <Header />
      <div className="max-w-[1120px] mx-auto px-7 pb-12">
        {/* Section label */}
        <div className="pt-12 pb-3.5 flex items-center gap-4 registry-label text-ink-3 after:content-[''] after:flex-1 after:h-px after:bg-line">
          <button
            onClick={() => navigate('/')}
            className="registry-label text-ink-2 cursor-pointer bg-transparent border-none font-mono hover:text-accent transition-colors"
          >
            &larr; Repositories
          </button>
          <span>/ Browse</span>
        </div>

        {/* Breadcrumb bar */}
        <div className="border border-line border-b-2 border-b-ink bg-paper px-6 py-3">
          <Breadcrumb
            repositoryId={repositoryId}
            currentPath={currentPath}
            onNavigate={handleNavigate}
          />
        </div>

        {/* Directory listing */}
        <DirectoryBrowser
          repositoryId={repositoryId}
          currentPath={currentPath}
          onNavigate={handleNavigate}
          onShowFileInfo={handleShowFileInfo}
        />

        {/* File Info Modal */}
        {selectedFile && (
          <FileInfoModal
            repositoryId={repositoryId}
            entry={selectedFile}
            onClose={handleCloseFileInfo}
          />
        )}

        <footer className="mt-20 py-6 border-t-2 border-t-ink flex justify-between registry-label text-ink-3">
          <span>Kagami &mdash; Maven Mirror</span>
          <span>鏡</span>
        </footer>
      </div>
    </div>
  );
}
