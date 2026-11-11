package com.mes.offline.api;

/**
 * 离线重传对外契约：定时任务清理过期的幂等记录。
 */
public interface OfflineSyncPort {

    /** 清掉过期的离线幂等记录，返回删除行数 */
    int cleanup();
}
