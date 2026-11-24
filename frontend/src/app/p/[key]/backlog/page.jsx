import BacklogView from '../../../../features/backlog/components/BacklogView';

export const metadata = {
  title: 'Project Backlog | DevSphere',
};

export default async function ProjectBacklogPage({ params }) {
  const resolvedParams = await params;
  const projectKey = resolvedParams?.key || 'DSP';

  return <BacklogView projectKey={projectKey} />;
}
