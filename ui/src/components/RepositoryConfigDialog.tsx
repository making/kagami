import { useState } from 'react';
import { Dialog, DialogHeader, DialogTitle, DialogClose, DialogContent } from './ui/Dialog';
import { Copy, Check } from 'lucide-react';
import type { RepositoryInfo } from '../types/api';
import { generateConfigExample, type AuthMethod, type BuildTool } from '../utils/configExamples';

interface RepositoryConfigDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  repository: RepositoryInfo | null;
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

export function RepositoryConfigDialog({ open, onOpenChange, repository }: RepositoryConfigDialogProps) {
  const [selectedTool, setSelectedTool] = useState<BuildTool>('maven');
  const [authMethod, setAuthMethod] = useState<AuthMethod>('basic');
  const [copiedConfig, setCopiedConfig] = useState<string | null>(null);

  if (!repository) return null;

  const baseUrl = `${window.location.origin}/artifacts/${repository.id}`;

  const getConfiguration = (tool: BuildTool) => {
    const token = repository.isPrivate ? 'YOUR_JWT_TOKEN_HERE' : '';
    return generateConfigExample(tool, {
      repositoryIds: [repository.id],
      token,
      baseUrl: window.location.origin,
      isPrivate: repository.isPrivate,
      authMethod
    });
  };

  const handleCopy = async (content: string, configType: string) => {
    try {
      await navigator.clipboard.writeText(content);
      setCopiedConfig(configType);
      setTimeout(() => setCopiedConfig(null), 2000);
    } catch (error) {
      console.error('Failed to copy to clipboard:', error);
    }
  };

  const config = getConfiguration(selectedTool);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogHeader>
        <DialogTitle className="registry-label font-semibold font-mono flex items-center gap-3 before:content-[''] before:w-[9px] before:h-[9px] before:shrink-0 before:bg-gradient-to-r before:from-accent before:to-magenta">
          Repository Configuration / {repository.id}
        </DialogTitle>
        <DialogClose onClick={() => onOpenChange(false)} />
      </DialogHeader>

      <DialogContent>
        <div className="space-y-6">
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

          {/* Authentication Method Tabs (private repositories only) */}
          {repository.isPrivate && (
            <div className="flex border border-line">
              <Tab active={authMethod === 'basic'} onClick={() => setAuthMethod('basic')}>
                Username / Password
              </Tab>
              <Tab active={authMethod === 'bearer'} onClick={() => setAuthMethod('bearer')}>
                Bearer Token
              </Tab>
            </div>
          )}

          {/* Repository Information */}
          <div className="border border-line">
            <div className="registry-label font-semibold px-4 py-2.5 border-b border-line">
              Repository Information
            </div>
            <div className="p-4 space-y-1.5 text-[12px] text-ink-2">
              <div><span className="registry-label text-ink-3 mr-2">ID</span> {repository.id}</div>
              <div className="break-all"><span className="registry-label text-ink-3 mr-2">URL</span> {baseUrl}</div>
              <div className="break-all"><span className="registry-label text-ink-3 mr-2">Origin</span> {repository.url}</div>
            </div>
          </div>

          {/* Configuration */}
          <div className="border border-line">
            <div className="flex items-center justify-between px-4 py-2 border-b border-line">
              <h3 className="registry-label font-semibold">{config.title}</h3>
              <button
                className="registry-label flex items-center gap-1.5 border border-line px-3 py-1.5 cursor-pointer text-ink-2 font-mono transition-colors hover:bg-ink hover:text-white hover:border-ink"
                onClick={() => handleCopy(config.content, selectedTool)}
              >
                {copiedConfig === selectedTool ? (
                  <>
                    <Check className="h-3 w-3" />
                    Copied!
                  </>
                ) : (
                  <>
                    <Copy className="h-3 w-3" />
                    Copy
                  </>
                )}
              </button>
            </div>
            <div className="px-4 py-3 text-[12px] text-ink-2 border-b border-line">
              Add this configuration to your <code className="bg-wash text-accent px-1">{config.filename}</code>:
            </div>
            <pre className="bg-ink text-[#f4f0f2] p-4 overflow-x-auto text-xs leading-relaxed">
              <code>{config.content}</code>
            </pre>
          </div>

          {/* Authentication Notice for Private Repositories */}
          {repository.isPrivate && (
            <div className="border border-line border-l-4 border-l-magenta p-4">
              <h4 className="registry-label font-semibold text-magenta mb-2">Authentication Required</h4>
              <div className="text-[12px] text-ink-2 space-y-2">
                <p>This is a private repository that requires JWT token authentication.</p>
                <p className="font-semibold">To generate a JWT token:</p>
                <ol className="list-decimal list-inside space-y-1 ml-2">
                  <li>Go to the <a href="/token" className="text-accent underline hover:text-magenta">Generate Token</a> page</li>
                  <li>Select this repository ({repository.id})</li>
                  <li>Choose your required permissions (artifacts:read for downloads)</li>
                  <li>Set token expiration (recommended: 6 months or less)</li>
                  <li>Generate the token and replace <code className="bg-wash text-accent px-1">YOUR_JWT_TOKEN_HERE</code> in the configuration above</li>
                </ol>
              </div>
            </div>
          )}

          {/* Usage Notes */}
          <div className="border border-line border-l-4 border-l-ink p-4">
            <h4 className="registry-label font-semibold mb-2">Usage Notes</h4>
            <ul className="text-[12px] text-ink-2 space-y-1">
              <li>&mdash; This Kagami server caches artifacts from the original repository</li>
              <li>&mdash; Artifacts are fetched on-demand and cached locally for faster subsequent access</li>
              <li>&mdash; You can use this as a primary repository or as a mirror for faster downloads</li>
              {repository.id === 'central' && (
                <li>&mdash; This mirrors Maven Central - you can use it as a drop-in replacement</li>
              )}
              {repository.isPrivate && (
                <li>&mdash; JWT tokens cannot be revoked - use appropriate expiration times for security</li>
              )}
            </ul>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
