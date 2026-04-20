package com.devodox.stopatestimate.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires the addon-token interceptor for all {@code /api/**} routes so no controller can forget
 * authentication, and the argument resolver so controllers consume the pre-verified context via
 * a typed method parameter.
 */
@Configuration
public class AddonWebMvcConfig implements WebMvcConfigurer {

    private final AddonTokenVerificationInterceptor tokenInterceptor;
    private final VerifiedAddonContextArgumentResolver contextArgumentResolver;

    public AddonWebMvcConfig(
            AddonTokenVerificationInterceptor tokenInterceptor,
            VerifiedAddonContextArgumentResolver contextArgumentResolver) {
        this.tokenInterceptor = tokenInterceptor;
        this.contextArgumentResolver = contextArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(contextArgumentResolver);
    }
}
