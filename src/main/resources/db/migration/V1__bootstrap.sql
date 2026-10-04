CREATE TABLE IF NOT EXISTS fkit_bi_schema_marker (
  id integer PRIMARY KEY,
  created_at timestamp with time zone NOT NULL DEFAULT now()
);
