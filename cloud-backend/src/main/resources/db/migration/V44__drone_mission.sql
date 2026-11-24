-- 最近一次成功上传或下载的无人机任务；协议传输过程不落库。
CREATE TABLE IF NOT EXISTS drone_missions (
    sysid        INTEGER PRIMARY KEY,
    tenant_id    INTEGER,
    items_json   TEXT NOT NULL,
    item_count   INTEGER NOT NULL,
    source       VARCHAR(20) NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_drone_missions_tenant_id ON drone_missions (tenant_id);
