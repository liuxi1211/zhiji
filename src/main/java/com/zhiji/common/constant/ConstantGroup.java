package com.zhiji.common.constant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在枚举类上声明其为常量组，value 为组名（与数据库 CHECK 约束、前端 types.ts 对应）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConstantGroup {

    String value();
}
