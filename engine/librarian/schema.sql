-- Project Intermix Continuity Lattice v0.3 ordinary-service schema.
-- Existing MemoryStore/Android Matrix tables are intentionally left untouched.

CREATE TABLE IF NOT EXISTS lattice_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS node_registry (
    node_id TEXT PRIMARY KEY,
    node_role TEXT NOT NULL CHECK(node_role IN ('cortex','librarian','archive','client','builder')),
    display_name TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT '',
    capabilities_json TEXT NOT NULL DEFAULT '{}',
    public_key TEXT,
    first_seen_ms INTEGER NOT NULL,
    last_seen_ms INTEGER NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1))
);

CREATE TABLE IF NOT EXISTS node_heartbeat (
    node_id TEXT PRIMARY KEY REFERENCES node_registry(node_id) ON DELETE CASCADE,
    observed_at_ms INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('ok','degraded','offline','maintenance')),
    battery_pct REAL,
    temperature_c REAL,
    free_ram_mib INTEGER,
    free_storage_mib INTEGER,
    current_job TEXT,
    details_json TEXT NOT NULL DEFAULT '{}',
    last_request_id TEXT,
    last_payload_sha256 TEXT,
    schema_version INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS node_sequence (
    node_id TEXT PRIMARY KEY REFERENCES node_registry(node_id) ON DELETE CASCADE,
    next_event_seq INTEGER NOT NULL DEFAULT 1 CHECK(next_event_seq >= 1),
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS request_receipt (
    node_id TEXT NOT NULL REFERENCES node_registry(node_id),
    operation TEXT NOT NULL,
    request_id TEXT NOT NULL,
    payload_sha256 TEXT NOT NULL,
    committed_at_ms INTEGER NOT NULL,
    PRIMARY KEY(node_id, operation, request_id)
);

CREATE INDEX IF NOT EXISTS idx_request_receipt_time
ON request_receipt(committed_at_ms DESC);

CREATE TABLE IF NOT EXISTS event_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    global_id TEXT NOT NULL UNIQUE,
    origin_node_id TEXT NOT NULL REFERENCES node_registry(node_id),
    origin_seq INTEGER NOT NULL CHECK(origin_seq >= 1),
    ts_ms INTEGER,
    source_time_ms INTEGER NOT NULL,
    receive_time_ms INTEGER NOT NULL,
    session_id TEXT NOT NULL DEFAULT '',
    actor TEXT NOT NULL CHECK(actor IN ('user','assistant','system','tool','runtime')),
    kind TEXT NOT NULL,
    content TEXT NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}',
    payload_sha256 TEXT NOT NULL,
    parent_global_ids_json TEXT NOT NULL DEFAULT '[]',
    schema_version INTEGER NOT NULL DEFAULT 1,
    UNIQUE(origin_node_id, origin_seq)
);

CREATE INDEX IF NOT EXISTS idx_event_receive_time ON event_log(receive_time_ms DESC);
CREATE INDEX IF NOT EXISTS idx_event_actor_kind ON event_log(actor, kind, receive_time_ms DESC);
CREATE INDEX IF NOT EXISTS idx_event_origin_seq ON event_log(origin_node_id, origin_seq);
CREATE UNIQUE INDEX IF NOT EXISTS idx_event_global_id
ON event_log(global_id) WHERE global_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_event_origin_sequence_unique
ON event_log(origin_node_id, origin_seq)
WHERE origin_node_id IS NOT NULL AND origin_seq IS NOT NULL;

CREATE TRIGGER IF NOT EXISTS event_log_immutable_update
BEFORE UPDATE ON event_log BEGIN
    SELECT RAISE(ABORT, 'event_log rows are immutable');
END;

CREATE TRIGGER IF NOT EXISTS event_log_immutable_delete
BEFORE DELETE ON event_log BEGIN
    SELECT RAISE(ABORT, 'event_log rows are immutable');
END;

