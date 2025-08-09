import { useState } from 'react';
import { Dialog, DialogHeader, DialogTitle, DialogClose, DialogContent } from './ui/Dialog';
import { Button } from './ui/Button';
import { Copy, Check } from 'lucide-react';
import type { RepositoryInfo } from '../types/api';

interface RepositoryConfigDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  repository: RepositoryInfo | null;
}

type BuildTool = 'maven' | 'gradle-groovy' | 'gradle-kotlin';

export function RepositoryConfigDialog({ open, onOpenChange, repository }: RepositoryConfigDialogProps) {
  const [selectedTool, setSelectedTool] = useState<BuildTool>('maven');
  const [copiedConfig, setCopiedConfig] = useState<string | null>(null);

  if (!repository) return null;

  const baseUrl = `${window.location.origin}/artifacts/${repository.id}`;

  const getConfiguration = (tool: BuildTool): { title: string; content: string; filename: string } => {
    switch (tool) {
      case 'maven':
        return {
          title: 'Maven Configuration',
          filename: 'pom.xml or settings.xml',
          content: `<!-- RECOMMENDED: Add to your settings.xml as a profile -->
<settings>
  <profiles>
    <profile>
      <id>kagami-${repository.id}</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <repositories>
        <repository>
          <id>kagami-${repository.id}</id>
          <name>Kagami Repository - ${repository.id}</name>
          <url>${baseUrl}</url>
          <snapshots>
            <enabled>${repository.id.includes('snapshot') ? 'true' : 'false'}</enabled>
          </snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>kagami-${repository.id}</id>
          <name>Kagami Repository - ${repository.id}</name>
          <url>${baseUrl}</url>
          <snapshots>
            <enabled>${repository.id.includes('snapshot') ? 'true' : 'false'}</enabled>
          </snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
</settings>

<!-- ALTERNATIVE: Mirror configuration -->
<!-- Use mirrors when you want to redirect ALL Maven repository requests through Kagami -->
<!-- This is useful for: -->
<!-- - Corporate environments where all external access must go through a proxy -->
<!-- - Offline environments where only Kagami has access to external repositories -->
<!-- - Performance optimization when Kagami has better network access to upstream repos -->
<!--
<settings>
  <mirrors>
    <mirror>
      <id>kagami-${repository.id}</id>
      <mirrorOf>${repository.id === 'central' ? 'central' : '*'}</mirrorOf>
      <name>Kagami Mirror for ${repository.id}</name>
      <url>${baseUrl}</url>
    </mirror>
  </mirrors>
</settings>
-->

<!-- SIMPLE: Add directly to your pom.xml (project-specific) -->
<!--
<repositories>
  <repository>
    <id>${repository.id}</id>
    <name>${repository.id.charAt(0).toUpperCase() + repository.id.slice(1)} Repository</name>
    <url>${baseUrl}</url>
  </repository>
</repositories>
-->`
        };

      case 'gradle-groovy':
        return {
          title: 'Gradle Configuration (Groovy DSL)',
          filename: 'build.gradle',
          content: `repositories {
    maven {
        name = "${repository.id}"
        url = "${baseUrl}"
    }
    
    // You can also add it as the first repository for priority
    // maven { url "${baseUrl}" }
    // mavenCentral() // fallback
}`
        };

      case 'gradle-kotlin':
        return {
          title: 'Gradle Configuration (Kotlin DSL)',
          filename: 'build.gradle.kts',
          content: `repositories {
    maven {
        name = "${repository.id}"
        url = uri("${baseUrl}")
    }
    
    // You can also add it as the first repository for priority
    // maven { url = uri("${baseUrl}") }
    // mavenCentral() // fallback
}`
        };
    }
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
        <DialogTitle>
          Repository Configuration - {repository.id}
        </DialogTitle>
        <DialogClose onClick={() => onOpenChange(false)} />
      </DialogHeader>
      
      <DialogContent>
        <div className="space-y-6">
          {/* Build Tool Tabs */}
          <div className="flex space-x-1 bg-gray-100 rounded-lg p-1">
            <button
              className={`flex-1 py-2 px-3 text-sm font-medium rounded-md transition-colors ${
                selectedTool === 'maven'
                  ? 'bg-white text-red-700 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              }`}
              onClick={() => setSelectedTool('maven')}
            >
              Maven
            </button>
            <button
              className={`flex-1 py-2 px-3 text-sm font-medium rounded-md transition-colors ${
                selectedTool === 'gradle-groovy'
                  ? 'bg-white text-red-700 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              }`}
              onClick={() => setSelectedTool('gradle-groovy')}
            >
              Gradle (Groovy)
            </button>
            <button
              className={`flex-1 py-2 px-3 text-sm font-medium rounded-md transition-colors ${
                selectedTool === 'gradle-kotlin'
                  ? 'bg-white text-red-700 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              }`}
              onClick={() => setSelectedTool('gradle-kotlin')}
            >
              Gradle (Kotlin)
            </button>
          </div>

          {/* Repository Information */}
          <div className="bg-gray-50 rounded-lg p-4">
            <h3 className="font-medium text-gray-900 mb-2">Repository Information</h3>
            <div className="space-y-1 text-sm text-gray-600">
              <div><span className="font-medium">ID:</span> {repository.id}</div>
              <div><span className="font-medium">URL:</span> {baseUrl}</div>
              <div><span className="font-medium">Original:</span> {repository.url}</div>
            </div>
          </div>

          {/* Configuration */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="font-medium text-gray-900">{config.title}</h3>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleCopy(config.content, selectedTool)}
                className="text-gray-600 hover:text-red-700"
              >
                {copiedConfig === selectedTool ? (
                  <>
                    <Check className="h-4 w-4 mr-1" />
                    Copied!
                  </>
                ) : (
                  <>
                    <Copy className="h-4 w-4 mr-1" />
                    Copy
                  </>
                )}
              </Button>
            </div>
            
            <div className="text-sm text-gray-600 mb-2">
              Add this configuration to your <code className="bg-gray-100 px-1 rounded">{config.filename}</code>:
            </div>
            
            <div className="relative">
              <pre className="bg-gray-900 text-gray-100 p-4 rounded-lg overflow-x-auto text-sm">
                <code>{config.content}</code>
              </pre>
            </div>
          </div>

          {/* Usage Notes */}
          <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
            <h4 className="font-medium text-blue-900 mb-2">Usage Notes</h4>
            <ul className="text-sm text-blue-800 space-y-1">
              <li>• This Kagami server caches artifacts from the original repository</li>
              <li>• Artifacts are fetched on-demand and cached locally for faster subsequent access</li>
              <li>• You can use this as a primary repository or as a mirror for faster downloads</li>
              {repository.id === 'central' && (
                <li>• This mirrors Maven Central - you can use it as a drop-in replacement</li>
              )}
            </ul>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}