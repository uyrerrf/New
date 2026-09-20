import { useState, useEffect, useRef } from 'react';
import { builderApi } from '@/services/api';
import type { BuilderProgress } from '@/services/socket';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import {
  Wrench, Download, CheckCircle2, XCircle, Loader2, AlertCircle, X,
  Upload, Server, Package, Info,
} from 'lucide-react';

const BUILD_STEPS = ['checking', 'configuring', 'patching', 'signing'] as const;
type BuildStep = typeof BUILD_STEPS[number];

const STEP_LABELS: Record<BuildStep, string> = {
  checking: 'Checking Prerequisites',
  configuring: 'Configuring APK',
  patching: 'Patching Configuration',
  signing: 'Signing APK',
};

const MAX_APP_NAME_LENGTH = 50;

export default function BuilderPage() {
  const [serverUrl, setServerUrl] = useState('http://127.0.0.1:32766');
  const [detecting, setDetecting] = useState(true);
  const [alternatives, setAlternatives] = useState<string[]>([]);
  const [showServerUrl, setShowServerUrl] = useState(true);
  const [homePageUrl, setHomePageUrl] = useState('https://google.com');
  const [appName, setAppName] = useState('Fason');
  const [iconFile, setIconFile] = useState<File | null>(null);
  const [iconPreview, setIconPreview] = useState<string | null>(null);
  const [building, setBuilding] = useState(false);
  const [progress, setProgress] = useState<BuilderProgress | null>(null);
  const [buildComplete, setBuildComplete] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const iconPreviewUrlRef = useRef<string | null>(null);
  const dragCounterRef = useRef(0);

  const buildingTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    builderApi.getServerUrl()
      .then(res => {
        const data = res.data?.data;
        if (data?.detected) {
          setServerUrl(data.detected);
        }
        if (data?.alternatives && Array.isArray(data.alternatives)) {
          setAlternatives(data.alternatives);
        }
        if (typeof data?.showServerUrl === 'boolean') {
          setShowServerUrl(data.showServerUrl);
        }
      })
      .catch(() => {
        const protocol = window.location.protocol;
        const host = window.location.hostname;
        const port = window.location.port || '32766';
        if (host && host !== 'localhost' && host !== '127.0.0.1') {
          const fallback = port ? `${protocol}//${host}:${port}` : `${protocol}//${host}`;
          setServerUrl(fallback);
        }
      })
      .finally(() => setDetecting(false));
  }, []);

  useEffect(() => {
    if (!building) return;
    const pollInterval = setInterval(async () => {
      try {
        const res = await builderApi.getStatus();
        if (res.data?.data) {
          setProgress(res.data.data);
          if (res.data.data.complete) {
            setBuilding(false);
            if (buildingTimeoutRef.current) { clearTimeout(buildingTimeoutRef.current); buildingTimeoutRef.current = null; }
            if (!res.data.data.error) setBuildComplete(true);
            clearInterval(pollInterval);
          }
        }
      } catch {}
    }, 1000);
    return () => clearInterval(pollInterval);
  }, [building]);

  useEffect(() => {
    return () => {
      if (iconPreviewUrlRef.current) URL.revokeObjectURL(iconPreviewUrlRef.current);
      if (buildingTimeoutRef.current) clearTimeout(buildingTimeoutRef.current);
    };
  }, []);

  const processIconFile = (file: File | null) => {
    if (iconPreviewUrlRef.current) {
      URL.revokeObjectURL(iconPreviewUrlRef.current);
      iconPreviewUrlRef.current = null;
    }
    if (file) {

      if (!file.type.startsWith('image/')) {
        setError('Select a valid image file (PNG, JPEG, or WebP)');
        setIconFile(null);
        setIconPreview(null);
        return;
      }
      setIconFile(file);
      const url = URL.createObjectURL(file);
      iconPreviewUrlRef.current = url;
      setIconPreview(url);
      setError(null);
    } else {
      setIconFile(null);
      setIconPreview(null);
    }
  };

  const removeIcon = () => {
    if (iconPreviewUrlRef.current) {
      URL.revokeObjectURL(iconPreviewUrlRef.current);
      iconPreviewUrlRef.current = null;
    }
    setIconFile(null);
    setIconPreview(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleDragEnter = (e: React.DragEvent) => {
    e.preventDefault(); e.stopPropagation();
    dragCounterRef.current++;
    setIsDragging(true);
  };
  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault(); e.stopPropagation();
    dragCounterRef.current--;
    if (dragCounterRef.current === 0) setIsDragging(false);
  };
  const handleDragOver = (e: React.DragEvent) => { e.preventDefault(); e.stopPropagation(); };
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault(); e.stopPropagation();
    setIsDragging(false);
    dragCounterRef.current = 0;
    const file = e.dataTransfer.files?.[0] || null;
    if (file) processIconFile(file);
  };

  const startBuild = async () => {
    setError(null);
    if (!serverUrl.trim()) { setError('Server URL is required'); return; }
    if (!serverUrl.match(/^https?:\/\/.+/)) { setError('Server URL must start with http:// or https://'); return; }
    if (!homePageUrl.trim()) { setError('Home Page URL is required'); return; }
    if (!homePageUrl.match(/^https?:\/\/.+/)) { setError('Home Page URL must start with http:// or https://'); return; }
    if (!appName.trim()) { setError('App name is required'); return; }
    if (appName.trim().length > MAX_APP_NAME_LENGTH) { setError(`App name must be ${MAX_APP_NAME_LENGTH} characters or less`); return; }

    setBuilding(true);
    setProgress(null);
    setBuildComplete(false);

    if (buildingTimeoutRef.current) clearTimeout(buildingTimeoutRef.current);
    buildingTimeoutRef.current = setTimeout(() => {
      setBuilding(false);
      setError('Build timed out. Retry.');
    }, 10 * 60 * 1000);
    const formData = new FormData();
    formData.append('serverUrl', serverUrl.trim());
    formData.append('homePageUrl', homePageUrl.trim());
    formData.append('appName', appName.trim());
    if (iconFile) formData.append('appIcon', iconFile);

    try {
      const res = await builderApi.build(formData);
      if (!res.data.success) {
        setError(res.data.error || 'Build failed to start');
        setBuilding(false);
        if (buildingTimeoutRef.current) { clearTimeout(buildingTimeoutRef.current); buildingTimeoutRef.current = null; }
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to start build');
      setBuilding(false);
      if (buildingTimeoutRef.current) { clearTimeout(buildingTimeoutRef.current); buildingTimeoutRef.current = null; }
    }
  };

  const downloadApk = async () => {
    setDownloading(true);
    setDownloadProgress(0);
    try {
      const res = await builderApi.downloadApk((e) => {
        if (e.total) setDownloadProgress(Math.round((e.loaded * 100) / e.total));
      });
      const url = window.URL.createObjectURL(new Blob([res.data]));
      const link = document.createElement('a');
      link.href = url;

      const builtName = progress?.appName || appName || 'Fason';
      link.setAttribute('download', `${builtName}.apk`);
      document.body.appendChild(link);
      link.click();
      link.remove();

      setTimeout(() => window.URL.revokeObjectURL(url), 1000);
    } catch (err: any) {

      const blob = err?.response?.data;
      if (blob instanceof Blob) {
        try {
          const text = await blob.text();
          try {
            const parsed = JSON.parse(text);
            setError(parsed.error || 'Failed to download APK.');
          } catch {
            setError(text || 'Failed to download APK.');
          }
        } catch {
          setError('Failed to download APK. It may not be ready yet.');
        }
      } else {
        setError(err?.response?.data?.error || 'Failed to download APK. It may not be ready yet.');
      }
    } finally {
      setDownloading(false);
      setDownloadProgress(0);
    }
  };

  const getStepStatus = (step: BuildStep): 'pending' | 'active' | 'done' | 'failed' => {
    if (!progress) return 'pending';
    const currentIdx = BUILD_STEPS.indexOf(progress.step as BuildStep);
    const stepIdx = BUILD_STEPS.indexOf(step);
    if (progress.complete && progress.error && progress.step === step) return 'failed';
    if (progress.complete && !progress.error && progress.step === step) return 'done';
    if (!progress.complete && progress.step === step) return 'active';
    if (currentIdx > stepIdx) return 'done';
    return 'pending';
  };

  const getOverallPercent = (): number => {
    if (!progress) return 0;
    if (progress.complete) return 100;
    const idx = BUILD_STEPS.indexOf(progress.step as BuildStep);
    if (idx < 0) return 0;
    return Math.round(((idx + 0.5) / BUILD_STEPS.length) * 100);
  };

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
          <div className="h-8 w-8 rounded-lg bg-primary/10 flex items-center justify-center">
            <Wrench className="h-5 w-5 text-primary" />
          </div>
          APK Builder
        </h1>
        <p className="text-muted-foreground mt-1 text-sm">
          Build a custom APK with your server configuration. Requires Java Runtime on the server.
        </p>
      </div>

      {error && (
        <div className="p-4 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive flex items-start gap-3">
          <AlertCircle className="h-5 w-5 shrink-0 mt-0.5" />
          <div className="flex-1">
            <p className="font-medium text-sm">Error</p>
            <p className="text-sm mt-0.5 opacity-90">{error}</p>
          </div>
          <Button variant="ghost" size="icon" className="h-6 w-6 shrink-0" onClick={() => setError(null)}>
            <X className="h-3.5 w-3.5" />
          </Button>
        </div>
      )}

      <Card className="shadow-sm">
        <CardHeader>
          <CardTitle className="text-lg flex items-center gap-2">
            <Server className="h-5 w-5 text-primary" /> Configuration
          </CardTitle>
          <CardDescription>Configure the APK with your server details</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {!detecting && showServerUrl && (
          <div className="space-y-2">
            <Label htmlFor="serverUrl">Server URL</Label>
            <Input
              id="serverUrl"
              value={serverUrl}
              onChange={(e) => { setServerUrl(e.target.value); setError(null); }}
              placeholder="http://your-server:32766"
              disabled={building || detecting}
              className="font-mono text-sm"
            />
            <p className="text-xs text-muted-foreground">
              {detecting ? 'Detecting server address...' : 'The address your device will connect to the server on'}
            </p>
            {!detecting && alternatives.length > 1 && (
              <div className="flex flex-wrap gap-1.5 mt-2">
                {alternatives.map((alt) => {
                  const isActive = alt === serverUrl;
                  return (
                    <button
                      key={alt}
                      type="button"
                      onClick={() => { setServerUrl(alt); setError(null); }}
                      disabled={building}
                      className={`text-xs font-mono px-2 py-1 rounded-md border transition-colors ${
                        isActive
                          ? 'border-primary bg-primary/10 text-primary'
                          : 'border-border bg-muted/50 text-muted-foreground hover:border-primary/50 hover:text-foreground'
                      }`}
                      title={isActive ? 'Currently selected' : 'Click to use this address'}
                    >
                      {isActive ? '✓ ' : ''}{alt}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
          )}

          <div className="space-y-2">
            <Label htmlFor="homePageUrl">Home Page URL</Label>
            <Input
              id="homePageUrl"
              value={homePageUrl}
              onChange={(e) => { setHomePageUrl(e.target.value); setError(null); }}
              placeholder="https://google.com"
              disabled={building}
              className="font-mono text-sm"
            />
            <p className="text-xs text-muted-foreground">The web page shown when the app opens</p>
          </div>

          <div className="space-y-2">
            <Label htmlFor="appName">App Name</Label>
            <Input
              id="appName"
              value={appName}
              onChange={(e) => { setAppName(e.target.value); setError(null); }}
              placeholder="Fason"
              disabled={building}
              maxLength={MAX_APP_NAME_LENGTH}
            />
            <p className="text-xs text-muted-foreground">
              The display name of the app on the device
              <span className="ml-1 opacity-60">({appName.length}/{MAX_APP_NAME_LENGTH})</span>
            </p>
          </div>

          <div className="space-y-2">
            <Label>App Icon <span className="text-muted-foreground font-normal">(optional)</span></Label>

            {iconPreview ? (
              <div className="flex items-center gap-3 p-2 rounded-lg bg-muted/50">
                <div className="h-12 w-12 rounded-lg bg-muted flex items-center justify-center overflow-hidden border shrink-0">
                  <img src={iconPreview} alt="Preview" className="h-full w-full object-cover" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium truncate">{iconFile?.name}</p>
                  <p className="text-xs text-muted-foreground">{iconFile ? `${(iconFile.size / 1024).toFixed(1)} KB` : ''}</p>
                </div>
                <Button variant="outline" size="sm" onClick={removeIcon} disabled={building}>
                  <X className="h-3 w-3 mr-1" /> Remove
                </Button>
              </div>
            ) : (
              <div
                className={`rounded-lg border-2 border-dashed transition-colors cursor-pointer ${
                  isDragging ? 'border-primary bg-primary/5' : 'border-muted-foreground/25 hover:border-primary/50'
                }`}
                onClick={() => fileInputRef.current?.click()}
                onDragEnter={handleDragEnter}
                onDragLeave={handleDragLeave}
                onDragOver={handleDragOver}
                onDrop={handleDrop}
              >
                <div className="flex items-center justify-center gap-2 py-4 px-4">
                  <Upload className={`h-4 w-4 ${isDragging ? 'text-primary' : 'text-muted-foreground'}`} />
                  <p className="text-sm text-muted-foreground">
                    {isDragging ? 'Drop image here' : 'Drag & drop or click to upload'}
                  </p>
                </div>
              </div>
            )}
            <input
              ref={fileInputRef}
              type="file"
              accept="image/png,image/jpeg,image/webp"
              onChange={(e) => processIconFile(e.target.files?.[0] || null)}
              disabled={building}
              className="hidden"
            />
            {!iconFile && (
              <p className="text-xs text-muted-foreground">Leave empty to use the default icon</p>
            )}
          </div>

          <Button onClick={startBuild} disabled={building} className="w-full" size="lg">
            {building ? (
              <><Loader2 className="h-4 w-4 mr-2 animate-spin" /> Building...</>
            ) : (
              <><Wrench className="h-4 w-4 mr-2" /> Build APK</>
            )}
          </Button>
        </CardContent>
      </Card>

      {(building || progress) && (
        <Card className="shadow-sm">
          <CardHeader>
            <CardTitle className="text-lg flex items-center justify-between">
              <span className="flex items-center gap-2">
                {building ? (
                  <Loader2 className="h-5 w-5 text-primary animate-spin" />
                ) : progress?.complete ? (
                  progress.error ? (
                    <XCircle className="h-5 w-5 text-destructive" />
                  ) : (
                    <CheckCircle2 className="h-5 w-5 text-success" />
                  )
                ) : (
                  <Package className="h-5 w-5 text-muted-foreground" />
                )}
                Build Progress
              </span>
              {building && (
                <Badge variant="secondary" className="text-xs font-mono">{getOverallPercent()}%</Badge>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {BUILD_STEPS.map((step, index) => {
              const status = getStepStatus(step);
              const isLast = index === BUILD_STEPS.length - 1;
              return (
                <div key={step} className="flex items-start gap-3">
                  <div className="flex flex-col items-center">
                    <div className={`h-8 w-8 rounded-full flex items-center justify-center shrink-0 transition-colors ${
                      status === 'active' ? 'bg-primary text-primary-foreground ring-4 ring-primary/10' :
                      status === 'done' ? 'bg-success/15 text-success' :
                      status === 'failed' ? 'bg-destructive/15 text-destructive' :
                      'bg-muted text-muted-foreground'
                    }`}>
                      {status === 'done' ? <CheckCircle2 className="h-4 w-4" /> :
                       status === 'failed' ? <XCircle className="h-4 w-4" /> :
                       status === 'active' ? <Loader2 className="h-4 w-4 animate-spin" /> :
                       <Info className="h-4 w-4" />}
                    </div>
                    {!isLast && (
                      <div className={`w-0.5 h-4 ${status === 'done' ? 'bg-success/40' : 'bg-muted'}`} />
                    )}
                  </div>
                  <div className="flex-1 min-w-0 pt-1">
                    <div className="flex items-center gap-2">
                      <p className={`text-sm font-medium ${
                        status === 'active' ? 'text-foreground' :
                        status === 'done' ? 'text-success' :
                        status === 'failed' ? 'text-destructive' :
                        'text-muted-foreground'
                      }`}>
                        {STEP_LABELS[step]}
                      </p>
                      {status === 'active' && (
                        <Loader2 className="h-3 w-3 animate-spin text-primary" />
                      )}
                    </div>
                    {status === 'active' && progress?.message && (
                      <p className="text-xs text-muted-foreground mt-0.5 truncate">{progress.message}</p>
                    )}
                    {status === 'failed' && progress?.error && (
                      <p className="text-xs text-destructive/80 mt-0.5 break-all">{progress.error}</p>
                    )}
                  </div>
                </div>
              );
            })}

            {building && (
              <div className="pt-2 border-t">
                <div className="w-full bg-muted rounded-full h-1.5 overflow-hidden">
                  <div
                    className="bg-primary h-1.5 rounded-full transition-all duration-700"
                    style={{ width: `${getOverallPercent()}%` }}
                  />
                </div>
              </div>
            )}

            {progress?.complete && !progress.error && (
              <div className="mt-2 p-3 rounded-lg bg-success/10 border border-success/20 flex items-center gap-2">
                <CheckCircle2 className="h-4 w-4 text-success shrink-0" />
                <p className="text-sm text-success font-medium">Build completed successfully!</p>
              </div>
            )}
            {progress?.complete && progress.error && (
              <div className="mt-2 p-3 rounded-lg bg-destructive/10 border border-destructive/20 flex items-start gap-2">
                <XCircle className="h-4 w-4 text-destructive shrink-0 mt-0.5" />
                <div>
                  <p className="text-sm text-destructive font-medium">Build failed</p>
                  <p className="text-xs text-destructive/80 mt-0.5 break-all">{progress.error}</p>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {buildComplete && (
        <Card className="shadow-sm border-success/30">
          <CardContent className="p-6">
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-3">
                <div className="h-10 w-10 rounded-lg bg-success/15 flex items-center justify-center shrink-0">
                  <CheckCircle2 className="h-5 w-5 text-success" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold">APK Ready</h3>
                  <p className="text-xs text-muted-foreground">{appName}.apk is ready to download</p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                {downloading && (
                  <span className="text-xs text-muted-foreground">{downloadProgress}%</span>
                )}
                <Button onClick={downloadApk} disabled={downloading} className="gap-2">
                  {downloading ? (
                    <><Loader2 className="h-4 w-4 animate-spin" /> Downloading...</>
                  ) : (
                    <><Download className="h-4 w-4" /> Download</>
                  )}
                </Button>
              </div>
            </div>
            {downloading && (
              <div className="mt-3">
                <div className="w-full bg-muted rounded-full h-2 overflow-hidden">
                  <div
                    className="bg-primary h-2 rounded-full transition-all duration-300"
                    style={{ width: `${downloadProgress}%` }}
                  />
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
