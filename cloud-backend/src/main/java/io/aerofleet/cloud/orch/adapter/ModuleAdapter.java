package io.aerofleet.cloud.orch.adapter;

import java.util.Map;

/**
 * 模块适配器接口，统一各业务模块的任务操作入口。
 * <p>
 * 每个业务模块（编队、配送、测绘、表演、应急）实现此接口，
 * 由编排层通过统一签名调用，屏蔽各模块 API 差异。
 */
public interface ModuleAdapter {

    /**
     * 创建任务。
     *
     * @param params 任务参数（各模块自定义）
     * @return 创建结果
     */
    ModuleResult createTask(Map<String, Object> params);

    /**
     * 启动任务。
     *
     * @param taskId 任务 ID
     * @return 启动结果
     */
    ModuleResult startTask(String taskId);

    /**
     * 中止任务。
     *
     * @param taskId 任务 ID
     * @return 中止结果
     */
    ModuleResult abortTask(String taskId);

    /**
     * 查询任务状态。
     *
     * @param taskId 任务 ID
     * @return 状态查询结果
     */
    ModuleResult getTaskStatus(String taskId);

    /**
     * 获取模块类型标识。
     *
     * @return 模块类型字符串
     */
    String getModuleType();
}