import { useNavigate, Link } from 'react-router-dom';
import { RepositorySelector } from '../components/RepositorySelector';
import { Button } from '../components/ui/Button';
import { Key } from 'lucide-react';

export function HomePage() {
  const navigate = useNavigate();

  const handleSelectRepository = (repositoryId: string) => {
    navigate(`/browse/${encodeURIComponent(repositoryId)}`);
  };

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="max-w-6xl mx-auto">
        <header className="text-center mb-8">
          <div className="flex justify-between items-start mb-6">
            <div></div>
            <div>
              <h1 className="text-3xl font-bold text-gray-900 mb-2">
                Kagami 🪞 Repository Browser
              </h1>
              <p className="text-gray-600">
                Browse and explore Maven repositories
              </p>
            </div>
            <div className="flex space-x-2">
              <Link to="/token">
                <Button variant="ghost" className="flex items-center space-x-2">
                  <Key className="h-4 w-4" />
                  <span>Generate Token</span>
                </Button>
              </Link>
            </div>
          </div>
        </header>
        
        <RepositorySelector onSelectRepository={handleSelectRepository} />
      </div>
    </div>
  );
}