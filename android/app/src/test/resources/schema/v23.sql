-- schema of version 23, as onCreate builds it
-- extracted to match Schema.create; do not hand-edit
CREATE TABLE chapters (folder TEXT, slug TEXT, filename TEXT, uri TEXT, url TEXT DEFAULT '', size INTEGER DEFAULT 0, hash TEXT DEFAULT '', PRIMARY KEY(folder, slug, filename));
CREATE TABLE names (folder TEXT, slug TEXT, vi TEXT, en TEXT, PRIMARY KEY(folder, slug, vi));
CREATE TABLE pending_batches (batch_id TEXT PRIMARY KEY, folder TEXT, slug TEXT, files TEXT, urls TEXT, created INTEGER, tries INTEGER, want_title TEXT);
CREATE TABLE titles (folder TEXT, slug TEXT, english TEXT, PRIMARY KEY(folder, slug));
CREATE TABLE IF NOT EXISTS folder_owner (folder TEXT, name TEXT, slug TEXT, PRIMARY KEY(folder, name));
CREATE TABLE IF NOT EXISTS novels (folder TEXT, slug TEXT, url TEXT, title TEXT, started INTEGER, total INTEGER DEFAULT -1, complete INTEGER DEFAULT 0, author TEXT DEFAULT '', disk_count INTEGER DEFAULT 0, last_dl INTEGER DEFAULT 0, last_read INTEGER DEFAULT 0, dir_name TEXT DEFAULT '', auto_dl INTEGER DEFAULT 0, translate INTEGER DEFAULT -1, resume_page INTEGER DEFAULT 0, resume_url TEXT DEFAULT '', resume_before INTEGER DEFAULT 0, alt_names TEXT DEFAULT '', genres TEXT DEFAULT '', source TEXT DEFAULT '', description TEXT DEFAULT '', status_label TEXT DEFAULT '', disk_bytes INTEGER DEFAULT -1, disk_stamp_dir TEXT DEFAULT '', disk_stamp_tr TEXT DEFAULT '', tts_lang TEXT DEFAULT '', PRIMARY KEY(folder, slug));
CREATE TABLE IF NOT EXISTS scanned (folder TEXT PRIMARY KEY, at INTEGER);
CREATE TABLE IF NOT EXISTS chapter_order (folder TEXT, slug TEXT, filename TEXT, ord INTEGER, PRIMARY KEY(folder, slug, filename));
CREATE TABLE IF NOT EXISTS chlist (folder TEXT, slug TEXT, pos INTEGER, name TEXT, src TEXT, tr TEXT, PRIMARY KEY(folder, slug, pos));
