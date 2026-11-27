import type { ComponentProps } from 'react';
import { AgentStatus, type AgentState } from './agent-status';
import { paper } from './surfaces';

export interface SubagentActivityItem {
  id: string;
  name: string;
  state: AgentState;
  label: string;
  model?: string;
  elapsed?: string;
  description?: string;
}

// YCA adapter over the actual upstream status Element. No estimated progress or
// completed-prefix assumption: concurrent agents can finish in any order.
export function SubagentActivity({ agents, className = '', ...props }: Omit<ComponentProps<'div'>, 'children'> & { agents: readonly SubagentActivityItem[] }) {
  return (
    <div data-slot="subagent-activity" className={`flex flex-col gap-2 ${className}`} {...props}>
      {agents.map(agent => (
        <section key={agent.id} className={`${paper} rounded-xl p-3`} aria-label={agent.name}>
          <div className="mb-2 flex items-baseline justify-between gap-2">
            <span className="text-sm font-medium">{agent.name}</span>
            {agent.model && <span className="text-xs text-[var(--yca-muted)]">{agent.model}</span>}
          </div>
          <AgentStatus state={agent.state} label={agent.label} elapsed={agent.elapsed} />
          {agent.description && <p className="mt-2 whitespace-pre-wrap text-xs text-[var(--yca-muted)]">{agent.description}</p>}
        </section>
      ))}
    </div>
  );
}
