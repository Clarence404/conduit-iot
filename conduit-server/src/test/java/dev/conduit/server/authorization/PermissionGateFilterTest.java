package dev.conduit.server.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.server.auth.AuthPrincipal;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** 观察模式兼容、未知入口失败关闭及异常退出的线程上下文清理。 */
class PermissionGateFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); AuthorizationContext.clear(); }
    private AuthPrincipal actor() {
        var actor=new AuthPrincipal(1L,1L,"test","TENANT_VIEWER",null,false);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of()));
        return actor;
    }
    @Test void shadowDenialDoesNotChangeLegacyResultOrInstallScope() throws Exception {
        var actor=actor();
        var service=mock(AuthorizationService.class);
        when(service.resolve(actor)).thenReturn(new AuthorizedScope(1L,List.of(),true));
        var chain=mock(FilterChain.class);
        var request=new MockHttpServletRequest("GET","/api/v1/devices");
        var response=new MockHttpServletResponse();
        new PermissionGateFilter(service,new ObjectMapper()).doFilter(request,response,chain);
        verify(chain).doFilter(request,response);
        assertThat(AuthorizationContext.current()).isNull();
    }
    @Test void unknownRouteFailsClosedAndExceptionDoesNotLeakRequestScope() throws Exception {
        var actor=actor();
        var service=mock(AuthorizationService.class);
        when(service.enforcing()).thenReturn(true);
        when(service.resolve(actor)).thenReturn(new AuthorizedScope(1L,List.of(new AuthorizedScope.Grant("TENANT_OWNER","TENANT",null,null,PermissionCatalog.ALL)),true));
        var chain=mock(FilterChain.class);
        var response=new MockHttpServletResponse();
        var filter=new PermissionGateFilter(service,new ObjectMapper());
        filter.doFilter(new MockHttpServletRequest("GET","/api/v1/future-resources"),response,chain);
        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
        var request=new MockHttpServletRequest("GET","/api/v1/devices");
        doAnswer(invocation->{assertThat(AuthorizationContext.current()).isNotNull();throw new jakarta.servlet.ServletException("test");}).when(chain).doFilter(eq(request),any());
        assertThatThrownBy(()->filter.doFilter(request,new MockHttpServletResponse(),chain)).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(AuthorizationContext.current()).isNull();
    }
}
