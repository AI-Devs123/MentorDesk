package com.nexusmind.security.rbac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agent RBAC Dubbo Filter Tests")
class AgentRbacDubboFilterTest {

    @Test
    @DisplayName("Filter should be properly annotated with @Activate")
    void testFilterActivateAnnotation() {
        AgentRbacDubboFilter filter = new AgentRbacDubboFilter();
        assertNotNull(filter);
        
        // Verify the filter class has @Activate annotation
        assertTrue(AgentRbacDubboFilter.class.isAnnotationPresent(
            org.apache.dubbo.common.extension.Activate.class));
    }

    @Test
    @DisplayName("Filter should have correct AGENT_ROLE_KEY constant")
    void testAgentRoleKeyConstant() {
        assertEquals("x-nexusmind-agent-role", AgentRbacDubboFilter.AGENT_ROLE_KEY);
    }

    @Test
    @DisplayName("AgentSecured annotation should exist on test service interface")
    void testAgentSecuredAnnotation() {
        // Verify the annotation is accessible
        assertTrue(TestService.class.isAnnotationPresent(AgentSecured.class));
        
        AgentSecured annotation = TestService.class.getAnnotation(AgentSecured.class);
        assertNotNull(annotation);
        assertEquals(2, annotation.allowedAgents().length);
    }

    @Test
    @DisplayName("Should identify allowed agents from annotation")
    void testAllowedAgentsFromAnnotation() {
        AgentSecured annotation = TestService.class.getAnnotation(AgentSecured.class);
        String[] allowedAgents = annotation.allowedAgents();
        
        assertTrue(java.util.Arrays.asList(allowedAgents).contains("SUPERVISOR"));
        assertTrue(java.util.Arrays.asList(allowedAgents).contains("CODE_AGENT"));
    }

    // Test service interface with @AgentSecured annotation
    @AgentSecured(allowedAgents = {"SUPERVISOR", "CODE_AGENT"})
    interface TestService {
        void securedMethod();
    }
}
