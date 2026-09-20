import { useState, useEffect, useRef } from 'react';
import { authApi, configApi } from '@/services/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Save, AlertCircle, CheckCircle, User, Lock, ShieldCheck, Shield, KeyRound, RefreshCw, Eye, EyeOff, Monitor, Trash2, Copy, AlertTriangle, Wrench } from 'lucide-react';
import { copyToClipboard } from '@/lib/utils';

interface SessionInfo {
  id: string;
  userId: string;
  username: string;
  ip: string;
  userAgent: string | null;
  createdAt: string;
  expiresAt: string;
  isCurrent: boolean;
}

export default function SettingsPage() {
  const { user, checkAuth, hasPermission } = useAuthStore();
  const canViewSettings = hasPermission('settings:view');
  const canEditSettings = hasPermission('settings:edit');

  const [profileUsername, setProfileUsername] = useState(user?.username || '');
  const [profileEmail, setProfileEmail] = useState(user?.email || '');
  const [profileSaving, setProfileSaving] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [profileSuccess, setProfileSuccess] = useState(false);

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmNewPassword, setConfirmNewPassword] = useState('');
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [passwordSuccess, setPasswordSuccess] = useState(false);

  const [deviceSecret, setDeviceSecret] = useState<string | null>(null);
  const [secretInput, setSecretInput] = useState('');
  const [secretVisible, setSecretVisible] = useState(false);
  const [secretRegenerating, setSecretRegenerating] = useState(false);
  const [secretSaving, setSecretSaving] = useState(false);
  const [secretError, setSecretError] = useState<string | null>(null);
  const [secretSuccess, setSecretSuccess] = useState<string | null>(null);
  const [secretCopied, setSecretCopied] = useState(false);
  const [regenDialogOpen, setRegenDialogOpen] = useState(false);
  const secretTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const [showServerUrl, setShowServerUrl] = useState(true);
  const [builderSettingsLoading, setBuilderSettingsLoading] = useState(false);
  const [builderSettingsError, setBuilderSettingsError] = useState<string | null>(null);

  const secretDirty = secretInput !== (deviceSecret || '') && secretInput.trim().length > 0;

  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(false);
  const [sessionsError, setSessionsError] = useState<string | null>(null);
  const [revokingId, setRevokingId] = useState<string | null>(null);

  const profileTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const passwordTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (profileTimerRef.current) clearTimeout(profileTimerRef.current);
      if (passwordTimerRef.current) clearTimeout(passwordTimerRef.current);
      if (secretTimerRef.current) clearTimeout(secretTimerRef.current);
      if (copyTimerRef.current) clearTimeout(copyTimerRef.current);
    };
  }, []);

  useEffect(() => {

    configApi.getDeviceSecret()
      .then((res) => {
        if (res.data.success) {
          const s = res.data.data?.deviceSecret || null;
          setDeviceSecret(s);
          setSecretInput(s || '');
        }
      })
      .catch(() => {  });

    if (!canViewSettings) return;
    configApi.get()
      .then((res) => {
        if (res.data.success) {
          setShowServerUrl(res.data.data?.build?.showServerUrl !== false);
        }
      })
      .catch(() => {  });
  }, [canViewSettings]);

  const handleToggleShowServerUrl = async (value: boolean) => {
    setShowServerUrl(value);
    setBuilderSettingsLoading(true);
    setBuilderSettingsError(null);
    try {
      await configApi.set('build.showServerUrl', String(value));
    } catch (err: any) {
      setShowServerUrl(!value);

      setBuilderSettingsError(err?.response?.data?.error || 'Failed to update setting');
    }
    setBuilderSettingsLoading(false);
  };

  const handleCopySecret = async () => {
    const val = secretInput || deviceSecret;
    if (!val) return;
    const ok = await copyToClipboard(val);
    if (ok) {
      setSecretCopied(true);
      if (copyTimerRef.current) clearTimeout(copyTimerRef.current);
      copyTimerRef.current = setTimeout(() => setSecretCopied(false), 2000);
    } else {
      setSecretError('Copy failed. Select and copy manually.');
      if (secretTimerRef.current) clearTimeout(secretTimerRef.current);
      secretTimerRef.current = setTimeout(() => setSecretError(null), 4000);
    }
  };

  const handleSaveDeviceSecret = async () => {
    const value = secretInput.trim();
    if (!value) {
      setSecretError('Secret cannot be empty');
      return;
    }
    if (value.length < 8) {
      setSecretError('Secret must be at least 8 characters');
      return;
    }
    if (/[\r\n=]/.test(value)) {
      setSecretError('Secret must not contain newlines or "=" characters');
      return;
    }
    setSecretSaving(true);
    setSecretError(null);
    setSecretSuccess(null);
    try {
      const res = await configApi.setDeviceSecret(value);
      if (res.data.success) {
        setDeviceSecret(res.data.data.deviceSecret);
        setSecretInput(res.data.data.deviceSecret);
        setSecretSuccess('Secret saved. Rebuild your APK to embed the new value.');
        if (secretTimerRef.current) clearTimeout(secretTimerRef.current);
        secretTimerRef.current = setTimeout(() => setSecretSuccess(null), 6000);
      } else {
        setSecretError(res.data.error || 'Failed to save secret');
      }
    } catch (err: any) {
      setSecretError(err?.response?.data?.error || 'Failed to save secret');
    }
    setSecretSaving(false);
  };

  const handleRegenerateDeviceSecret = async () => {
    setRegenDialogOpen(false);
    setSecretRegenerating(true);
    setSecretError(null);
    setSecretSuccess(null);
    try {
      const res = await configApi.regenerateDeviceSecret();
      if (res.data.success) {
        setDeviceSecret(res.data.data.deviceSecret);
        setSecretInput(res.data.data.deviceSecret);
        setSecretSuccess('Secret rotated. Rebuild your APK to use the new one.');
        if (secretTimerRef.current) clearTimeout(secretTimerRef.current);
        secretTimerRef.current = setTimeout(() => setSecretSuccess(null), 6000);
      } else {
        setSecretError(res.data.error || 'Failed to regenerate secret');
      }
    } catch (err: any) {
      setSecretError(err?.response?.data?.error || 'Failed to regenerate secret');
    }
    setSecretRegenerating(false);
  };

  const fetchSessions = async () => {
    setSessionsLoading(true);
    setSessionsError(null);
    try {
      const res = await authApi.sessions();
      if (res.data.success) {
        setSessions(Array.isArray(res.data.data) ? res.data.data : []);
      } else {
        setSessionsError(res.data.error || 'Failed to load sessions');
      }
    } catch (err: any) {
      setSessionsError(err?.response?.data?.error || 'Failed to load sessions');
    }
    setSessionsLoading(false);
  };

  const handleRevokeSession = async (id: string) => {
    setRevokingId(id);
    try {
      const res = await authApi.revokeSession(id);
      if (res.data.success) {
        setSessions((prev) => prev.filter((s) => s.id !== id));
      } else {
        setSessionsError(res.data.error || 'Failed to revoke session');
      }
    } catch (err: any) {
      setSessionsError(err?.response?.data?.error || 'Failed to revoke session');
    }
    setRevokingId(null);
  };

  useEffect(() => {
    fetchSessions();
  }, []);

  useEffect(() => {
    if (user) {
      setProfileUsername(user.username);
      setProfileEmail(user.email || '');
    }
  }, [user]);

  const handleProfileSave = async () => {
    setProfileSaving(true);
    setProfileError(null);
    setProfileSuccess(false);
    try {
      const updates: { username?: string; email?: string } = {};
      if (profileUsername !== user?.username) updates.username = profileUsername;
      if (profileEmail !== user?.email) updates.email = profileEmail;
      if (Object.keys(updates).length === 0) {

        setProfileError('No changes to save');
      } else {
        const res = await authApi.updateProfile(updates);
        if (res.data.success) {
          await checkAuth();
          setProfileSuccess(true);
          if (profileTimerRef.current) clearTimeout(profileTimerRef.current);
          profileTimerRef.current = setTimeout(() => setProfileSuccess(false), 3000);
        } else {
          setProfileError(res.data.error || 'Failed to update profile');
        }
      }
    } catch (err: any) {
      setProfileError(err?.response?.data?.error || 'Failed to update profile');
    }
    setProfileSaving(false);
  };

  const handlePasswordChange = async () => {
    if (newPassword !== confirmNewPassword) {
      setPasswordError('Passwords do not match');
      return;
    }
    if (newPassword.length < 8) {
      setPasswordError('Password must be at least 8 characters');
      return;
    }
    setPasswordSaving(true);
    setPasswordError(null);
    setPasswordSuccess(false);
    try {
      const res = await authApi.changePassword(currentPassword, newPassword);
      if (res.data.success) {
        setCurrentPassword('');
        setNewPassword('');
        setConfirmNewPassword('');
        setPasswordSuccess(true);
        if (passwordTimerRef.current) clearTimeout(passwordTimerRef.current);
        passwordTimerRef.current = setTimeout(() => setPasswordSuccess(false), 3000);
      } else {
        setPasswordError(res.data.error || 'Failed to change password');
      }
    } catch (err: any) {
      setPasswordError(err?.response?.data?.error || 'Failed to change password');
    }
    setPasswordSaving(false);
  };

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Settings</h1>
        <p className="text-muted-foreground mt-1">Manage your account information</p>
      </div>

      <Card className="shadow-sm">
        <CardHeader>
          <CardTitle className="text-lg flex items-center gap-2">
            <User className="h-5 w-5" /> Profile
          </CardTitle>
          <CardDescription>Update your account information</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {profileError && (
            <div className="p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
              <AlertCircle className="h-4 w-4 shrink-0" /> {profileError}
            </div>
          )}
          {profileSuccess && (
            <div className="p-3 rounded-lg bg-success/10 border border-success/20 text-success text-sm flex items-center gap-2">
              <CheckCircle className="h-4 w-4 shrink-0" /> Profile updated successfully
            </div>
          )}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="profile-username">Username</Label>
              <Input
                id="profile-username"
                type="text"
                value={profileUsername}
                onChange={(e) => setProfileUsername(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="profile-email">Email</Label>
              <Input
                id="profile-email"
                type="email"
                value={profileEmail}
                onChange={(e) => setProfileEmail(e.target.value)}
              />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant={user?.role === 'admin' ? 'default' : 'secondary'} className="gap-1">
              {user?.role === 'admin' ? <ShieldCheck className="h-3 w-3" /> : <Shield className="h-3 w-3" />}
              {user?.role === 'admin' ? 'Administrator' : 'User'}
            </Badge>
          </div>
          <Button onClick={handleProfileSave} disabled={profileSaving} className="gap-2">
            {profileSaving ? 'Saving...' : <><Save className="h-4 w-4" /> Save Profile</>}
          </Button>
        </CardContent>
      </Card>

      <Card className="shadow-sm">
        <CardHeader>
          <CardTitle className="text-lg flex items-center gap-2">
            <Lock className="h-5 w-5" /> Change Password
          </CardTitle>
          <CardDescription>Update your account password</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {passwordError && (
            <div className="p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
              <AlertCircle className="h-4 w-4 shrink-0" /> {passwordError}
            </div>
          )}
          {passwordSuccess && (
            <div className="p-3 rounded-lg bg-success/10 border border-success/20 text-success text-sm flex items-center gap-2">
              <CheckCircle className="h-4 w-4 shrink-0" /> Password changed successfully
            </div>
          )}
          <div className="space-y-2">
            <Label htmlFor="current-password">Current Password</Label>
            <Input
              id="current-password"
              type="password"
              placeholder="Enter current password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="new-password">New Password</Label>
              <Input
                id="new-password"
                type="password"
                placeholder="Enter new password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="confirm-new-password">Confirm New Password</Label>
              <Input
                id="confirm-new-password"
                type="password"
                placeholder="Confirm new password"
                value={confirmNewPassword}
                onChange={(e) => setConfirmNewPassword(e.target.value)}
              />
            </div>
          </div>
          <Button onClick={handlePasswordChange} disabled={passwordSaving || !currentPassword || !newPassword || !confirmNewPassword} className="gap-2">
            {passwordSaving ? 'Changing...' : <><Lock className="h-4 w-4" /> Change Password</>}
          </Button>
        </CardContent>
      </Card>

      {}
      <Card className="shadow-sm">
        <CardHeader>
          <CardTitle className="text-lg flex items-center gap-2">
            <KeyRound className="h-5 w-5" /> Device Authentication
          </CardTitle>
          <CardDescription>
            Your personal secret for device connections. Embedded in every APK you build.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-2">
            <span className="text-sm text-muted-foreground">Status:</span>
            {deviceSecret ? (
              <Badge className="gap-1 bg-success/15 text-success border-success/30 hover:bg-success/15">
                <ShieldCheck className="h-3 w-3" /> Active
              </Badge>
            ) : (
              <Badge variant="secondary" className="gap-1">
                <Shield className="h-3 w-3" /> Not generated
              </Badge>
            )}
          </div>

          {secretError && (
            <div className="p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
              <AlertCircle className="h-4 w-4 shrink-0" /> {secretError}
            </div>
          )}
          {secretSuccess && (
            <div className="p-3 rounded-lg bg-success/10 border border-success/20 text-success text-sm flex items-start gap-2">
              <CheckCircle className="h-4 w-4 shrink-0 mt-0.5" /> <span>{secretSuccess}</span>
            </div>
          )}

          <div className="space-y-2">
            <Label htmlFor="device-secret">Secret</Label>
            <div className="flex gap-2">
              <Input
                id="device-secret"
                readOnly={false}
                type={secretVisible ? 'text' : 'password'}
                value={secretInput}
                placeholder={deviceSecret ? '' : 'Type a secret or click Generate'}
                onChange={(e) => { setSecretInput(e.target.value); setSecretError(null); setSecretSuccess(null); }}
                className="font-mono text-sm"
                autoComplete="off"
                spellCheck={false}
              />
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={() => setSecretVisible(!secretVisible)}
                disabled={secretRegenerating || secretSaving || !secretInput}
                title={secretVisible ? 'Hide secret' : 'Show secret'}
                aria-label={secretVisible ? 'Hide secret' : 'Show secret'}
                className="shrink-0"
              >
                {secretVisible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={handleCopySecret}
                disabled={secretRegenerating || secretSaving || !secretInput}
                title={secretCopied ? 'Copied!' : 'Copy to clipboard'}
                aria-label="Copy to clipboard"
                className="shrink-0"
              >
                {secretCopied ? <CheckCircle className="h-4 w-4 text-success" /> : <Copy className="h-4 w-4" />}
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              {deviceSecret
                ? 'Edit the field and Save, or click Regenerate for a random one. Min 8 chars.'
                : 'Type a secret (min 8 chars) and Save, or click Generate for a random one.'}
            </p>
          </div>

          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              onClick={handleSaveDeviceSecret}
              disabled={secretSaving || secretRegenerating || !secretDirty}
              className="gap-2"
            >
              {secretSaving ? <><RefreshCw className="h-4 w-4 animate-spin" /> Saving…</> : <><Save className="h-4 w-4" /> Save Secret</>}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={() => setRegenDialogOpen(true)}
              disabled={secretRegenerating || secretSaving}
              className="gap-2"
            >
              {secretRegenerating ? <RefreshCw className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              {deviceSecret ? 'Regenerate' : 'Generate'}
            </Button>
            {secretDirty && (
              <Button
                type="button"
                variant="ghost"
                onClick={() => { setSecretInput(deviceSecret || ''); setSecretError(null); setSecretSuccess(null); }}
                disabled={secretSaving || secretRegenerating}
                className="gap-2"
              >
                Reset
              </Button>
            )}
          </div>
        </CardContent>
      </Card>

      <Dialog open={regenDialogOpen} onOpenChange={setRegenDialogOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-amber-500" />
              {deviceSecret ? 'Regenerate Device Secret?' : 'Generate Device Secret?'}
            </DialogTitle>
            <DialogDescription>
              {deviceSecret
                ? 'Replaces current secret. Existing APKs stop working and must be rebuilt.'
                : 'A new random secret will be created and embedded into APKs you build. Devices already connected will keep working until they reconnect.'}
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col-reverse sm:flex-row sm:justify-end gap-2 pt-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => setRegenDialogOpen(false)}
              disabled={secretRegenerating}
            >
              Cancel
            </Button>
            <Button
              type="button"
              variant="destructive"
              onClick={handleRegenerateDeviceSecret}
              disabled={secretRegenerating}
              className="gap-2"
            >
              {secretRegenerating ? (
                <><RefreshCw className="h-4 w-4 animate-spin" /> Regenerating…</>
              ) : (
                <><RefreshCw className="h-4 w-4" /> {deviceSecret ? 'Regenerate' : 'Generate'}</>
              )}
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      {canViewSettings && (
      <Card className="shadow-sm">
        <CardHeader>
          <CardTitle className="text-lg flex items-center gap-2">
            <Wrench className="h-5 w-5" /> Builder Settings
          </CardTitle>
          <CardDescription>Control what's visible on the APK Builder page</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center justify-between gap-3 py-1">
            <div className="min-w-0">
              <p className="text-sm font-medium">Show Server URL field</p>
              <p className="text-xs text-muted-foreground mt-0.5">When off, the Server URL is auto-detected and users can't change it on the Builder page.</p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={showServerUrl}
              disabled={!canEditSettings || builderSettingsLoading}
              onClick={() => handleToggleShowServerUrl(!showServerUrl)}
              className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed ${
                showServerUrl ? 'bg-primary' : 'bg-muted'
              }`}
            >
              <span
                className={`pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow transition duration-200 ${
                  showServerUrl ? 'translate-x-4' : 'translate-x-0'
                }`}
              />
            </button>
          </div>
          {!canEditSettings && (
            <p className="text-xs text-muted-foreground">You need the <code>settings:edit</code> permission to change this setting.</p>
          )}
          {builderSettingsError && (
            <p className="text-xs text-destructive">{builderSettingsError}</p>
          )}
        </CardContent>
      </Card>
      )}

      <Card className="shadow-sm">
        <CardHeader>
          <CardTitle className="text-lg flex items-center gap-2">
            <Monitor className="h-5 w-5" /> Active Sessions
          </CardTitle>
          <CardDescription>Devices currently logged into your account{user?.role === 'admin' ? ' (admin sees all users)' : ''}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {sessionsError && (
            <div className="p-3 rounded-lg bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center gap-2">
              <AlertCircle className="h-4 w-4 shrink-0" /> {sessionsError}
            </div>
          )}

          {sessionsLoading ? (
            <p className="text-sm text-muted-foreground">Loading sessions…</p>
          ) : sessions.length === 0 ? (
            <p className="text-sm text-muted-foreground">No active sessions.</p>
          ) : (
            <div className="space-y-2">
              {sessions.map((s) => (
                <div key={s.id} className="flex items-center gap-3 p-3 rounded-lg border bg-card/50">
                  <div className="h-9 w-9 rounded-md bg-muted flex items-center justify-center shrink-0">
                    <Monitor className="h-4 w-4 text-muted-foreground" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-medium truncate">{s.username || `User #${s.userId}`}</span>
                      {s.isCurrent && (
                        <Badge variant="secondary" className="text-xs">This device</Badge>
                      )}
                    </div>
                    <div className="text-xs text-muted-foreground mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5">
                      <span>IP: <span className="font-mono">{s.ip || 'unknown'}</span></span>
                    </div>
                    <div className="text-xs text-muted-foreground mt-0.5">
                      <span>Started: {new Date(s.createdAt).toLocaleString()}</span>
                      <span className="mx-1">·</span>
                      <span>Expires: {new Date(s.expiresAt).toLocaleString()}</span>
                    </div>
                  </div>
                  {!s.isCurrent && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => handleRevokeSession(s.id)}
                      disabled={revokingId === s.id}
                      className="gap-1 shrink-0 text-destructive hover:text-destructive hover:bg-destructive/10"
                    >
                      <Trash2 className="h-3.5 w-3.5" /> {revokingId === s.id ? 'Revoking…' : 'Revoke'}
                    </Button>
                  )}
                </div>
              ))}
            </div>
          )}
          <Button variant="ghost" size="sm" onClick={fetchSessions} disabled={sessionsLoading} className="gap-2 text-xs">
            <RefreshCw className={`h-3 w-3 ${sessionsLoading ? 'animate-spin' : ''}`} /> Refresh
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
