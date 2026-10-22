package com.nexusmind.security.rbac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Permission Enum Tests")
class PermissionTest {

    @Test
    @DisplayName("SUPERVISOR should have orchestration permissions")
    void testSupervisorPermissions() {
        assertTrue(Permission.DAG_ORCHESTRATION.isAllowedFor(AgentRole.SUPERVISOR));
        assertTrue(Permission.TASK_COORDINATION.isAllowedFor(AgentRole.SUPERVISOR));
        assertTrue(Permission.SYSTEM_COMMAND.isAllowedFor(AgentRole.SUPERVISOR));
    }

    @Test
    @DisplayName("JOURNAL_AGENT should have journal permissions")
    void testJournalAgentPermissions() {
        assertTrue(Permission.JOURNAL_READ.isAllowedFor(AgentRole.JOURNAL_AGENT));
        assertTrue(Permission.JOURNAL_APPEND.isAllowedFor(AgentRole.JOURNAL_AGENT));
        assertFalse(Permission.CODE_WRITE.isAllowedFor(AgentRole.JOURNAL_AGENT));
        assertFalse(Permission.SYSTEM_COMMAND.isAllowedFor(AgentRole.JOURNAL_AGENT));
    }

    @Test
    @DisplayName("CODE_AGENT should have code execution permissions")
    void testCodeAgentPermissions() {
        assertTrue(Permission.CODE_READ.isAllowedFor(AgentRole.CODE_AGENT));
        assertTrue(Permission.CODE_WRITE.isAllowedFor(AgentRole.CODE_AGENT));
        assertTrue(Permission.GIT_OPERATION.isAllowedFor(AgentRole.CODE_AGENT));
        assertTrue(Permission.AST_PARSE.isAllowedFor(AgentRole.CODE_AGENT));
        assertFalse(Permission.JOURNAL_READ.isAllowedFor(AgentRole.CODE_AGENT));
        assertFalse(Permission.CREDENTIAL_ACCESS.isAllowedFor(AgentRole.CODE_AGENT));
    }

    @Test
    @DisplayName("TUTOR_AGENT should have read-only RAG access")
    void testTutorAgentPermissions() {
        assertTrue(Permission.RAG_READ.isAllowedFor(AgentRole.TUTOR_AGENT));
        assertTrue(Permission.KNOWLEDGE_SEARCH.isAllowedFor(AgentRole.TUTOR_AGENT));
        assertTrue(Permission.CODE_READ.isAllowedFor(AgentRole.TUTOR_AGENT));
        assertFalse(Permission.CODE_WRITE.isAllowedFor(AgentRole.TUTOR_AGENT));
        assertFalse(Permission.SYSTEM_COMMAND.isAllowedFor(AgentRole.TUTOR_AGENT));
    }

    @Test
    @DisplayName("Permission should deny denied roles")
    void testDeniedPermissions() {
        assertTrue(Permission.JOURNAL_APPEND.isDeniedFor(AgentRole.CODE_AGENT));
        assertTrue(Permission.CODE_WRITE.isDeniedFor(AgentRole.JOURNAL_AGENT));
        assertTrue(Permission.SYSTEM_COMMAND.isDeniedFor(AgentRole.TUTOR_AGENT));
    }

    @Test
    @DisplayName("Should return non-null permissionId")
    void testGetPermissionId() {
        assertNotNull(Permission.DAG_ORCHESTRATION.getPermissionId());
        assertFalse(Permission.DAG_ORCHESTRATION.getPermissionId().isEmpty());
    }

    @Test
    @DisplayName("All permissions should have descriptions")
    void testPermissionDescriptions() {
        for (Permission permission : Permission.values()) {
            assertNotNull(permission.getDescription());
            assertFalse(permission.getDescription().isEmpty());
        }
    }

    @Test
    @DisplayName("Credential access should be denied for all Agent roles")
    void testCredentialAccessDenied() {
        for (AgentRole role : AgentRole.values()) {
            assertFalse(Permission.CREDENTIAL_ACCESS.isAllowedFor(role));
        }
    }

    @Test
    @DisplayName("Health private access should be denied for all Agent roles")
    void testHealthPrivateAccessDenied() {
        for (AgentRole role : AgentRole.values()) {
            assertFalse(Permission.HEALTH_PRIVATE_READ.isAllowedFor(role));
        }
    }
}
