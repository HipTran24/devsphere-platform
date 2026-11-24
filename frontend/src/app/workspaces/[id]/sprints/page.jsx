import SprintView from '../../../../features/sprints/components/SprintView';

export const metadata = {
  title: 'Sprint Board | DevSphere',
};

export default async function WorkspaceSprintsPage({ params }) {
  const resolvedParams = await params;
  const workspaceId = resolvedParams?.id || 'devsphere-core';

  return <SprintView workspaceId={workspaceId} />;
}
