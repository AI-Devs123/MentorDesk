package com.nexusmind.security.rbac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agent RBAC Context Tests")
class AgentRbacContextTest {

    @BeforeEach
    void setUp() {
        // Clear context before each test
        AgentRbacContext.clearContext();
    }

    @Test
    @DisplayName("Should set and retrieve current agent by AgentRole")
    void testSetAndGetCurrentAgent() {
        AgentRbacContext.setCurrentAgent(AgentRole.SUPERVISOR);
        assertEquals("supervisor", AgentRbacContext.getCurrentAgent());
    }

    @Test
    @DisplayName("Should set and retrieve current agent by String roleId")
    void testSetCurrentAgentByString() {
        AgentRbacContext.setCurrentAgent("codeAgent");
        assertEquals("codeAgent", AgentRbacContext.getCurrentAgent());
    }

    @Test
    @DisplayName("Should retrieve current agent role as AgentRole enum")
    void testGetCurrentAgentRole() {
        AgentRbacContext.setCurrentAgent(AgentRole.JOURNAL_AGENT);
        assertEquals(AgentRole.JOURNAL_AGENT, AgentRbacContext.getCurrentAgentRole());
    }

    @Test
    @DisplayName("Should throw exception for invalid roleId string")
    void testSetCurrentAgentInvalidRole() {
        assertThrows(IllegalArgumentException.class, () -> {
            AgentRbacContext.setCurrentAgent("INVALID_ROLE");
        });
    }

    @Test
    @DisplayName("Should return null when no agent is set")
    void testGetCurrentAgentWhenNotSet() {
        assertNull(AgentRbacContext.getCurrentAgent());
    }

    @Test
    @DisplayName("Should return null for agent role when not set")
    void testGetCurrentAgentRoleWhenNotSet() {
        assertNull(AgentRbacContext.getCurrentAgentRole());
    }

    @Test
    @DisplayName("Should clear context properly")
    void testClearContext() {
        AgentRbacContext.setCurrentAgent(AgentRole.SUPERVISOR);
        assertNotNull(AgentRbacContext.getCurrentAgent());
        
        AgentRbacContext.clearContext();
        assertNull(AgentRbacContext.getCurrentAgent());
        assertNull(AgentRbacContext.getCurrentAgentRole());
    }

    @Test
    @DisplayName("Should switch between different agents")
    void testSwitchBetweenAgents() {
        AgentRbacContext.setCurrentAgent(AgentRole.SUPERVISOR);
        assertEquals(AgentRole.SUPERVISOR, AgentRbacContext.getCurrentAgentRole());
        
        AgentRbacContext.setCurrentAgent(AgentRole.CODE_AGENT);
        assertEquals(AgentRole.CODE_AGENT, AgentRbacContext.getCurrentAgentRole());
        
        AgentRbacContext.setCurrentAgent("tutorAgent");
        assertEquals(AgentRole.TUTOR_AGENT, AgentRbacContext.getCurrentAgentRole());
    }

    @Test
    @DisplayName("Should handle all valid agent roles")
    void testAllValidAgentRoles() {
        for (AgentRole role : AgentRole.values()) {
            AgentRbacContext.setCurrentAgent(role);
            assertEquals(role, AgentRbacContext.getCurrentAgentRole());
            assertEquals(role.getRoleId(), AgentRbacContext.getCurrentAgent());
        }
    }
}
