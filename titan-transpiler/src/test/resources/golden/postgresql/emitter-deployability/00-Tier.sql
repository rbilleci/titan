CREATE TABLE IF NOT EXISTS "test"."__enum_emitter_deployability_fixture_tier" (
    "ordinal" INTEGER PRIMARY KEY,
    "name" TEXT UNIQUE NOT NULL
);

INSERT INTO "test"."__enum_emitter_deployability_fixture_tier" ("ordinal", "name") VALUES
    (0, 'BASIC'),
    (1, 'PRO')
ON CONFLICT ("ordinal") DO UPDATE SET "name" = EXCLUDED."name";