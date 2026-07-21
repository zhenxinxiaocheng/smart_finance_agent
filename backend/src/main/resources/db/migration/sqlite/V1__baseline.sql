CREATE TABLE "user" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    nickname TEXT,
    email TEXT,
    avatar TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE financial_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL UNIQUE,
    life_stage TEXT, monthly_income NUMERIC NOT NULL DEFAULT 0, fixed_expense NUMERIC NOT NULL DEFAULT 0,
    risk_preference TEXT, savings_goal_amount NUMERIC NOT NULL DEFAULT 0, savings_goal_deadline TEXT,
    monthly_budget_goal NUMERIC NOT NULL DEFAULT 0, notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, memory_type TEXT NOT NULL,
    memory_key TEXT NOT NULL, memory_value TEXT NOT NULL, confidence REAL NOT NULL DEFAULT 1,
    source_query TEXT, disabled INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, memory_type, memory_key)
);

CREATE TABLE agent_skill (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, skill_key TEXT NOT NULL, name TEXT NOT NULL,
    description TEXT, version TEXT, author TEXT, category TEXT, risk_level TEXT, input_schema TEXT,
    trigger_text TEXT, instruction_text TEXT, bound_tools TEXT, source_type TEXT NOT NULL, source_uri TEXT NOT NULL,
    source_version TEXT, enabled INTEGER NOT NULL DEFAULT 1, built_in INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(user_id, source_type, source_uri, skill_key)
);

CREATE TABLE skill_invocation_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, trace_id TEXT, skill_name TEXT NOT NULL,
    category TEXT, source_type TEXT, risk_level TEXT, input TEXT, success INTEGER NOT NULL DEFAULT 0,
    blocked INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER, summary TEXT, raw_result TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, trace_id TEXT NOT NULL UNIQUE,
    query TEXT NOT NULL, final_answer TEXT, status TEXT NOT NULL DEFAULT 'RUNNING',
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at TEXT, duration_ms INTEGER, error_message TEXT
);

CREATE TABLE agent_run_step (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, trace_id TEXT NOT NULL, step_number INTEGER NOT NULL,
    summary TEXT, tool_name TEXT, input TEXT, success INTEGER, observation_summary TEXT, error_message TEXT,
    status TEXT NOT NULL DEFAULT 'RUNNING', started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at TEXT,
    UNIQUE(trace_id, step_number)
);

CREATE TABLE agent_reflection (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, trace_id TEXT NOT NULL,
    suggestion_type TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, payload TEXT,
    status TEXT NOT NULL DEFAULT 'OPEN', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE agent_context_summary (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, conversation_id INTEGER,
    scope TEXT NOT NULL, summary TEXT NOT NULL, source_hash TEXT, covered_until_message_id INTEGER,
    token_count INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE "transaction" (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, amount NUMERIC NOT NULL,
    type TEXT NOT NULL DEFAULT 'EXPENSE', category TEXT NOT NULL, description TEXT, transaction_date TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE expense_category (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, icon TEXT,
    benchmark_min INTEGER, benchmark_max INTEGER, benchmark_label TEXT, sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0, UNIQUE(user_id, name)
);

CREATE TABLE chat_conversation (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, title TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, conversation_id INTEGER,
    role TEXT NOT NULL, content TEXT NOT NULL, trace_id TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pending_action (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, action_type TEXT NOT NULL,
    title TEXT NOT NULL, summary TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE budget (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, category TEXT NOT NULL,
    amount NUMERIC NOT NULL, month TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, category, month)
);

CREATE TABLE budget_alert (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, budget_id INTEGER, category TEXT NOT NULL,
    month TEXT NOT NULL, alert_level TEXT NOT NULL, threshold_percent INTEGER NOT NULL,
    budget_amount NUMERIC NOT NULL, spent_amount NUMERIC NOT NULL, message TEXT NOT NULL,
    read_flag INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE bill_import_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, original_filename TEXT, file_path TEXT NOT NULL,
    bill_type TEXT NOT NULL DEFAULT 'UNKNOWN', confidence NUMERIC NOT NULL DEFAULT 0, ocr_text TEXT, warnings TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE bill_candidate_transaction (
    id INTEGER PRIMARY KEY AUTOINCREMENT, bill_import_id INTEGER NOT NULL, user_id INTEGER NOT NULL,
    amount NUMERIC NOT NULL, type TEXT NOT NULL DEFAULT 'EXPENSE', category TEXT NOT NULL DEFAULT '其他',
    description TEXT, transaction_date TEXT NOT NULL, confidence NUMERIC NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'PENDING', transaction_id INTEGER, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE analysis_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, trace_id TEXT, analysis_type TEXT NOT NULL,
    title TEXT NOT NULL, summary TEXT NOT NULL, result TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_schedule (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, trace_id TEXT, name TEXT NOT NULL,
    description TEXT, cron_expression TEXT NOT NULL, timezone TEXT NOT NULL DEFAULT 'Asia/Shanghai', task_query TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1, last_run_at TEXT, next_run_at TEXT, lock_until TEXT,
    run_count INTEGER NOT NULL DEFAULT 0, consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_status TEXT, last_answer TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE agent_schedule_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT, schedule_id INTEGER NOT NULL, user_id INTEGER NOT NULL, trace_id TEXT,
    status TEXT NOT NULL, answer TEXT, error_message TEXT, started_at TEXT NOT NULL, finished_at TEXT NOT NULL,
    duration_ms INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
