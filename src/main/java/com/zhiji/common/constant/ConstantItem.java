package com.zhiji.common.constant;

/**
 * 常量组枚举的统一契约：标注 @ConstantGroup 的枚举实现本接口，
 * 启动扫描后经 GET /api/constants 下发 code/name 给前端展示。
 */
public interface ConstantItem {

    String getCode();

    String getName();
}
