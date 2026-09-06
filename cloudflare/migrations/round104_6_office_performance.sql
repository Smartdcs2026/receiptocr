-- Round104.6: speed up office lists, review queue, reports and evidence lookups.
CREATE INDEX IF NOT EXISTS idx_field_submissions_status_updated
ON field_submissions(status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_field_submissions_work_date_status
ON field_submissions(work_date, status);

CREATE INDEX IF NOT EXISTS idx_field_submissions_employee_store_date
ON field_submissions(employee_code, store_code, work_date);

CREATE INDEX IF NOT EXISTS idx_field_submissions_work_plan_employee_updated
ON field_submissions(work_plan_item_id, employee_code, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_field_submission_pos_submission_no_receipt
ON field_submission_pos(submission_id, no_receipt, pos_number);

CREATE INDEX IF NOT EXISTS idx_r2_objects_category_deleted_updated
ON r2_objects(category, deleted, updated_at DESC);
