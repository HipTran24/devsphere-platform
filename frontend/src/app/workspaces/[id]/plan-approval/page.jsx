import PlanApprovalView from '../../../../features/planning/components/PlanApprovalView';

export const metadata = {
  title: 'Plan Approval | DevSphere',
};

export default async function WorkspacePlanApprovalPage({ params }) {
  const resolvedParams = await params;
  const workspaceId = resolvedParams?.id || 'devsphere-core';

  return <PlanApprovalView workspaceId={workspaceId} />;
}
