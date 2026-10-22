package com.motochat.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前登录用户。用法：
 * <pre>
 * public Object me(@CurrentUser SessionUser user) { ... }
 * public Object adminOnly(@CurrentUser(required = true) SessionUser user) { ... }
 * </pre>
 *
 * <p>{@code required = true} 时未登录直接抛 401，等价于 Python 的 {@code Depends(require_user)}。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
    boolean required() default false;
}
