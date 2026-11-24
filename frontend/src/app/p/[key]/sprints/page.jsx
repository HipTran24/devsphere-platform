import SprintView from '../../../../features/sprints/components/SprintView';

export const metadata = {
  title: 'Sprint Board | DevSphere',
};

export default async function ProjectSprintsPage({ params }) {
  const resolvedParams = await params;
  const projectKey = resolvedParams?.key || 'DSP';

  return <SprintView projectKey={projectKey} />;
}
