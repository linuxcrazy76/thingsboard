/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.tools.TbRateLimits;
import org.thingsboard.server.common.msg.tools.TbRateLimitsException;
import org.thingsboard.server.dao.tenant.TbTenantProfileCache;
import org.thingsboard.server.exception.ThingsboardErrorResponseHandler;
import org.thingsboard.server.service.security.model.SecurityUser;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class RateLimitProcessingFilter extends GenericFilterBean {

    @Autowired
    private ThingsboardErrorResponseHandler errorResponseHandler;

    @Autowired
    private TbTenantProfileCache tenantProfileCache;

    private ConcurrentMap<TenantId, TbRateLimits> perTenantLimits = new ConcurrentHashMap<>();
    private ConcurrentMap<CustomerId, TbRateLimits> perCustomerLimits = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        SecurityUser user = getCurrentUser();
        if (user != null && !user.isSystemAdmin()) {

            var profile= tenantProfileCache.get(user.getTenantId()).getDefaultTenantProfileConfiguration();

            if(profile != null) {
                if (StringUtils.isNotEmpty(profile.getRateLimitsTenantConfiguration())) {

                    TbRateLimits rateLimits = perTenantLimits.computeIfAbsent(
                            user.getTenantId(), id -> new TbRateLimits(profile.getRateLimitsTenantConfiguration())
                    );
                    if (!rateLimits.tryConsume()) {
                        errorResponseHandler.handle(new TbRateLimitsException(EntityType.TENANT), (HttpServletResponse) response);
                        return;
                    }
                }
                if (StringUtils.isNotEmpty(profile.getRateLimitsCustomerConfiguration())) {

                    TbRateLimits rateLimits = perCustomerLimits.computeIfAbsent(
                            user.getCustomerId(), id -> new TbRateLimits(profile.getRateLimitsCustomerConfiguration())
                    );
                    if (!rateLimits.tryConsume()) {
                        errorResponseHandler.handle(new TbRateLimitsException(EntityType.CUSTOMER), (HttpServletResponse) response);
                        return;
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }

    protected SecurityUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser) {
            return (SecurityUser) authentication.getPrincipal();
        } else {
            return null;
        }
    }

}
