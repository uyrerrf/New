import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Eye, EyeOff, CheckCircle2, Circle, Loader2, Copy, RefreshCw, AlertCircle, Shield, ArrowRight, ArrowLeft, Lock } from 'lucide-react';
import { copyToClipboard } from '@/lib/utils';

interface SetupSteps {
  admin: boolean;
  deviceSecret: boolean;
}

interface SetupStatus {
  complete: boolean;
  steps: SetupSteps;
}

type Step = 'welcome' | 'admin' | 'device' | 'review' | 'done';

export default function SetupPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>('welcome');
  const [status, setStatus] = useState<SetupStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [username, setUsername] = useState('admin');
  const [email, setEmail] = useState('admin@fason.com');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [confirmPassword, setConfirmPassword] = useState('');

  const [deviceSecretMode, setDeviceSecretMode] = useState<'generate' | 'manual'>('generate');
  const [deviceSecret, setDeviceSecret] = useState('');
  const [showDeviceSecret, setShowDeviceSecret] = useState(false);
  const [generatedSecret, setGeneratedSecret] = useState('');

  useEffect(() => {
    checkStatus();
  }, []);

  async function checkStatus() {
    try {
      const res = await axios.get('/api/setup/status');
      setStatus(res.data.data);
      if (res.data.data.complete) {
        navigate('/login', { replace: true });
        return;
      }
      if (res.data.data.steps.admin) {
        setStep('device');
      }
    } catch {
      setError('Failed to check setup status');
    } finally {
      setLoading(false);
    }
  }

  function generateSecret() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
    const charsLen = chars.length;

    const maxByte = 256 - (256 % charsLen);
    let s = '';
    const arr = new Uint8Array(64);
    crypto.getRandomValues(arr);
    let i = 0;
    while (s.length < 32 && i < arr.length) {
      const b = arr[i++];
      if (b < maxByte) s += chars[b % charsLen];
    }

    if (s.length < 32) {
      const arr2 = new Uint8Array(32);
      crypto.getRandomValues(arr2);
      for (let j = 0; j < arr2.length && s.length < 32; j++) s += chars[arr2[j] % charsLen];
    }
    setGeneratedSecret(s);
    setDeviceSecret(s);
  }

  function handleAdminNext(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (username.length < 3) {
      setError('Username must be at least 3 characters');
      return;
    }
    if (!/^[a-zA-Z0-9_]+$/.test(username)) {
      setError('Username can only contain letters, numbers, and underscores');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      setError('Enter a valid email address');
      return;
    }
    if (password.length < 8) {
      setError('Password must be at least 8 characters');
      return;
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match');
      return;
    }

    setStep('device');
  }

  function handleDeviceNext() {
    setError(null);

    if (deviceSecretMode === 'manual') {
      if (deviceSecret.length < 8) {
        setError('Device secret must be at least 8 characters');
        return;
      }
      if (/[\r\n=]/.test(deviceSecret)) {
        setError('Device secret must not contain newlines or "=" characters');
        return;
      }
    }

    setStep('review');
  }

  async function handleComplete() {
    setError(null);
    setSubmitting(true);

    try {
      const payload: any = {
        admin: { username, email, password },
      };

      if (deviceSecretMode === 'generate') {

        payload.generateDeviceSecret = true;

        setDeviceSecret('');
      } else if (deviceSecretMode === 'manual') {
        payload.deviceSecret = deviceSecret;
      }

      const res = await axios.post('/api/setup/complete', payload);

      if (deviceSecretMode === 'generate' && res?.data?.data?.deviceSecret) {
        setDeviceSecret(res.data.data.deviceSecret);
      }
      setStep('done');
    } catch (err: any) {
      const msg = err?.response?.data?.error || 'Setup failed. Try again.';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <Loader2 className="h-6 w-6 animate-spin text-primary" />
      </div>
    );
  }

  const stepNumber = step === 'welcome' ? 0 : step === 'admin' ? 1 : step === 'device' ? 2 : step === 'review' ? 3 : 4;
  const steps = ['welcome', 'admin', 'device', 'review'] as const;
  const currentIdx = steps.indexOf(step as typeof steps[number]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-background via-background to-muted/30 p-4">
      <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-primary/5 rounded-full blur-3xl" />
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-primary/5 rounded-full blur-3xl" />
      </div>

      <Card className="w-full max-w-[480px] shadow-xl border-0 bg-card/80 backdrop-blur-sm relative">
        <CardHeader className="text-center space-y-4 pb-3">
          <div className="flex items-center justify-center mx-auto">
            <img src="/favicon.svg" alt="Fason" className="h-12 w-12 shadow-lg shadow-primary/20 rounded-xl" />
          </div>
          <div>
            <CardTitle className="text-xl">Initial Setup</CardTitle>
            <CardDescription className="mt-1">
              {step === 'done' ? 'Setup complete' : step === 'welcome' ? "Welcome. Let's configure your server" : `Step ${stepNumber} of 3`}
            </CardDescription>
          </div>
        </CardHeader>

        {step !== 'done' && step !== 'welcome' && (
          <div className="px-6 pb-2">
            <div className="flex items-center gap-1.5">
              {steps.slice(1).map((s, i) => (
                <div
                  key={s}
                  className={`h-1 flex-1 rounded-full transition-colors ${
                    i < currentIdx ? 'bg-primary' : i === currentIdx - 1 ? 'bg-primary' : 'bg-muted'
                  }`}
                />
              ))}
            </div>
          </div>
        )}

        {error && (
          <div className="mx-6 mb-3 p-3 rounded-lg border bg-destructive/10 border-destructive/20 text-destructive text-sm flex items-center gap-2">
            <AlertCircle className="h-4 w-4 shrink-0" />
            {error}
          </div>
        )}

        {step === 'welcome' && (
          <CardContent className="space-y-5">
            <div className="space-y-3">
              <SetupStepRow done={status?.steps.admin} label="Create admin account" icon={Shield} />
              <SetupStepRow done={status?.steps.deviceSecret} label="Configure device secret" icon={Lock} />
            </div>
            <div className="rounded-lg border border-border bg-muted/30 p-3 text-xs text-muted-foreground space-y-1.5">
              <p className="font-medium text-foreground">Optional environment variables:</p>
              <p className="font-mono text-[11px]">BETTER_AUTH_SECRET: session signing</p>
              <p className="font-mono text-[11px]">BETTER_AUTH_URL: public server URL</p>
              <p className="text-[11px] pt-1">Can be set later via .env and restart.</p>
            </div>
            <Button className="w-full" size="lg" onClick={() => setStep(status?.steps.admin ? 'device' : 'admin')}>
              {status?.steps.admin ? 'Continue Setup' : 'Get Started'}
            </Button>
          </CardContent>
        )}

        {step === 'admin' && (
          <form onSubmit={handleAdminNext}>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="username">Username</Label>
                <Input
                  id="username"
                  type="text"
                  placeholder="admin"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  required
                  autoFocus
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="email">Email</Label>
                <Input
                  id="email"
                  type="email"
                  placeholder="admin@example.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="password">Password</Label>
                <div className="relative">
                  <Input
                    id="password"
                    type={showPassword ? 'text' : 'password'}
                    placeholder="At least 8 characters"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                    className="pr-10"
                  />
                  <button
                    type="button"
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    onClick={() => setShowPassword(!showPassword)}
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </div>
              <div className="space-y-2">
                <Label htmlFor="confirmPassword">Confirm Password</Label>
                <Input
                  id="confirmPassword"
                  type={showPassword ? 'text' : 'password'}
                  placeholder="Re-enter password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  required
                />
              </div>
            </CardContent>
            <CardFooter className="gap-2 pt-2">
              <Button type="button" variant="outline" onClick={() => setStep('welcome')} className="flex-1 gap-1.5">
                <ArrowLeft className="h-4 w-4" /> Back
              </Button>
              <Button type="submit" className="flex-1 gap-1.5">
                Next <ArrowRight className="h-4 w-4" />
              </Button>
            </CardFooter>
          </form>
        )}

        {step === 'device' && (
          <>
            <CardContent className="space-y-3">
              <p className="text-xs text-muted-foreground">
                Your admin secret. Embedded in APKs and required for device connections. Changeable later in Settings.
              </p>

              <button
                type="button"
                onClick={() => { setDeviceSecretMode('generate'); if (!generatedSecret) generateSecret(); }}
                className={`w-full text-left p-3 rounded-lg border transition-colors ${
                  deviceSecretMode === 'generate'
                    ? 'border-primary bg-primary/5'
                    : 'border-border hover:border-primary/50'
                }`}
              >
                <div className="flex items-center gap-2">
                  {deviceSecretMode === 'generate' ? (
                    <CheckCircle2 className="h-4 w-4 text-primary" />
                  ) : (
                    <Circle className="h-4 w-4 text-muted-foreground" />
                  )}
                  <span className="font-medium text-sm">Auto-generate</span>
                  <span className="text-[10px] text-primary font-medium ml-auto">RECOMMENDED</span>
                </div>
                {deviceSecretMode === 'generate' && (
                  <div className="mt-2 ml-6 space-y-1.5">
                    {generatedSecret ? (
                      <div className="flex items-center gap-2">
                        <code className="text-xs bg-muted px-2 py-1 rounded font-mono flex-1 truncate">
                          {showDeviceSecret ? generatedSecret : '••••••••••••••••••••'}
                        </code>
                        <button type="button" onClick={() => setShowDeviceSecret(!showDeviceSecret)} className="text-muted-foreground hover:text-foreground">
                          {showDeviceSecret ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                        </button>
                        <button type="button" onClick={() => copyToClipboard(generatedSecret)} className="text-muted-foreground hover:text-foreground">
                          <Copy className="h-3.5 w-3.5" />
                        </button>
                        <button type="button" onClick={generateSecret} className="text-muted-foreground hover:text-foreground">
                          <RefreshCw className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    ) : (
                      <p className="text-xs text-muted-foreground">A secret will be generated when you continue.</p>
                    )}
                    <p className="text-[10px] text-amber-600">
                      Preview only. Copy the secret after setup completes.
                    </p>
                  </div>
                )}
              </button>

              <button
                type="button"
                onClick={() => setDeviceSecretMode('manual')}
                className={`w-full text-left p-3 rounded-lg border transition-colors ${
                  deviceSecretMode === 'manual'
                    ? 'border-primary bg-primary/5'
                    : 'border-border hover:border-primary/50'
                }`}
              >
                <div className="flex items-center gap-2">
                  {deviceSecretMode === 'manual' ? (
                    <CheckCircle2 className="h-4 w-4 text-primary" />
                  ) : (
                    <Circle className="h-4 w-4 text-muted-foreground" />
                  )}
                  <span className="font-medium text-sm">Enter manually</span>
                </div>
              </button>

              {deviceSecretMode === 'manual' && (
                <Input
                  type="text"
                  placeholder="Min 8 characters"
                  value={deviceSecret}
                  onChange={(e) => setDeviceSecret(e.target.value)}
                  className="font-mono text-sm"
                  autoFocus
                />
              )}
            </CardContent>
            <CardFooter className="gap-2 pt-2">
              <Button type="button" variant="outline" onClick={() => setStep('admin')} className="flex-1 gap-1.5">
                <ArrowLeft className="h-4 w-4" /> Back
              </Button>
              <Button type="button" onClick={handleDeviceNext} className="flex-1 gap-1.5">
                Next <ArrowRight className="h-4 w-4" />
              </Button>
            </CardFooter>
          </>
        )}

        {step === 'review' && (
          <>
            <CardContent className="space-y-4">
              <div className="rounded-lg border divide-y">
                <ReviewRow label="Username" value={username} />
                <ReviewRow label="Email" value={email} />
                <ReviewRow label="Password" value="••••••••" />
                <ReviewRow
                  label="Device Secret"
                  value={
                    deviceSecretMode === 'generate' ? 'Auto-generated' : deviceSecret
                  }
                />
              </div>
              <div className="flex items-start gap-2 text-xs text-muted-foreground">
                <Shield className="h-3.5 w-3.5 shrink-0 mt-0.5" />
                <p>Save your credentials. You'll be redirected to login after completing.</p>
              </div>
            </CardContent>
            <CardFooter className="gap-2 pt-2">
              <Button type="button" variant="outline" onClick={() => setStep('device')} className="flex-1 gap-1.5" disabled={submitting}>
                <ArrowLeft className="h-4 w-4" /> Back
              </Button>
              <Button type="button" onClick={handleComplete} className="flex-1" disabled={submitting}>
                {submitting ? <><Loader2 className="h-4 w-4 mr-2 animate-spin" /> Completing...</> : 'Complete Setup'}
              </Button>
            </CardFooter>
          </>
        )}

        {step === 'done' && (
          <CardContent className="space-y-5 text-center py-6">
            <div className="h-16 w-16 mx-auto rounded-full bg-green-500/10 flex items-center justify-center">
              <CheckCircle2 className="h-8 w-8 text-green-500" />
            </div>
            <div className="space-y-1">
              <h3 className="text-lg font-semibold">Setup Complete</h3>
              <p className="text-sm text-muted-foreground">
                Your server is ready. Log in with your admin credentials.
              </p>
            </div>
            {deviceSecret ? (
              <div className="rounded-lg border bg-muted/50 p-3 text-left">
                <p className="text-xs font-medium mb-1.5 text-muted-foreground">Your device secret (save this):</p>
                <div className="flex items-center gap-2">
                  <code className="text-xs font-mono flex-1 break-all text-foreground">{deviceSecret}</code>
                  <button
                    type="button"
                    onClick={() => copyToClipboard(deviceSecret)}
                    className="text-muted-foreground hover:text-foreground shrink-0"
                  >
                    <Copy className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>
            ) : deviceSecretMode === 'generate' ? (
              <div className="rounded-lg border bg-muted/50 p-3 text-left">
                <p className="text-xs text-muted-foreground">
                  A device secret was generated automatically. You can view and manage it in
                  Settings after logging in.
                </p>
              </div>
            ) : null}
            <Button className="w-full" size="lg" onClick={() => navigate('/login', { replace: true })}>
              Go to Login <ArrowRight className="h-4 w-4 ml-1.5" />
            </Button>
          </CardContent>
        )}
      </Card>
    </div>
  );
}

function SetupStepRow({ done, label, icon: Icon }: { done?: boolean; label: string; icon: React.ElementType }) {
  return (
    <div className="flex items-center gap-3">
      <div className={`h-8 w-8 rounded-lg flex items-center justify-center shrink-0 ${
        done ? 'bg-green-500/10' : 'bg-primary/10'
      }`}>
        {done ? (
          <CheckCircle2 className="h-4 w-4 text-green-500" />
        ) : (
          <Icon className="h-4 w-4 text-primary" />
        )}
      </div>
      <span className={`text-sm ${done ? 'text-muted-foreground line-through' : 'text-foreground'}`}>
        {label}
      </span>
    </div>
  );
}

function ReviewRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between items-center gap-4 px-3 py-2.5">
      <span className="text-xs text-muted-foreground shrink-0">{label}</span>
      <span className="text-sm font-medium text-right truncate">{value}</span>
    </div>
  );
}
