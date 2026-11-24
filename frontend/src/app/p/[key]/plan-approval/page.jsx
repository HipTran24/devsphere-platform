import PlanApprovalView from '../../../../features/planning/components/PlanApprovalView';

export const metadata = {
  title: 'Plan Approval | DevSphere',
};

export default async function ProjectPlanApprovalPage({ params }) {
  const resolvedParams = await params;
  const projectKey = resolvedParams?.key || 'DSP';

  return <PlanApprovalView projectKey={projectKey} />;
}