CREATE TABLE IF NOT EXISTS memory_atom (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    global_id TEXT NOT NULL UNIQUE,
    origin_node_id TEXT NOT NULL REFERENCES node_registry(node_id),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    class TEXT NOT NULL CHECK(class IN (
        'episode','fact','procedure','constraint','preference',
        'self_model','user_model','project_state','hypothesis','lesson'
    )),
    domain TEXT,
    subject TEXT,
    predicate TEXT,
    object_text TEXT,
    canonical_text TEXT NOT NULL,
    epistemic TEXT NOT NULL CHECK(epistemic IN (
        'user_stated','observed','tool_verified','inferred','model_synthesized','imported'
    )),
    status TEXT NOT NULL DEFAULT 'active' CHECK(status IN (
        'active','disputed','superseded','retracted','expired'
    )),
    confidence REAL NOT NULL DEFAULT 0.50 CHECK(confidence BETWEEN 0.0 AND 1.0),
    salience REAL NOT NULL DEFAULT 0.50 CHECK(salience BETWEEN 0.0 AND 1.0),
    stability REAL NOT NULL DEFAULT 0.50 CHECK(stability BETWEEN 0.0 AND 1.0),
    novelty REAL NOT NULL DEFAULT 0.50 CHECK(novelty BETWEEN 0.0 AND 1.0),
    utility REAL NOT NULL DEFAULT 0.50 CHECK(utility BETWEEN 0.0 AND 1.0),
    access_count INTEGER NOT NULL DEFAULT 0,
    last_accessed_at_ms INTEGER,
    valid_from_ms INTEGER,
    valid_until_ms INTEGER,
    expires_at_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_memory_active_class ON memory_atom(status, class, updated_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_memory_subject ON memory_atom(subject, predicate, status);
CREATE INDEX IF NOT EXISTS idx_memory_salience
ON memory_atom(status, salience DESC, stability DESC, utility DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_atom_global_id
ON memory_atom(global_id) WHERE global_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS atom_evidence (
    atom_id INTEGER NOT NULL REFERENCES memory_atom(id) ON DELETE CASCADE,
    event_id INTEGER NOT NULL REFERENCES event_log(id) ON DELETE RESTRICT,
    relation TEXT NOT NULL CHECK(relation IN ('source','supports','challenges','verifies','corrects')),
    weight REAL NOT NULL DEFAULT 1.0 CHECK(weight BETWEEN 0.0 AND 1.0),
    PRIMARY KEY(atom_id, event_id, relation)
);

CREATE INDEX IF NOT EXISTS idx_evidence_event ON atom_evidence(event_id, atom_id);

CREATE TABLE IF NOT EXISTS memory_edge (
    from_atom_id INTEGER NOT NULL REFERENCES memory_atom(id) ON DELETE CASCADE,
    to_atom_id INTEGER NOT NULL REFERENCES memory_atom(id) ON DELETE CASCADE,
    relation TEXT NOT NULL CHECK(relation IN (
        'supports','contradicts','supersedes','derived_from','caused_by',
        'part_of','about','depends_on','goal_supports','same_as','related_to'
    )),
    weight REAL NOT NULL DEFAULT 0.50 CHECK(weight BETWEEN 0.0 AND 1.0),
    created_at_ms INTEGER NOT NULL,
    source_event_id INTEGER REFERENCES event_log(id),
    PRIMARY KEY(from_atom_id, to_atom_id, relation)
);

CREATE INDEX IF NOT EXISTS idx_edge_to ON memory_edge(to_atom_id, relation, weight DESC);

CREATE TABLE IF NOT EXISTS friction_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    global_id TEXT UNIQUE,
    created_at_ms INTEGER NOT NULL,
    type TEXT NOT NULL CHECK(type IN (
        'contradiction','uncertainty','runtime_failure','prediction_error',
        'goal_conflict','missing_evidence','behavior_regression'
    )),
    logical_key TEXT,
    atom_a_id INTEGER REFERENCES memory_atom(id),
    atom_b_id INTEGER REFERENCES memory_atom(id),
    source_event_id INTEGER REFERENCES event_log(id),
    description TEXT NOT NULL,
    severity REAL NOT NULL DEFAULT 0.50 CHECK(severity BETWEEN 0.0 AND 1.0),
    status TEXT NOT NULL DEFAULT 'open' CHECK(status IN (
        'open','investigating','resolved','accepted_unknown'
    )),
    resolution_atom_id INTEGER REFERENCES memory_atom(id),
    resolved_at_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_friction_open
ON friction_event(status, severity DESC, created_at_ms DESC);

CREATE TABLE IF NOT EXISTS goal (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    global_id TEXT UNIQUE,
    parent_goal_id INTEGER REFERENCES goal(id),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL DEFAULT 'active' CHECK(status IN (
        'active','blocked','paused','completed','abandoned'
    )),
    priority REAL NOT NULL DEFAULT 0.50 CHECK(priority BETWEEN 0.0 AND 1.0),
    progress REAL NOT NULL DEFAULT 0.0 CHECK(progress BETWEEN 0.0 AND 1.0),
    source_event_id INTEGER REFERENCES event_log(id),
    source_atom_id INTEGER REFERENCES memory_atom(id),
    deadline_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_goal_active ON goal(status, priority DESC, updated_at_ms DESC);

CREATE TABLE IF NOT EXISTS state_register (
    scope TEXT NOT NULL,
    key TEXT NOT NULL,
    value_json TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 1.0 CHECK(confidence BETWEEN 0.0 AND 1.0),
    source_atom_id INTEGER NOT NULL REFERENCES memory_atom(id),
    source_event_id INTEGER REFERENCES event_log(id),
    updated_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER,
    PRIMARY KEY(scope, key)
);

CREATE INDEX IF NOT EXISTS idx_state_scope ON state_register(scope, updated_at_ms DESC);

CREATE TABLE IF NOT EXISTS context_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at_ms INTEGER NOT NULL,
    session_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    summary TEXT NOT NULL,
    state_json TEXT,
    memory_ids_json TEXT,
    goal_ids_json TEXT,
    friction_ids_json TEXT,
    approx_tokens INTEGER NOT NULL DEFAULT 0,
    parent_snapshot_id INTEGER REFERENCES context_snapshot(id)
);

CREATE INDEX IF NOT EXISTS idx_context_snapshot_session
ON context_snapshot(session_id, created_at_ms DESC);

CREATE TABLE IF NOT EXISTS memory_embedding (
    atom_id INTEGER PRIMARY KEY REFERENCES memory_atom(id) ON DELETE CASCADE,
    model TEXT NOT NULL,
    dims INTEGER NOT NULL,
    dtype TEXT NOT NULL DEFAULT 'f16',
    vector_blob BLOB NOT NULL,
    vector_norm REAL,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS consolidation_queue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at_ms INTEGER NOT NULL,
    not_before_ms INTEGER,
    job_type TEXT NOT NULL CHECK(job_type IN (
        'deduplicate','summarize_episode','resolve_friction',
        'refresh_embedding','decay_scores','build_checkpoint'
    )),
    target_type TEXT NOT NULL CHECK(target_type IN ('atom','session','friction','global')),
    target_id INTEGER,
    priority REAL NOT NULL DEFAULT 0.50 CHECK(priority BETWEEN 0.0 AND 1.0),
    attempts INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','running','done','failed')),
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_consolidation_pending
ON consolidation_queue(status, priority DESC, created_at_ms);

CREATE TABLE IF NOT EXISTS replication_cursor (
    peer_node_id TEXT NOT NULL,
    stream TEXT NOT NULL,
    origin_node_id TEXT NOT NULL,
    max_origin_seq INTEGER NOT NULL DEFAULT 0,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY(peer_node_id, stream, origin_node_id)
);

CREATE TABLE IF NOT EXISTS replication_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at_ms INTEGER NOT NULL,
    destination_node_id TEXT NOT NULL,
    object_type TEXT NOT NULL CHECK(object_type IN (
        'event','atom','edge','friction','goal','state','snapshot','blob_manifest'
    )),
    object_global_id TEXT NOT NULL,
    payload_sha256 TEXT,
    payload_json TEXT NOT NULL DEFAULT '{}',
    priority REAL NOT NULL DEFAULT 0.50 CHECK(priority BETWEEN 0.0 AND 1.0),
    state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN (
        'pending','sending','acked','failed','dead_letter'
    )),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_ms INTEGER,
    last_error TEXT,
    UNIQUE(destination_node_id, object_type, object_global_id)
);

CREATE INDEX IF NOT EXISTS idx_replication_pending
ON replication_outbox(state, priority DESC, created_at_ms);

CREATE TABLE IF NOT EXISTS content_object (
    sha256 TEXT PRIMARY KEY,
    created_at_ms INTEGER NOT NULL,
    source_node_id TEXT NOT NULL REFERENCES node_registry(node_id),
    object_class TEXT NOT NULL CHECK(object_class IN (
        'attachment','checkpoint','db_snapshot','event_pack','release_artifact',
        'diagnostic','model_asset','workspace_file','other'
    )),
    byte_size INTEGER NOT NULL,
    mime_type TEXT,
    local_path TEXT,
    nas_relative_path TEXT,
    nas_state TEXT NOT NULL DEFAULT 'not_present' CHECK(nas_state IN (
        'not_present','queued','present','verify_failed'
    )),
    metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_object_nas_state ON content_object(nas_state, created_at_ms);

CREATE TABLE IF NOT EXISTS librarian_job (
    job_id TEXT PRIMARY KEY,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN (
        'embed_atom','fts_maintenance','deduplicate_candidates','cluster_episode',
        'detect_contradiction','hash_object','snapshot_db','nas_push','nas_verify',
        'integrity_check','release_check','release_verify','retention_gc',
        'context_compile','llm_consolidate','llm_resolve_friction'
    )),
    payload_json TEXT NOT NULL,
    preferred_role TEXT NOT NULL DEFAULT 'librarian' CHECK(preferred_role IN (
        'librarian','cortex','archive','any'
    )),
    min_free_ram_mib INTEGER NOT NULL DEFAULT 0,
    max_temp_c REAL,
    requires_charging INTEGER NOT NULL DEFAULT 0 CHECK(requires_charging IN (0,1)),
    priority REAL NOT NULL DEFAULT 0.50 CHECK(priority BETWEEN 0.0 AND 1.0),
    not_before_ms INTEGER,
    state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN (
        'pending','leased','running','done','failed','cancelled'
    )),
    lease_owner_node_id TEXT REFERENCES node_registry(node_id),
    lease_until_ms INTEGER,
    attempts INTEGER NOT NULL DEFAULT 0,
    result_json TEXT,
    last_error TEXT,
    source_event_id INTEGER REFERENCES event_log(id)
);

CREATE INDEX IF NOT EXISTS idx_librarian_job_claim
ON librarian_job(state, preferred_role, priority DESC, not_before_ms, created_at_ms);

CREATE TABLE IF NOT EXISTS replication_conflict (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at_ms INTEGER NOT NULL,
    object_type TEXT NOT NULL,
    logical_key TEXT NOT NULL,
    local_global_id TEXT,
    remote_global_id TEXT,
    description TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open' CHECK(status IN (
        'open','resolved_local','resolved_remote','merged','accepted_divergence'
    )),
    resolution_event_id INTEGER REFERENCES event_log(id)
);

