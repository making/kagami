import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useRepositories } from '../hooks/useApi';
import { LoadingSpinner } from '../components/ui/LoadingSpinner';
import { Alert, AlertDescription } from '../components/ui/Alert';
import { Button } from '../components/ui/Button';
import { LockIcon } from '../components/ui/LockIcon';
import { ArrowLeft, Copy, Key, Shield, Clock, AlertTriangle } from 'lucide-react';

interface TokenFormData {
  repositories: string[];
  scopes: string[];
  duration: number;
  unit: 'hours' | 'days' | 'months';
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
    setFormData({
      repositories: [],
      scopes: [],
      duration: 6,
      unit: 'months',
    });
  };

  const generateConfigExamples = () => {
    if (!generatedToken) return { maven: '', gradleGroovy: '', gradleKotlin: '' };
    
    const baseUrl = window.location.origin;
    const selectedRepoIds = formData.repositories;
    
    // Use first repository for single repo examples, or show multiple for multi-repo setup
    const primaryRepo = selectedRepoIds[0];
    
    const maven = selectedRepoIds.length === 1 
      ? `<!-- settings.xml -->
<settings>
  <servers>
    <server>
      <id>kagami-${primaryRepo}</id>
      <username>kagami</username>
      <password>${generatedToken}</password>
    </server>
  </servers>
  
  <!-- OPTIONAL: Mirror configuration -->
  <!-- Use mirrors only if you want to redirect ALL repository requests to Kagami -->
  <!-- This replaces Maven Central and other public repositories with this private repo -->
  <!-- Remove this section if you want to use this repo alongside other repositories -->
  <mirrors>
    <mirror>
      <id>kagami-${primaryRepo}</id>
      <mirrorOf>*</mirrorOf>
      <name>Kagami Private Mirror</name>
      <url>${baseUrl}/artifacts/${primaryRepo}</url>
    </mirror>
  </mirrors>
</settings>`
      : `<!-- settings.xml -->
<settings>
  <servers>
${selectedRepoIds.map(repo => `    <server>
      <id>kagami-${repo}</id>
      <username>kagami</username>
      <password>${generatedToken}</password>
    </server>`).join('\n')}
  </servers>
  
  <!-- Add these repositories to your project's pom.xml or profiles section -->
  <!-- These repositories will be used alongside Maven Central and other configured repos -->
  <repositories>
${selectedRepoIds.map(repo => `    <repository>
      <id>kagami-${repo}</id>
      <url>${baseUrl}/artifacts/${repo}</url>
    </repository>`).join('\n')}
  </repositories>
</settings>`;

    const gradleGroovy = selectedRepoIds.length === 1
      ? `// build.gradle
repositories {
    maven {
        url '${baseUrl}/artifacts/${primaryRepo}'
        credentials {
            username = 'kagami'
            password = '${generatedToken}'
        }
    }
}`
      : `// build.gradle
repositories {
${selectedRepoIds.map(repo => `    maven {
        url '${baseUrl}/artifacts/${repo}'
        credentials {
            username = 'kagami'
            password = '${generatedToken}'
        }
    }`).join('\n')}
}`;

    const gradleKotlin = selectedRepoIds.length === 1
      ? `// build.gradle.kts
repositories {
    maven {
        url = uri("${baseUrl}/artifacts/${primaryRepo}")
        credentials {
            username = "kagami"
            password = "${generatedToken}"
        }
    }
}`
      : `// build.gradle.kts
repositories {
${selectedRepoIds.map(repo => `    maven {
        url = uri("${baseUrl}/artifacts/${repo}")
        credentials {
            username = "kagami"
            password = "${generatedToken}"
        }
    }`).join('\n')}
}`;

    return { maven, gradleGroovy, gradleKotlin };
  };

  if (reposLoading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <div className="flex justify-center items-center h-32">
          <LoadingSpinner size="lg" />
        </div>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="max-w-4xl mx-auto">
        {/* Header */}
        <header className="mb-8">
          <Link 
            to="/" 
            className="inline-flex items-center text-red-600 hover:text-red-700 mb-4"
          >
            <ArrowLeft className="h-4 w-4 mr-2" />
            Back to Repositories
          </Link>
          <div className="flex items-center space-x-3 mb-2">
            <Key className="h-8 w-8 text-red-600" />
            <h1 className="text-3xl font-bold text-gray-900">
              Generate Access Token
            </h1>
          </div>
          <p className="text-gray-600">
            Create JWT tokens for accessing private repositories
          </p>
        </header>

        {reposError && (
          <Alert variant="error" className="mb-6">
            <AlertDescription>
              Failed to load repositories: {reposError.message}
            </AlertDescription>
          </Alert>
        )}

        {!generatedToken ? (
          <div className="bg-white rounded-xl border border-gray-200 shadow-lg p-8">
            {/* Repository Selection */}
            <div className="mb-8">
              <h3 className="text-lg font-semibold text-gray-900 mb-4 flex items-center">
                <Shield className="h-5 w-5 text-red-600 mr-2" />
                Select Repositories
              </h3>
              <p className="text-sm text-gray-600 mb-4">
                Choose which repositories this token can access
              </p>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {repositories.map((repo) => (
                  <label
                    key={repo.id}
                    className="flex items-center p-3 border border-gray-200 rounded-lg hover:bg-gray-50 cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      checked={formData.repositories.includes(repo.id)}
                      onChange={(e) => handleRepositoryChange(repo.id, e.target.checked)}
                      className="h-4 w-4 text-red-600 rounded focus:ring-red-500"
                    />
                    <div className="ml-3 flex-1">
                      <div className="flex items-center space-x-2">
                        <span className="font-medium text-gray-900">{repo.id}</span>
                        {repo.isPrivate && <LockIcon className="h-4 w-4" />}
                      </div>
                      <span className="text-xs text-gray-500 font-mono">{repo.url}</span>
                    </div>
                  </label>
                ))}
              </div>
            </div>

            {/* Scope Selection */}
            <div className="mb-8">
              <h3 className="text-lg font-semibold text-gray-900 mb-4">
                Select Permissions
              </h3>
              <p className="text-sm text-gray-600 mb-4">
                Choose what actions this token can perform
              </p>
              <div className="space-y-3">
                {availableScopes.map((scope) => (
                  <label
                    key={scope.value}
                    className="flex items-start p-3 border border-gray-200 rounded-lg hover:bg-gray-50 cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      checked={formData.scopes.includes(scope.value)}
                      onChange={(e) => handleScopeChange(scope.value, e.target.checked)}
                      className="h-4 w-4 text-red-600 rounded focus:ring-red-500 mt-1"
                    />
                    <div className="ml-3">
                      <div className="font-medium text-gray-900">{scope.label}</div>
                      <div className="text-sm text-gray-600">{scope.description}</div>
                    </div>
                  </label>
                ))}
              </div>
            </div>

            {/* Expiration */}
            <div className="mb-8">
              <h3 className="text-lg font-semibold text-gray-900 mb-4 flex items-center">
                <Clock className="h-5 w-5 text-red-600 mr-2" />
                Token Expiration
              </h3>
              <p className="text-sm text-gray-600 mb-4">
                Set how long the token will remain valid
              </p>
              <div className="flex items-center space-x-3">
                <input
                  type="number"
                  min="1"
                  value={formData.duration}
                  onChange={(e) => setFormData(prev => ({ ...prev, duration: parseInt(e.target.value) || 1 }))}
                  className="w-20 px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-red-500"
                />
                <select
                  value={formData.unit}
                  onChange={(e) => setFormData(prev => ({ ...prev, unit: e.target.value as 'hours' | 'days' | 'months' }))}
                  className="px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-red-500"
                >
                  <option value="hours">Hours</option>
                  <option value="days">Days</option>
                  <option value="months">Months</option>
                </select>
              </div>
              
              {isLongDuration() && (
                <Alert variant="warning" className="mt-4">
                  <AlertTriangle className="h-4 w-4" />
                  <AlertDescription>
                    <strong>Long-lived token warning:</strong> This token will be valid for more than 6 months. 
                    Since JWT tokens cannot be revoked, consider using shorter durations for better security.
                  </AlertDescription>
                </Alert>
              )}
            </div>

            {/* Error Display */}
            {error && (
              <Alert variant="error" className="mb-6">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}

            {/* Generate Button */}
            <div className="flex justify-end">
              <Button
                onClick={generateToken}
                disabled={isGenerating || formData.repositories.length === 0 || formData.scopes.length === 0}
                className="px-6 py-2"
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
          <div className="bg-white rounded-xl border border-gray-200 shadow-lg p-8">
            <div className="text-center mb-6">
              <div className="inline-flex items-center justify-center w-16 h-16 bg-green-100 rounded-full mb-4">
                <Key className="h-8 w-8 text-green-600" />
              </div>
              <h3 className="text-xl font-semibold text-gray-900 mb-2">
                Token Generated Successfully!
              </h3>
              <p className="text-gray-600">
                Copy this token and store it securely. You won't be able to see it again.
              </p>
            </div>

            <div className="bg-gray-50 rounded-lg p-4 mb-6">
              <div className="flex items-center justify-between mb-2">
                <label className="text-sm font-medium text-gray-700">JWT Token:</label>
                <Button
                  onClick={() => copyToClipboard(generatedToken!, 'token')}
                  variant="ghost"
                  size="sm"
                  className="flex items-center space-x-1"
                >
                  <Copy className="h-4 w-4" />
                  <span>{copySuccess.token ? 'Copied!' : 'Copy'}</span>
                </Button>
              </div>
              <div className="bg-white border rounded p-3 font-mono text-sm break-all select-all">
                {generatedToken}
              </div>
            </div>

            {/* Configuration Examples */}
            <div className="mb-6">
              <h4 className="text-lg font-semibold text-gray-900 mb-4">
                Build Tool Configuration
              </h4>
              <p className="text-sm text-gray-600 mb-4">
                Use these configurations in your build tools to access the selected repositories with the generated token.
              </p>
              
              <div className="space-y-6">
                {(() => {
                  const configs = generateConfigExamples();
                  const examples = [
                    { key: 'maven', title: 'Maven (settings.xml)', content: configs.maven },
                    { key: 'gradleGroovy', title: 'Gradle Groovy (build.gradle)', content: configs.gradleGroovy },
                    { key: 'gradleKotlin', title: 'Gradle Kotlin (build.gradle.kts)', content: configs.gradleKotlin },
                  ];

                  return examples.map(({ key, title, content }) => (
                    <div key={key} className="border border-gray-200 rounded-lg">
                      <div className="flex items-center justify-between p-3 bg-gray-50 border-b border-gray-200">
                        <h5 className="font-medium text-gray-900">{title}</h5>
                        <Button
                          onClick={() => copyToClipboard(content, key)}
                          variant="ghost"
                          size="sm"
                          className="flex items-center space-x-1"
                        >
                          <Copy className="h-4 w-4" />
                          <span>{copySuccess[key] ? 'Copied!' : 'Copy'}</span>
                        </Button>
                      </div>
                      <div className="p-3">
                        <pre className="bg-gray-900 text-gray-100 p-3 rounded text-xs overflow-x-auto">
                          <code>{content}</code>
                        </pre>
                      </div>
                    </div>
                  ));
                })()}
              </div>
            </div>

            <div className="text-center">
              <Button onClick={reset} variant="ghost">
                Generate Another Token
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}