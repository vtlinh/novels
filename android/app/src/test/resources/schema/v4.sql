-- schema of version 4, as onCreate built it at commit 8249426
-- extracted by tools/extract-schemas.py; do not hand-edit
CREATE TABLE chapters (folder TEXT, slug TEXT, filename TEXT, uri TEXT, PRIMARY KEY(folder, slug, filename));
CREATE TABLE names (folder TEXT, slug TEXT, vi TEXT, en TEXT, PRIMARY KEY(folder, slug, vi));
CREATE TABLE pending_batches (batch_id TEXT PRIMARY KEY, folder TEXT, slug TEXT, files TEXT, created INTEGER, tries INTEGER, want_title TEXT);
CREATE TABLE titles (folder TEXT, slug TEXT, english TEXT, PRIMARY KEY(folder, slug));
