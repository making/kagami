import { useNavigate } from 'react-router-dom';
import { RepositorySelector } from '../components/RepositorySelector';
import { Header } from '../components/Header';
import { useRepositories } from '../hooks/useApi';
import { formatFileSize } from '../utils/format';

export function HomePage() {
  const navigate = useNavigate();
  const { repositories } = useRepositories();

  const handleSelectRepository = (repositoryId: string) => {
    navigate(`/browse/${encodeURIComponent(repositoryId)}`);
  };

  const totalArtifacts = repositories.reduce((sum, repo) => sum + repo.artifactCount, 0);
  const totalSize = repositories.reduce((sum, repo) => sum + repo.totalSize, 0);

  return (
    <div>
      <Header />
      <div className="max-w-[1120px] mx-auto px-7">
        {/* Hero */}
        <section className="border-x border-line bg-paper px-12 pt-14 relative overflow-hidden">
          <div className="registry-label text-accent flex items-center gap-3.5 mb-5 after:content-[''] after:flex-1 after:h-px after:bg-line">
            01 / Mirror Index
          </div>
          <h1 className="font-sans font-extrabold uppercase text-[clamp(36px,5.4vw,58px)] tracking-[-0.015em] leading-[0.98]">
            Maven
            <br />
            <span className="text-transparent [-webkit-text-stroke:1.5px_var(--color-ink)]">
              Mirror
            </span>{' '}
            <span className="bg-gradient-to-r from-accent to-magenta bg-clip-text text-transparent">
              Registry
            </span>
          </h1>
          <p className="mt-5 text-ink-2 max-w-[470px] text-[13px]">
            Browse, inspect and manage every artifact mirrored by this Kagami instance &mdash;
            reflected from upstream, served from here.
          </p>
          <div
            aria-hidden="true"
            className="absolute -right-4 -bottom-9 select-none pointer-events-none font-sans font-extrabold text-[180px] leading-none text-transparent [-webkit-text-stroke:1px_var(--color-line)]"
          >
            鏡
          </div>
          <div className="mt-11 grid grid-cols-3 border-t border-line -mx-12">
            <div className="px-12 py-5 border-r border-line">
              <div className="text-2xl font-semibold tracking-[-0.02em]">
                {repositories.length}
              </div>
              <div className="registry-label text-ink-3 mt-1">Repositories</div>
            </div>
            <div className="px-12 py-5 border-r border-line">
              <div className="text-2xl font-semibold tracking-[-0.02em]">
                {totalArtifacts.toLocaleString()}
              </div>
              <div className="registry-label text-ink-3 mt-1">Artifacts</div>
            </div>
            <div className="px-12 py-5">
              <div className="text-2xl font-semibold tracking-[-0.02em]">
                {formatFileSize(totalSize)}
              </div>
              <div className="registry-label text-ink-3 mt-1">Mirrored</div>
            </div>
          </div>
        </section>

        <RepositorySelector onSelectRepository={handleSelectRepository} />

        <footer className="mt-20 py-6 border-t-2 border-t-ink flex justify-between registry-label text-ink-3">
          <span>Kagami &mdash; Maven Mirror</span>
          <span>鏡</span>
        </footer>
      </div>
    </div>
  );
}
