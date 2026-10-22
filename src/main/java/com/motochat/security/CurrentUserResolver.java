package com.motochat.security;

import com.motochat.common.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 把 {@link CurrentUser} 标注的参数解析成会话用户。
 * 对应 Python 版 {@code user: dict = Depends(get_current_user)}。
 */
@Component
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && SessionUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        SessionUser user = request == null
                ? null
                : (SessionUser) request.getAttribute(AuthFilter.ATTR_USER);

        CurrentUser ann = parameter.getParameterAnnotation(CurrentUser.class);
        if (user == null && ann != null && ann.required()) {
            throw ApiException.unauthorized("未登录");
        }
        return user;
    }
}
