import BacklogView from '../../../../features/backlog/components/BacklogView';

export const metadata = {
  title: 'Workspace Backlog | DevSphere',
};

export default async function WorkspaceBacklogPage({ params }) {
  const resolvedParams = await params;
  const workspaceId = resolvedParams?.id || 'devsphere-core';

  return <BacklogView workspaceId={workspaceId} />;
}
