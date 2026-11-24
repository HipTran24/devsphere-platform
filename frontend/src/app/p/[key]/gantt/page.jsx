import GanttView from '../../../../features/planning/components/GanttView';

export const metadata = {
  title: 'Gantt Chart | DevSphere',
};

export default async function ProjectGanttPage({ params }) {
  const resolvedParams = await params;
  const projectKey = resolvedParams?.key || 'DSP';

  return <GanttView projectKey={projectKey} />;
}
