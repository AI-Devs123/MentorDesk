package com.nexusmind.security.rbac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agent RBAC Integration Tests")
class AgentRbacIntegrationTest {

    @BeforeEach
    void setUp() {
        AgentRbacContext.clearContext();
    }

    @Test
    @DisplayName("SUPERVISOR should have full orchestration permissions")
    void testSupervisorFullPermissions() {
        AgentRbacContext.setCurrentAgent(AgentRole.SUPERVISOR);
        AgentRole currentRole = AgentRbacContext.getCurrentAgentRole();
        
        assertTrue(Permission.DAG_ORCHESTRATION.isAllowedFor(currentRole));
        assertTrue(Permission.TASK_COORDINATION.isAllowedFor(currentRole));
        assertTrue(Permission.SYSTEM_COMMAND.isAllowedFor(currentRole));
    }

    @Test
    @DisplayName("JOURNAL_AGENT should be restricted from code operations")
    void testJournalAgentRestrictions() {
        AgentRbacContext.setCurrentAgent(AgentRole.JOURNAL_AGENT);
        AgentRole currentRole = AgentRbacContext.getCurrentAgentRole();
        
        assertTrue(Permission.JOURNAL_APPEND.isAllowedFor(currentRole));
        assertTrue(Permission.JOURNAL_READ.isAllowedFor(currentRole));
        
        assertFalse(Permission.CODE_WRITE.isAllowedFor(currentRole));
        assertFalse(Permission.GIT_OPERATION.isAllowedFor(currentRole));
        assertFalse(Permission.CREDENTIAL_ACCESS.isAllowedFor(currentRole));
    }

    @Test
    @DisplayName("CODE_AGENT should have repository operations but no journal access")
    void testCodeAgentCodeOperations() {
        AgentRbacContext.setCurrentAgent(AgentRole.CODE_AGENT);
        AgentRole currentRole = AgentRbacContext.getCurrentAgentRole();
        
        assertTrue(Permission.CODE_READ.isAllowedFor(currentRole));
        assertTrue(Permission.CODE_WRITE.isAllowedFor(currentRole));
        assertTrue(Permission.GIT_OPERATION.isAllowedFor(currentRole));
        
        assertFalse(Permission.JOURNAL_READ.isAllowedFor(currentRole));
        assertFalse(Permission.JOURNAL_APPEND.isAllowedFor(currentRole));
    }

    @Test
    @DisplayName("TUTOR_AGENT should have read-only RAG access")
    void testTutorAgentReadOnly() {
        AgentRbacContext.setCurrentAgent(AgentRole.TUTOR_AGENT);
        AgentRole currentRole = AgentRbacContext.getCurrentAgentRole();
        
        assertTrue(Permission.RAG_READ.isAllowedFor(currentRole));
        assertFalse(Permission.CODE_WRITE.isAllowedFor(currentRole));
        assertFalse(Permission.SYSTEM_COMMAND.isAllowedFor(currentRole));
    }

    @Test
    @DisplayName("Permission matrix should be consistent")
    void testPermissionMatrixConsistency() {
        // For each role, verify that denied permissions are correctly inverse of allowed
        for (AgentRole role : AgentRole.values()) {
            for (Permission perm : Permission.values()) {
                boolean isAllowed = perm.isAllowedFor(role);
                boolean isDenied = perm.isDeniedFor(role);
                
                // A permission should be either allowed or denied, not both, not neither
                assertNotEquals(isAllowed, isDenied, 
                    String.format("Permission %s for role %s has inconsistent state", 
                        perm.getPermissionId(), role.getRoleId()));
            }
        }
    }

    @Test
    @DisplayName("Should convert between roleId and AgentRole correctly")
    void testRoleConversion() {
        for (AgentRole role : AgentRole.values()) {
            String roleId = role.getRoleId();
            AgentRole converted = AgentRole.fromId(roleId);
            assertEquals(role, converted, 
                String.format("Role conversion failed for %s", roleId));
        }
    }

    @Test
    @DisplayName("Context should properly isolate between different agents")
    void testContextIsolation() {
        // Set SUPERVISOR
        AgentRbacContext.setCurrentAgent(AgentRole.SUPERVISOR);
        assertEquals(AgentRole.SUPERVISOR, AgentRbacContext.getCurrentAgentRole());
        
        // Switch to CODE_AGENT
        AgentRbacContext.setCurrentAgent(AgentRole.CODE_AGENT);
        assertEquals(AgentRole.CODE_AGENT, AgentRbacContext.getCurrentAgentRole());
        assertNotEquals(AgentRole.SUPERVISOR, AgentRbacContext.getCurrentAgentRole());
        
        // Verify CODE_AGENT has different permissions than SUPERVISOR
        assertFalse(Permission.DAG_ORCHESTRATION.isAllowedFor(AgentRbacContext.getCurrentAgentRole()));
    }

    @Test
    @DisplayName("Sensitive data access should be denied for all roles")
    void testSensitiveDataRestrictions() {
        for (AgentRole role : AgentRole.values()) {
            assertFalse(Permission.CREDENTIAL_ACCESS.isAllowedFor(role));
            assertFalse(Permission.HEALTH_PRIVATE_READ.isAllowedFor(role));
        }
    }
}
