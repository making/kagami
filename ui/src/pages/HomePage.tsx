import { useNavigate } from 'react-router-dom';
import { RepositorySelector } from '../components/RepositorySelector';

export function HomePage() {
  const navigate = useNavigate();

  const handleSelectRepository = (repositoryId: string) => {
    navigate(`/browse/${encodeURIComponent(repositoryId)}`);
  };

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="max-w-6xl mx-auto">
        <header className="text-center mb-8">
          <h1 className="text-3xl font-bold text-gray-900 mb-2">
            Kagami Repository Browser
          </h1>
          <p className="text-gray-600">
            Browse and explore Maven repositories
          </p>
        </header>
        
        <RepositorySelector onSelectRepository={handleSelectRepository} />
      </div>
    </div>
  );
}