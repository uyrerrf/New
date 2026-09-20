import { useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext, ClientFile } from '@/types';
import { DevicePageHeader, EmptyState, ErrorAlert, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildFileActions } from '@/components/device/DataActionsMenu';
import { Card } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { Download, File } from 'lucide-react';
import { formatBytes, formatDate } from '@/lib/utils';
import { downloadAuthFile } from '@/components/AuthMedia';

export default function DownloadsPage() {
  const { clientId } = useOutletContext<DeviceOutletContext>();

  const { data: downloads, loading, error, refresh, commandStatus, clearData } = useDeviceData<ClientFile[]>({
    clientId,
    page: 'downloads',
    extractData: (d) => Array.isArray(d.list) ? d.list : [],
    dataType: 'files',
    defaultValue: [],
  });

  const [exporting, setExporting] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const fileActions = buildFileActions({
    files: downloads.map((f) => ({ url: `/api/files/${f.fileType === 'upload' ? 'uploads' : 'downloads'}/${clientId}/${f.id}`, name: f.originalName })),
    metadata: downloads,
    exportPrefix: 'downloads',
    onClear: clearData,
    onExportStart: () => setExporting(true),
    onExportEnd: () => setExporting(false),
  });

  return (
    <div className="space-y-5">
      <DevicePageHeader
        title="Downloads"
        subtitle={`${downloads.length} files`}
        moreActions={<DataActionsMenu actions={fileActions} disabled={loading} loadingLabel={exporting ? 'Export ZIP' : null} />}
        refresh={refresh}
        loading={loading}
        commandStatus={commandStatus}
      />

      {(error || downloadError) && <ErrorAlert message={downloadError || error!} onRetry={() => { setDownloadError(null); refresh(); }} />}

      {loading && !error ? (
        <LoadingSkeleton rows={5} />
      ) : downloads.length === 0 ? (
        <EmptyState
          icon={Download}
          title="No downloaded files"
          description="Files downloaded from the device will appear here"
        />
      ) : (
        <Card className="shadow-none overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="text-xs">Name</TableHead>
                <TableHead className="text-xs">Size</TableHead>
                <TableHead className="text-xs hidden sm:table-cell">Date</TableHead>
                <TableHead className="w-[60px] text-xs">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {downloads.map((file) => (
                <TableRow key={`dl-${file.id}`}>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <File className="h-4 w-4 text-muted-foreground shrink-0" />
                      <span className="text-xs truncate max-w-[200px]">{file.originalName}</span>
                    </div>
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">{formatBytes(file.fileSize)}</TableCell>
                  <TableCell className="text-xs text-muted-foreground hidden sm:table-cell whitespace-nowrap">{file.createdAt ? formatDate(file.createdAt) : '-'}</TableCell>
                  <TableCell>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7"
                      aria-label="Download file"
                      onClick={async () => {
                        setDownloadError(null);
                        const ok = await downloadAuthFile(`/api/files/${file.fileType === 'upload' ? 'uploads' : 'downloads'}/${clientId}/${file.id}`, file.originalName);

                        if (!ok) setDownloadError('Failed to download file.');
                      }}
                    >
                      <Download className="h-3.5 w-3.5" />
                    </Button>
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
