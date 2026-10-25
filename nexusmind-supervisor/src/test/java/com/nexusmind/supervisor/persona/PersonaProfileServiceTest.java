package com.nexusmind.supervisor.persona;

import com.nexusmind.api.dto.JournalEntryDTO;
import com.nexusmind.api.dto.PersonaProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PersonaProfileService 单元测试
 *
 * 重点覆盖切片4 评审修复点:
 * - compute 原子化 (单线程串行下验证序列正确性，并发场景见 evolve 纯函数性)
 * - 盲区评分只增模型与上限截断
 * - totalReflectiveDays 按次数递增的语义
 * - 空阻碍不产生盲区记录
 */
class PersonaProfileServiceTest {

    private final PersonaProfileService service = new PersonaProfileService();

    private JournalEntryDTO entryWithBlockers(String... domains) {
        var blockers = java.util.Arrays.stream(domains)
                .map(d -> new JournalEntryDTO.BlockerItem(
                        "前端关闭连接后后端线程未释放的现象描述", "取消信号未逆向传播", d))
                .toList();
        var extraction = new JournalEntryDTO.StructuredExtraction(
                List.of("打通 Triple 协议"), blockers, List.of("补 3 个单元测试"));
        return new JournalEntryDTO("entry-001", "原始日记内容",
                java.time.LocalDate.of(2026, 10, 25), List.of("Dubbo"), extraction,
                System.currentTimeMillis());
    }

    @Test
    @DisplayName("首次演变: 从空画像创建，阻碍域按基线 0.3 + 0.1 = 0.4 入册")
    void shouldInitializeBlindSpotFromBaseline() {
        PersonaProfile updated = service.evolvePersonaProfile("user-1", entryWithBlockers("WebFlux"));

        assertEquals(0.4, updated.blindSpots().get("WebFlux"), 0.001);
        assertEquals(1, updated.totalReflectiveDays(), "首次复盘次数为 1");
    }

    @Test
    @DisplayName("重复演变: 同一阻碍域累计递增 0.4 -> 0.5 -> ... 上限截断 1.0")
    void shouldAccumulateAndCapBlindSpot() {
        service.evolvePersonaProfile("user-1", entryWithBlockers("WebFlux"));
        service.evolvePersonaProfile("user-1", entryWithBlockers("WebFlux"));

        PersonaProfile afterTwo = service.getOrCreateProfile("user-1");
        assertEquals(0.5, afterTwo.blindSpots().get("WebFlux"), 0.001);

        // 连续推进至截断
        for (int i = 0; i < 10; i++) {
            service.evolvePersonaProfile("user-1", entryWithBlockers("WebFlux"));
        }
        PersonaProfile capped = service.getOrCreateProfile("user-1");
        assertEquals(1.0, capped.blindSpots().get("WebFlux"), 0.001, "盲区评分上限 1.0");
    }

    @Test
    @DisplayName("多域阻碍: 各域独立计分互不干扰")
    void shouldTrackDomainsIndependently() {
        service.evolvePersonaProfile("user-1", entryWithBlockers("WebFlux", "Concurrency"));

        PersonaProfile profile = service.getOrCreateProfile("user-1");
        assertEquals(2, profile.blindSpots().size());
        assertEquals(0.4, profile.blindSpots().get("WebFlux"), 0.001);
        assertEquals(0.4, profile.blindSpots().get("Concurrency"), 0.001);
    }

    @Test
    @DisplayName("空阻碍条目: 不产生盲区记录但次数仍递增")
    void shouldHandleEmptyBlockers() {
        var extraction = new JournalEntryDTO.StructuredExtraction(
                List.of("平稳推进"), List.of(), List.of("继续推进"));
        var entry = new JournalEntryDTO("entry-002", "内容",
                java.time.LocalDate.of(2026, 10, 25), List.of(), extraction,
                System.currentTimeMillis());

        PersonaProfile updated = service.evolvePersonaProfile("user-1", entry);

        assertTrue(updated.blindSpots().isEmpty());
        assertEquals(1, updated.totalReflectiveDays());
    }

    @Test
    @DisplayName("多用户隔离: 不同 profileId 画像互不影响")
    void shouldIsolateProfilesByUserId() {
        service.evolvePersonaProfile("user-A", entryWithBlockers("WebFlux"));
        service.evolvePersonaProfile("user-B", entryWithBlockers("Dubbo"));

        assertEquals(0.4, service.getOrCreateProfile("user-A").blindSpots().get("WebFlux"), 0.001);
        assertNull(service.getOrCreateProfile("user-A").blindSpots().get("Dubbo"));
        assertNull(service.getOrCreateProfile("user-B").blindSpots().get("WebFlux"));
        assertEquals(0.4, service.getOrCreateProfile("user-B").blindSpots().get("Dubbo"), 0.001);
    }

    @Test
    @DisplayName("纯函数 evolve: 不改输入画像 (不可变)")
    void shouldNotMutateInputProfile() {
        PersonaProfile before = PersonaProfile.newProfile("user-1");

        service.evolve(before, entryWithBlockers("WebFlux"));

        assertTrue(before.blindSpots().isEmpty(), "输入画像必须保持不可变");
    }
}
