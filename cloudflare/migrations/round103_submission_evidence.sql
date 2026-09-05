-- Round103: production bill/store photo evidence for Admin review
-- Run this new migration once. Do not rerun older migrations.

CREATE TABLE IF NOT EXISTS field_submission_evidence (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  submission_id INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('R','S')),
  slot INTEGER NOT NULL,
  object_key TEXT NOT NULL UNIQUE,
  content_type TEXT,
  size_bytes INTEGER NOT NULL DEFAULT 0,
  source TEXT,
  captured_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (submission_id, kind, slot)
);

CREATE INDEX IF NOT EXISTS idx_field_submission_evidence_submission
  ON field_submission_evidence(submission_id, kind, slot);
