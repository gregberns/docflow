CREATE INDEX IF NOT EXISTS idx_workflow_instances_org_updated_id
  ON workflow_instances (organization_id, updated_at DESC, id DESC);
