import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useRepositories } from '../hooks/useApi';
import { LoadingSpinner } from '../components/ui/LoadingSpinner';
import { Alert, AlertDescription } from '../components/ui/Alert';
import { Button } from '../components/ui/Button';
import { Header } from '../components/Header';
import { Copy, Key, AlertTriangle } from 'lucide-react';
import { generateConfigExample, type AuthMethod, type BuildTool } from '../utils/configExamples';

interface TokenFormData {
  repositories: string[];
  scopes: string[];
  duration: number;
  unit: 'hours' | 'days' | 'months';
}

interface SectionHeadProps {
  index: string;
  title: string;
  description: string;
}

function SectionHead({ index, title, description }: SectionHeadProps) {
  return (
    <div className="px-6 py-3.5 border-b border-line">
      <h3 className="registry-label font-semibold flex items-center gap-3">
        <span className="text-accent">{index}</span>
        {title}
      </h3>
      <p className="text-[11.5px] text-ink-3 mt-1">{description}</p>
    </div>
  );
}

interface TabProps {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}

function Tab({ active, onClick, children }: TabProps) {
  return (
    <button
      className={`flex-1 py-2 px-3 registry-label font-medium border-r border-line last:border-r-0 cursor-pointer transition-colors ${
        active
          ? 'bg-gradient-to-r from-accent to-magenta text-white'
          : 'bg-transparent text-ink-2 hover:bg-ink hover:text-white'
      }`}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

export function TokenPage() {
  const { repositories, isLoading: reposLoading, error: reposError } = useRepositories();
  const [formData, setFormData] = useState<TokenFormData>({
    repositories: [],
    scopes: [],
    duration: 6,
    unit: 'months',
  });
  const [isGenerating, setIsGenerating] = useState(false);
  const [generatedToken, setGeneratedToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copySuccess, setCopySuccess] = useState<{ [key: string]: boolean }>({});
  const [selectedTool, setSelectedTool] = useState<BuildTool>('maven');
  const [authMethod, setAuthMethod] = useState<AuthMethod>('basic');

  const availableScopes = [
    { value: 'artifacts:read', label: 'Read Artifacts', description: 'Download and view repository contents' },
    { value: 'artifacts:delete', label: 'Delete Artifacts', description: 'Remove artifacts from repositories' },
  ];

  const calculateHours = () => {
    switch (formData.unit) {
      case 'hours':
        return formData.duration;
      case 'days':
        return formData.duration * 24;
      case 'months':
        return formData.duration * 24 * 30;
      default:
        return formData.duration;
    }
  };

  const isLongDuration = () => {
    const totalHours = calculateHours();
    return totalHours > 24 * 30 * 6; // More than 6 months
  };

  const handleRepositoryChange = (repoId: string, checked: boolean) => {
    setFormData(prev => ({
      ...prev,
      repositories: checked
        ? [...prev.repositories, repoId]
        : prev.repositories.filter(id => id !== repoId)
    }));
  };

  const handleScopeChange = (scope: string, checked: boolean) => {
    setFormData(prev => ({
      ...prev,
      scopes: checked
        ? [...prev.scopes, scope]
        : prev.scopes.filter(s => s !== scope)
    }));
  };

  const generateToken = async () => {
    if (formData.repositories.length === 0 || formData.scopes.length === 0) {
      setError('Please select at least one repository and one scope.');
      return;
    }

    setIsGenerating(true);
    setError(null);

    try {
      const response = await fetch('/token', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: new URLSearchParams({
          repositories: formData.repositories.join(','),
          scope: formData.scopes.join(','),
          expires_in: calculateHours().toString(),
        }),
      });

      if (!response.ok) {
        throw new Error(`Failed to generate token: ${response.status} ${response.statusText}`);
      }

      const token = await response.text();
      setGeneratedToken(token);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error occurred');
    } finally {
      setIsGenerating(false);
    }
  };

  const copyToClipboard = async (text: string, key: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopySuccess(prev => ({ ...prev, [key]: true }));
      setTimeout(() => setCopySuccess(prev => ({ ...prev, [key]: false })), 2000);
    } catch (err) {
      console.error('Failed to copy to clipboard:', err);
    }
  };

  const reset = () => {
    setGeneratedToken(null);
    setError(null);
    setCopySuccess({});
    setSelectedTool('maven');
    setAuthMethod('basic');
    setFormData({
      repositories: [],
      scopes: [],
      duration: 6,
      unit: 'months',
    });
  };

  if (reposLoading) {
    return (
      <div className="max-w-[1120px] mx-auto px-7 py-8">
        <div className="flex justify-center items-center h-32">
          <LoadingSpinner size="lg" />
        </div>
      </div>
    );
  }

  return (
    <div>
      <Header />
      <div className="max-w-[900px] mx-auto px-7 pb-12">
        {/* Section label */}
        <div className="pt-12 pb-3.5 flex items-center gap-4 registry-label text-ink-3 after:content-[''] after:flex-1 after:h-px after:bg-line">
          <Link
            to="/"
            className="registry-label text-ink-2 no-underline hover:text-accent transition-colors"
          >
            &larr; Repositories
          </Link>
          <span>/ Access Token</span>
        </div>

        {/* Page head */}
        <header className="border border-line border-b-0 bg-paper px-8 pt-8 pb-6">
          <div className="flex items-center gap-3 mb-2">
            <span className="w-[34px] h-[34px] bg-gradient-to-r from-accent to-magenta grid place-items-center text-white">
              <Key className="h-4 w-4" />
            </span>
            <h1 className="font-sans font-extrabold uppercase text-3xl tracking-[-0.015em]">
              Generate <span className="bg-gradient-to-r from-accent to-magenta bg-clip-text text-transparent">Access Token</span>
            </h1>
          </div>
          <p className="text-[12.5px] text-ink-2">
            Create JWT tokens for accessing private repositories
          </p>
        </header>

        {reposError && (
          <Alert variant="error" className="mb-6">
            <AlertDescription>
              Failed to load repositories: {reposError.message}
              {reposError.message.includes('401') && (
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
        )}

        {!generatedToken ? (
          <div className="border border-line bg-paper">
            {/* Repository Selection */}
            <SectionHead
              index="01 /"
              title="Select Repositories"
              description="Choose which repositories this token can access"
            />
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 p-6 border-b border-line">
              {repositories.map((repo) => (
                <label
                  key={repo.id}
                  className={`flex items-center p-3 border cursor-pointer transition-colors ${
                    formData.repositories.includes(repo.id)
                      ? 'border-accent bg-wash shadow-[inset_3px_0_0_var(--color-accent)]'
                      : 'border-line hover:bg-wash'
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={formData.repositories.includes(repo.id)}
                    onChange={(e) => handleRepositoryChange(repo.id, e.target.checked)}
                    className="h-4 w-4 accent-accent"
                  />
                  <div className="ml-3 flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-sans font-bold text-sm">{repo.id}</span>
                      {repo.isPrivate && (
                        <span className="registry-label text-[8.5px] text-magenta border border-magenta px-1.5 py-0.5">
                          Private
                        </span>
                      )}
                    </div>
                    <span className="text-[11px] text-ink-3 break-all">{repo.url}</span>
                  </div>
                </label>
              ))}
            </div>

            {/* Scope Selection */}
            <SectionHead
              index="02 /"
              title="Select Permissions"
              description="Choose what actions this token can perform"
            />
            <div className="space-y-3 p-6 border-b border-line">
              {availableScopes.map((scope) => (
                <label
                  key={scope.value}
                  className={`flex items-start p-3 border cursor-pointer transition-colors ${
                    formData.scopes.includes(scope.value)
                      ? 'border-accent bg-wash shadow-[inset_3px_0_0_var(--color-accent)]'
                      : 'border-line hover:bg-wash'
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={formData.scopes.includes(scope.value)}
                    onChange={(e) => handleScopeChange(scope.value, e.target.checked)}
                    className="h-4 w-4 accent-accent mt-0.5"
                  />
                  <div className="ml-3">
                    <div className="font-sans font-bold text-sm">{scope.label}</div>
                    <div className="text-[11.5px] text-ink-2 mt-0.5">{scope.description}</div>
                    <div className="text-[10px] text-ink-3 mt-0.5">{scope.value}</div>
                  </div>
                </label>
              ))}
            </div>

            {/* Expiration */}
            <SectionHead
              index="03 /"
              title="Token Expiration"
              description="Set how long the token will remain valid"
            />
            <div className="p-6">
              <div className="flex items-center gap-3">
                <input
                  type="number"
                  min="1"
                  value={formData.duration}
                  onChange={(e) => setFormData(prev => ({ ...prev, duration: parseInt(e.target.value) || 1 }))}
                  className="w-24 px-3 py-2 border border-line bg-paper text-sm focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent"
                />
                <select
                  value={formData.unit}
                  onChange={(e) => setFormData(prev => ({ ...prev, unit: e.target.value as 'hours' | 'days' | 'months' }))}
                  className="px-3 py-2 border border-line bg-paper text-sm focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent"
                >
                  <option value="hours">Hours</option>
                  <option value="days">Days</option>
                  <option value="months">Months</option>
                </select>
              </div>

              {isLongDuration() && (
                <Alert variant="warning" className="mt-4">
                  <AlertDescription className="flex items-start gap-2">
                    <AlertTriangle className="h-4 w-4 mt-0.5 shrink-0" />
                    <span>
                      <strong>Long-lived token warning:</strong> This token will be valid for more than 6 months.
                      Since JWT tokens cannot be revoked, consider using shorter durations for better security.
                    </span>
                  </AlertDescription>
                </Alert>
              )}
            </div>

            {/* Error Display */}
            {error && (
              <div className="px-6 pb-6">
                <Alert variant="error">
                  <AlertDescription>{error}</AlertDescription>
                </Alert>
              </div>
            )}

            {/* Generate Button */}
            <div className="flex justify-end px-6 py-4 border-t-2 border-t-ink">
              <Button
                onClick={generateToken}
                disabled={isGenerating || formData.repositories.length === 0 || formData.scopes.length === 0}
              >
                {isGenerating ? (
                  <>
                    <LoadingSpinner size="sm" className="mr-2" />
                    Generating...
                  </>
                ) : (
                  'Generate Token'
                )}
              </Button>
            </div>
          </div>
        ) : (
          /* Token Result */
          <div className="border border-line bg-paper">
            <div className="px-8 py-6 border-b border-line">
              <div className="flex items-center gap-3 mb-2">
                <span className="w-[34px] h-[34px] bg-gradient-to-r from-accent to-magenta grid place-items-center text-white">
                  <Key className="h-4 w-4" />
                </span>
                <h3 className="font-sans font-extrabold uppercase text-xl tracking-[-0.01em]">
                  Token Generated
                </h3>
              </div>
              <p className="text-[12.5px] text-ink-2">
                Copy this token and store it securely. You won't be able to see it again.
              </p>
            </div>

            <div className="p-6 border-b border-line">
              <div className="flex items-center justify-between mb-2">
                <span className="registry-label text-ink-3">JWT Token</span>
                <button
                  onClick={() => copyToClipboard(generatedToken!, 'token')}
                  className="registry-label flex items-center gap-1.5 border border-line px-3 py-1.5 cursor-pointer text-ink-2 font-mono transition-colors hover:bg-ink hover:text-white hover:border-ink"
                >
                  <Copy className="h-3 w-3" />
                  {copySuccess.token ? 'Copied!' : 'Copy'}
                </button>
              </div>
              <div className="border border-line bg-wash p-3 text-xs break-all select-all">
                {generatedToken}
              </div>
            </div>

            {/* Configuration Examples */}
            <SectionHead
              index="&gt;"
              title="Build Tool Configuration"
              description="Use these configurations in your build tools to access the selected repositories with the generated token"
            />
            <div className="p-6 space-y-4">
              {/* Build Tool Tabs */}
              <div className="flex border border-line">
                <Tab active={selectedTool === 'maven'} onClick={() => setSelectedTool('maven')}>
                  Maven
                </Tab>
                <Tab active={selectedTool === 'gradleGroovy'} onClick={() => setSelectedTool('gradleGroovy')}>
                  Gradle (Groovy)
                </Tab>
                <Tab active={selectedTool === 'gradleKotlin'} onClick={() => setSelectedTool('gradleKotlin')}>
                  Gradle (Kotlin)
                </Tab>
              </div>

              {/* Authentication Method Tabs */}
              <div className="flex border border-line">
                <Tab active={authMethod === 'basic'} onClick={() => setAuthMethod('basic')}>
                  Username / Password
                </Tab>
                <Tab active={authMethod === 'bearer'} onClick={() => setAuthMethod('bearer')}>
                  Bearer Token
                </Tab>
              </div>

              {/* Selected Configuration */}
              <div className="border border-line">
                {(() => {
                  const params = {
                    repositoryIds: formData.repositories,
                    token: generatedToken!,
                    baseUrl: window.location.origin,
                    isPrivate: true,
                    authMethod
                  };
                  const config = generateConfigExample(selectedTool, params);

                  return (
                    <>
                      <div className="flex items-center justify-between px-4 py-2 border-b border-line">
                        <h5 className="registry-label font-semibold">{config.title}</h5>
                        <button
                          onClick={() => copyToClipboard(config.content, selectedTool)}
                          className="registry-label flex items-center gap-1.5 border border-line px-3 py-1.5 cursor-pointer text-ink-2 font-mono transition-colors hover:bg-ink hover:text-white hover:border-ink"
                        >
                          <Copy className="h-3 w-3" />
                          {copySuccess[selectedTool] ? 'Copied!' : 'Copy'}
                        </button>
                      </div>
                      <pre className="bg-ink text-[#f4f0f2] p-4 text-xs overflow-x-auto leading-relaxed">
                        <code>{config.content}</code>
                      </pre>
                    </>
                  );
                })()}
              </div>
            </div>

            <div className="flex justify-center px-6 py-4 border-t-2 border-t-ink">
              <Button onClick={reset} variant="secondary">
                Generate Another Token
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
