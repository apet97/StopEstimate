package com.devodox.stopatestimate.web;

import com.devodox.stopatestimate.model.VerifiedAddonContext;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves {@link VerifiedAddonContext} controller parameters by reading the request attribute
 * populated by {@link AddonTokenVerificationInterceptor}. Controllers declare the context as a
 * method parameter and get the verified instance automatically.
 */
@Component
public class VerifiedAddonContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return VerifiedAddonContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new IllegalStateException("VerifiedAddonContext can only be resolved in a servlet request");
        }
        Object attribute = request.getAttribute(AddonTokenVerificationInterceptor.REQUEST_ATTRIBUTE);
        if (!(attribute instanceof VerifiedAddonContext context)) {
            // Should never happen: the interceptor must have run before the handler. If it did
            // not, the request bypassed /api/** mapping somehow — fail loudly.
            throw new IllegalStateException("VerifiedAddonContext not present; interceptor skipped?");
        }
        return context;
    }
}