CREATE INDEX IF NOT EXISTS idx_replication_conflict_open
ON replication_conflict(status, created_at_ms DESC);

CREATE TABLE IF NOT EXISTS snapshot_catalog (
    snapshot_id TEXT PRIMARY KEY,
    request_id TEXT,
    generation INTEGER NOT NULL UNIQUE,
    node_id TEXT NOT NULL REFERENCES node_registry(node_id),
    created_at_ms INTEGER NOT NULL,
    lattice_schema_version TEXT NOT NULL,
    sqlite_user_version INTEGER,
    event_high_water_json TEXT NOT NULL,
    parent_snapshot_hash TEXT,
    db_sha256 TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    byte_size INTEGER NOT NULL,
    local_path TEXT,
    manifest_path TEXT,
    nas_relative_path TEXT,
    nas_verified INTEGER NOT NULL DEFAULT 0 CHECK(nas_verified IN (0,1)),
    reason TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_snapshot_recent ON snapshot_catalog(node_id, created_at_ms DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_snapshot_generation
ON snapshot_catalog(generation) WHERE generation IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_snapshot_request
ON snapshot_catalog(request_id) WHERE request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS release_observation (
    release_id TEXT PRIMARY KEY,
    observed_at_ms INTEGER NOT NULL,
    source TEXT NOT NULL DEFAULT 'nas',
    version TEXT NOT NULL,
    channel TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    artifact_sha256 TEXT,
    signing_cert_sha256 TEXT,
    verification_state TEXT NOT NULL CHECK(verification_state IN (
        'unverified','manifest_valid','artifact_valid','rejected'
    )),
    metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
    canonical_text,
    subject,
    object_text,
    content='memory_atom',
    content_rowid='id',
    tokenize='unicode61'
);

CREATE TRIGGER IF NOT EXISTS memory_ai AFTER INSERT ON memory_atom BEGIN
    INSERT INTO memory_fts(rowid, canonical_text, subject, object_text)
    VALUES (new.id, new.canonical_text, new.subject, new.object_text);
END;

CREATE TRIGGER IF NOT EXISTS memory_ad AFTER DELETE ON memory_atom BEGIN
    INSERT INTO memory_fts(memory_fts, rowid, canonical_text, subject, object_text)
    VALUES ('delete', old.id, old.canonical_text, old.subject, old.object_text);
END;

CREATE TRIGGER IF NOT EXISTS memory_au
AFTER UPDATE OF canonical_text, subject, object_text ON memory_atom BEGIN
    INSERT INTO memory_fts(memory_fts, rowid, canonical_text, subject, object_text)
    VALUES ('delete', old.id, old.canonical_text, old.subject, old.object_text);
    INSERT INTO memory_fts(rowid, canonical_text, subject, object_text)
    VALUES (new.id, new.canonical_text, new.subject, new.object_text);
END;

CREATE VIEW IF NOT EXISTS v_active_memory AS
SELECT * FROM memory_atom
WHERE status = 'active'
  AND (expires_at_ms IS NULL OR expires_at_ms > CAST(unixepoch('subsec') * 1000 AS INTEGER));

CREATE VIEW IF NOT EXISTS v_open_friction AS
SELECT * FROM friction_event WHERE status IN ('open','investigating');

CREATE VIEW IF NOT EXISTS v_librarian_runnable_jobs AS
SELECT * FROM librarian_job
WHERE state = 'pending'
  AND (not_before_ms IS NULL OR not_before_ms <= CAST(unixepoch('subsec') * 1000 AS INTEGER))
ORDER BY priority DESC, created_at_ms ASC;
