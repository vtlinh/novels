-- schema of version 10, as onCreate built it at commit 2910bf9
-- extracted by tools/extract-schemas.py; do not hand-edit
CREATE TABLE chapters (folder TEXT, slug TEXT, filename TEXT, uri TEXT, PRIMARY KEY(folder, slug, filename));
CREATE TABLE names (folder TEXT, slug TEXT, vi TEXT, en TEXT, PRIMARY KEY(folder, slug, vi));
CREATE TABLE pending_batches (batch_id TEXT PRIMARY KEY, folder TEXT, slug TEXT, files TEXT, created INTEGER, tries INTEGER, want_title TEXT);
CREATE TABLE titles (folder TEXT, slug TEXT, english TEXT, PRIMARY KEY(folder, slug));
CREATE TABLE IF NOT EXISTS novels (folder TEXT, slug TEXT, url TEXT, title TEXT, started INTEGER, total INTEGER DEFAULT -1, complete INTEGER DEFAULT 0, author TEXT DEFAULT '', disk_count INTEGER DEFAULT 0, last_dl INTEGER DEFAULT 0, last_read INTEGER DEFAULT 0, PRIMARY KEY(folder, slug));
CREATE TABLE IF NOT EXISTS scanned (folder TEXT PRIMARY KEY, at INTEGER);
CREATE TABLE IF NOT EXISTS chapter_order (folder TEXT, slug TEXT, filename TEXT, ord INTEGER, PRIMARY KEY(folder, slug, filename));
