import GanttView from '../../../../features/planning/components/GanttView';

export const metadata = {
  title: 'Gantt Chart | DevSphere',
};

export default async function WorkspaceGanttPage({ params }) {
  const resolvedParams = await params;
  const workspaceId = resolvedParams?.id || 'devsphere-core';

  return <GanttView workspaceId={workspaceId} />;
}
