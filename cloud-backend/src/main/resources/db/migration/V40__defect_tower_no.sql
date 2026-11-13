-- V40__defect_tower_no.sql: P1-8「按杆塔号 + 缺陷复检」——缺陷挂杆塔号
ALTER TABLE defects ADD COLUMN IF NOT EXISTS tower_no VARCHAR(40);
CREATE INDEX IF NOT EXISTS idx_defects_tower_no ON defects (tower_no);
