import { useState } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import { Button } from '../components/ui/Button';
import { Card, CardHeader, CardTitle, CardContent } from '../components/ui/Card';
import { Breadcrumb } from '../components/Breadcrumb';
import { DirectoryBrowser } from '../components/DirectoryBrowser';
import { FileInfoModal } from '../components/FileInfoModal';
import { Header } from '../components/Header';
import { ArrowLeft } from 'lucide-react';
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
      <div className="container mx-auto px-4 py-8">
        <div className="max-w-6xl mx-auto">
          {/* Header */}
          <header className="flex items-center justify-between mb-6">
            <div className="flex items-center space-x-4">
              <Button
                variant="ghost"
                onClick={() => navigate('/')}
                className="flex items-center space-x-2"
              >
                <ArrowLeft className="h-4 w-4" />
                <span>Back to Repositories</span>
              </Button>
              <h1 className="text-2xl font-bold text-gray-900">
                Repository Browser
              </h1>
            </div>
          </header>

        {/* Main Content */}
        <Card>
          <CardHeader>
            <CardTitle>
              <Breadcrumb
                repositoryId={repositoryId}
                currentPath={currentPath}
                onNavigate={handleNavigate}
              />
            </CardTitle>
          </CardHeader>
          <CardContent>
            <DirectoryBrowser
              repositoryId={repositoryId}
              currentPath={currentPath}
              onNavigate={handleNavigate}
              onShowFileInfo={handleShowFileInfo}
            />
          </CardContent>
        </Card>

        {/* File Info Modal */}
        {selectedFile && (
          <FileInfoModal
            repositoryId={repositoryId}
            entry={selectedFile}
            onClose={handleCloseFileInfo}
          />
        )}
        </div>
      </div>
    </div>
  );
}