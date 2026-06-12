import { Link } from 'react-router-dom';
import { useCurrentUser } from '../hooks/useApi';
import { LoadingSpinner } from './ui/LoadingSpinner';

function Brand() {
  return (
    <Link to="/" className="flex items-center gap-3 text-ink no-underline">
      <span className="w-[26px] h-[26px] bg-gradient-to-r from-accent to-magenta grid place-items-center text-white text-[13px] font-semibold">
        鏡
      </span>
      <span className="font-sans font-extrabold text-[17px] tracking-[0.02em] uppercase">
        Kagami
      </span>
    </Link>
  );
}

export function Header() {
  const { user, isLoading, error } = useCurrentUser();

  return (
    <header className="sticky top-0 z-50 bg-canvas/90 backdrop-blur border-b-2 border-b-ink">
      <div className="max-w-[1120px] mx-auto px-7 h-14 flex items-stretch justify-between">
        <div className="flex items-center">
          <Brand />
        </div>

        <nav className="flex items-stretch">
          {isLoading ? (
            <div className="flex items-center px-5 border-l border-line">
              <LoadingSpinner size="sm" />
            </div>
          ) : error || !user ? (
            <div className="flex items-center px-5 border-l border-line registry-label text-ink-3">
              Authentication required
            </div>
          ) : (
            <>
              <Link
                to="/"
                className="hidden sm:flex items-center px-5 border-l border-line registry-label font-medium text-ink-2 no-underline transition-colors hover:bg-ink hover:text-white"
              >
                Repositories
              </Link>
              <Link
                to="/token"
                className="flex items-center px-5 registry-label font-semibold text-white no-underline bg-gradient-to-r from-accent to-magenta transition-[filter] hover:brightness-110"
              >
                Generate Token
              </Link>
              <span className="flex items-center gap-2 px-5 border-l border-line text-[11px] tracking-[0.08em] text-ink-2">
                <span className="text-accent text-[8px]">●</span>
                {user.name}
              </span>
              <a
                href="/logout"
                className="flex items-center px-5 border-l border-line registry-label font-medium text-ink-2 no-underline transition-colors hover:bg-ink hover:text-white"
              >
                Logout
              </a>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}
