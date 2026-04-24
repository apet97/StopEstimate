package com.devodox.stopatestimate.it;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.api.ClockifyReportsApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A2: verifies we own a {@link org.springframework.security.core.userdetails.UserDetailsService}
 * bean so Spring Boot's {@code UserDetailsServiceAutoConfiguration} stays off and the
 * "Using generated security password" startup warning is silenced. Locking this via a test makes
 * sure a future Security upgrade can't silently regress the suppression.
 *
 * <p>D3: migrated from H2 to Testcontainers Postgres via {@link AbstractPostgresIT}. Bean wiring
 * is DB-agnostic, but the test loads a full Spring context so it inherits the IT path rather
 * than maintaining a separate H2 profile.
 */
class UserDetailsServiceBeanIT extends AbstractPostgresIT {

    @Autowired
    private InMemoryUserDetailsManager userDetailsService;

    @MockBean
    private ClockifyBackendApiClient backendApiClient;

    @MockBean
    private ClockifyReportsApiClient reportsApiClient;

    @Test
    void userDetailsServiceBeanIsEmptyInMemoryManager() {
        assertThat(userDetailsService).isNotNull();
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
