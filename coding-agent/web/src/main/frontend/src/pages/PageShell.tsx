// Shared wrapper for the secondary content pages (Memory, Tools, Code graph, MCP, ...).
// Centralising the layout keeps the pages' width / padding / scroll behaviour consistent instead of
// each page hardcoding its own max-w-* wrapper and slowly drifting apart. text-[15px] lifts the base
// above the default 'sm' (14px) so these pages read at a comfortable 16px-adjacent size per the
// minisite baseline without bumping every inner node.
//
// The page header is rendered centrally with the yupiik brand treatment (.yca-page-header): a big
// tracked-tight title + the accent underline, so every sub-page shares the same product look instead
// of each page hand-rolling its own <h1>.
export function PageShell({
  title,
  subtitle,
  children,
  className = '',
  fullWidth = false,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  className?: string;
  fullWidth?: boolean;
}) {
  return (
    <div className={`mx-auto h-full ${fullWidth ? 'w-full max-w-none' : 'max-w-5xl'} overflow-y-auto p-6 text-[15px] ${className}`}>
      <div className="yca-page-header">
        <h1>{title}</h1>
        {subtitle && (
          <p className="mt-1 max-w-2xl text-sm leading-relaxed" style={{ color: 'var(--yca-muted)' }}>
            {subtitle}
          </p>
        )}
      </div>
      {children}
    </div>
  );
}
