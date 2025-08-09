import { Link } from 'react-router-dom';
import { useCurrentUser } from '../hooks/useApi';
import { LoadingSpinner } from './ui/LoadingSpinner';
import { User, LogOut } from 'lucide-react';

export function Header() {
  const { user, isLoading, error } = useCurrentUser();

  if (isLoading) {
    return (
      <header className="bg-white border-b border-gray-200 shadow-sm">
        <div className="container mx-auto px-4">
          <div className="flex items-center justify-between h-16">
            <Link to="/" className="flex items-center space-x-2">
              <span className="text-xl font-bold bg-gradient-to-r from-red-600 to-pink-600 bg-clip-text text-transparent">
                Kagami
              </span>
              <span className="text-xl">🪞</span>
            </Link>
            <div className="flex items-center">
              <LoadingSpinner size="sm" />
            </div>
          </div>
        </div>
      </header>
    );
  }

  if (error || !user) {
    return (
      <header className="bg-white border-b border-gray-200 shadow-sm">
        <div className="container mx-auto px-4">
          <div className="flex items-center justify-between h-16">
            <Link to="/" className="flex items-center space-x-2">
              <span className="text-xl font-bold bg-gradient-to-r from-red-600 to-pink-600 bg-clip-text text-transparent">
                Kagami
              </span>
              <span className="text-xl">🪞</span>
            </Link>
            <div className="text-sm text-gray-500">
              Authentication required
            </div>
          </div>
        </div>
      </header>
    );
  }

  return (
    <header className="bg-white border-b border-gray-200 shadow-sm">
      <div className="container mx-auto px-4">
        <div className="flex items-center justify-between h-16">
          <Link to="/" className="flex items-center space-x-2">
            <span className="text-xl font-bold bg-gradient-to-r from-red-600 to-pink-600 bg-clip-text text-transparent">
              Kagami
            </span>
            <span className="text-xl">🪞</span>
          </Link>
          
          <div className="flex items-center space-x-4">
            <Link 
              to="/token" 
              className="inline-flex items-center px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
            >
              <svg className="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 7a2 2 0 012 2m0 0a2 2 0 012 2 2 2 0 002-2 2 2 0 00-2-2zm0 0V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2h4z" />
              </svg>
              Generate Token
            </Link>
            
            <div className="flex items-center space-x-3">
              <div className="flex items-center space-x-2 text-sm text-gray-700">
                <User className="h-4 w-4" />
                <span className="font-medium">{user.name}</span>
              </div>
              
              <a
                href="/logout"
                className="inline-flex items-center px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
              >
                <LogOut className="h-4 w-4 mr-2" />
                Logout
              </a>
            </div>
          </div>
        </div>
      </div>
    </header>
  );
}