package com.motochat.security;

import com.motochat.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 按注解做接口鉴权。
 *
 * <p>为什么不用 Spring Security 的 {@code authorizeHttpRequests} 表达式：
 * 本项目的凭据是自定义内存 token（不是 UserDetails），塞进 SecurityContext
 * 需要额外适配器；直接用拦截器读 request attribute 更直观，也和原版
 * {@code Depends(require_user)} 的心智模型一致。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        boolean needAdmin = hm.hasMethodAnnotation(RequireAdmin.class)
                || hm.getBeanType().isAnnotationPresent(RequireAdmin.class);
        boolean needLogin = needAdmin
                || hm.hasMethodAnnotation(RequireLogin.class)
                || hm.getBeanType().isAnnotationPresent(RequireLogin.class);

        if (!needLogin) {
            return true;
        }

        SessionUser user = (SessionUser) request.getAttribute(AuthFilter.ATTR_USER);
        if (user == null) {
            throw ApiException.unauthorized("请先登录");
        }
        if (needAdmin && !user.isAdmin()) {
            throw ApiException.forbidden("需要管理员权限");
        }
        return true;
    }
}
