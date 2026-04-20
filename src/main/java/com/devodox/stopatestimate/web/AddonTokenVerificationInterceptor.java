package com.devodox.stopatestimate.web;

import com.devodox.stopatestimate.model.VerifiedAddonContext;
import com.devodox.stopatestimate.service.InvalidAddonTokenException;
import com.devodox.stopatestimate.service.VerifiedAddonContextService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Verifies the {@code X-Addon-Token} on every {@code /api/**} request and stores the resulting
 * {@link VerifiedAddonContext} under {@link #REQUEST_ATTRIBUTE}. Controllers consume the
 * pre-verified context via {@link VerifiedAddonContextArgumentResolver} instead of calling
 * {@link VerifiedAddonContextService#verifyRequired(String)} manually — a new /api/** route
 * can't forget authentication because it's enforced centrally.
 */
@Component
public class AddonTokenVerificationInterceptor implements HandlerInterceptor {

    public static final String REQUEST_ATTRIBUTE = "com.devodox.stopatestimate.verifiedAddonContext";
    public static final String TOKEN_HEADER = "X-Addon-Token";

    private final VerifiedAddonContextService verifiedAddonContextService;

    public AddonTokenVerificationInterceptor(VerifiedAddonContextService verifiedAddonContextService) {
        this.verifiedAddonContextService = verifiedAddonContextService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            throw new InvalidAddonTokenException("Missing X-Addon-Token header");
        }
        VerifiedAddonContext context = verifiedAddonContextService.verifyRequired(token);
        request.setAttribute(REQUEST_ATTRIBUTE, context);
        return true;
    }
}
