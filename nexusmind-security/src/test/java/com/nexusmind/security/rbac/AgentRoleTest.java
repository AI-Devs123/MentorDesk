package com.nexusmind.security.rbac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agent Role Enum Tests")
class AgentRoleTest {

    @Test
    @DisplayName("Should retrieve all roles by roleId")
    void testFromId() {
        assertEquals(AgentRole.SUPERVISOR, AgentRole.fromId("supervisor"));
        assertEquals(AgentRole.JOURNAL_AGENT, AgentRole.fromId("journalAgent"));
        assertEquals(AgentRole.CODE_AGENT, AgentRole.fromId("codeAgent"));
        assertEquals(AgentRole.TUTOR_AGENT, AgentRole.fromId("tutorAgent"));
    }

    @Test
    @DisplayName("Should throw exception for invalid roleId")
    void testFromIdInvalid() {
        assertNull(AgentRole.fromId("INVALID_ROLE"));
        assertNull(AgentRole.fromId(null));
        assertNull(AgentRole.fromId(""));
    }

    @Test
    @DisplayName("Should return correct roleId")
    void testGetRoleId() {
        assertEquals("supervisor", AgentRole.SUPERVISOR.getRoleId());
        assertEquals("journalAgent", AgentRole.JOURNAL_AGENT.getRoleId());
        assertEquals("codeAgent", AgentRole.CODE_AGENT.getRoleId());
        assertEquals("tutorAgent", AgentRole.TUTOR_AGENT.getRoleId());
    }

    @Test
    @DisplayName("Should return non-null description")
    void testGetDescription() {
        assertNotNull(AgentRole.SUPERVISOR.getDescription());
        assertNotNull(AgentRole.JOURNAL_AGENT.getDescription());
        assertNotNull(AgentRole.CODE_AGENT.getDescription());
        assertNotNull(AgentRole.TUTOR_AGENT.getDescription());
        
        assertFalse(AgentRole.SUPERVISOR.getDescription().isEmpty());
        assertFalse(AgentRole.JOURNAL_AGENT.getDescription().isEmpty());
        assertFalse(AgentRole.CODE_AGENT.getDescription().isEmpty());
        assertFalse(AgentRole.TUTOR_AGENT.getDescription().isEmpty());
    }

    @Test
    @DisplayName("Should have all roles defined")
    void testAllRolesExist() {
        assertNotNull(AgentRole.SUPERVISOR);
        assertNotNull(AgentRole.JOURNAL_AGENT);
        assertNotNull(AgentRole.CODE_AGENT);
        assertNotNull(AgentRole.TUTOR_AGENT);
    }
}
