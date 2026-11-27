import { useEffect, useId, useState } from 'react';

export interface SessionMetadataProps {
  sessionId: string;
  project?: string;
  gitBranch?: string;
  model?: string;
  flavor?: string;
  provider?: string;
  permissionMode?: string;
  sandboxEnabled?: boolean;
  modelCandidates?: string[];
  providerCandidates?: string[];
  onSetProject: (dir: string) => void;
  onSetModel: (model: string) => void;
  onSetProvider: (provider: string) => void;
  onSetPermissions: (mode: string) => void;
}

export function SessionMetadata({
  sessionId, project, gitBranch, model, flavor, provider, permissionMode, sandboxEnabled,
  modelCandidates, providerCandidates, onSetProject, onSetModel, onSetProvider, onSetPermissions,
}: SessionMetadataProps) {
  const id = useId();
  const unattended = permissionMode === 'yolo' || permissionMode === 'full_yolo';
  const sandboxBypassed = permissionMode === 'full_yolo';
  const sandboxActive = sandboxEnabled === true && !sandboxBypassed;
  const sandboxStatus = sandboxBypassed ? 'Bypassed (full_yolo)' : sandboxEnabled === true ? 'Enabled' : sandboxEnabled === false ? 'Disabled' : 'Unknown';
  const permissionColor = permissionMode === 'full_yolo' || (permissionMode === 'yolo' && sandboxEnabled !== true)
    ? 'var(--yca-yolo)'
    : permissionMode === 'yolo'
    ? 'var(--yca-warning)'
    : permissionMode === 'auto_edit'
    ? 'var(--yca-success)'
    : permissionMode === 'ask'
    ? 'var(--yca-plan)'
    : 'var(--yca-muted)';
  const [dirDraft, setDirDraft] = useState(project ?? '');
  useEffect(() => {
    setDirDraft(project ?? '');
  }, [project]);

  return (
    <dl className="yca-session-metadata">
      <div className="yca-metadata-row">
        <dt>Session</dt>
        <dd className="yca-metadata-value" title={sessionId}>{sessionId}</dd>
      </div>
      <div className="yca-metadata-row">
        <dt><label htmlFor={`${id}-project`}>Working dir</label></dt>
        <dd className="yca-metadata-edit">
          <input
            id={`${id}-project`}
            className="yca-metadata-control"
            value={dirDraft}
            title={dirDraft}
            onChange={(e) => setDirDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                onSetProject(dirDraft);
              }
            }}
          />
          <button type="button" onClick={() => onSetProject(dirDraft)}>Set</button>
        </dd>
      </div>
      {gitBranch && (
        <div className="yca-metadata-row">
          <dt>Branch</dt>
          <dd className="yca-metadata-value" title={gitBranch}>{gitBranch}</dd>
        </div>
      )}
      <div className="yca-metadata-row">
        <dt><label htmlFor={`${id}-provider`}>Provider</label></dt>
        <dd>
          <select
            id={`${id}-provider`}
            className="yca-metadata-control"
            value={provider || ''}
            title={provider}
            onChange={(e) => {
              if (e.target.value) {
                onSetProvider(e.target.value);
              }
            }}
          >
            {provider && <option value={provider}>{provider}</option>}
            {(providerCandidates ?? []).filter((p) => p !== provider).map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </select>
        </dd>
      </div>
      <div className="yca-metadata-row">
        <dt><label htmlFor={`${id}-model`}>Model</label></dt>
        <dd>
          <select
            id={`${id}-model`}
            className="yca-metadata-control"
            value={model || ''}
            title={model}
            onChange={(e) => {
              if (e.target.value) {
                onSetModel(e.target.value);
              }
            }}
          >
            {model && <option value={model}>{model}</option>}
            {(modelCandidates ?? []).filter((m) => m !== model).map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </dd>
      </div>
      {flavor && (
        <div className="yca-metadata-row">
          <dt>Flavor</dt>
          <dd className="yca-metadata-value" title={flavor}>{flavor}</dd>
        </div>
      )}
      <div className="yca-metadata-row">
        <dt><label htmlFor={`${id}-permissions`}>Permissions</label></dt>
        <dd className="yca-metadata-edit">
          <select
            id={`${id}-permissions`}
            className="yca-metadata-control"
            value={permissionMode || 'ask'}
            title="Tool permission level for this session"
            onChange={(e) => {
              if (e.target.value) {
                onSetPermissions(e.target.value);
              }
            }}
          >
            <option value="ask">ask</option>
            <option value="auto_edit">auto_edit</option>
            <option value="yolo">yolo</option>
            <option value="full_yolo">full_yolo (not recommended)</option>
          </select>
          <span
            aria-hidden="true"
            className={unattended ? 'shrink-0 font-bold' : 'inline-block h-2 w-2 shrink-0 rounded-full'}
            title={permissionMode === 'full_yolo' ? 'Sandbox bypassed - not recommended' : undefined}
            style={{
              ...(unattended ? { color: permissionColor } : { background: permissionColor }),
            }}
          >{unattended ? '!' : null}</span>
        </dd>
      </div>
      <div className="yca-metadata-row">
        <dt>Sandbox</dt>
        <dd
          className="yca-metadata-value"
          title="Effective session sandbox state; trusted integrations run outside this boundary"
          style={{ color: sandboxActive ? 'var(--yca-success)' : sandboxEnabled === undefined && !sandboxBypassed ? 'var(--yca-muted)' : 'var(--yca-yolo)' }}
        >{sandboxStatus}</dd>
      </div>
    </dl>
  );
}
