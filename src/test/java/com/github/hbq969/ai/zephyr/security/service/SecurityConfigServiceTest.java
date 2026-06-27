package com.github.hbq969.ai.zephyr.security.service;

import com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao;
import com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityConfigServiceTest {

    @Mock private SecurityConfigDao dao;

    @InjectMocks
    private SecurityConfigService service;

    @Test
    void refresh_shouldReturnEmptySnapshotWhenDbNotReady() {
        when(dao.queryAll()).thenThrow(new RuntimeException("table not found"));
        service.refresh();
        SecurityConfigService.ConfigSnapshot snap = service.getSnapshot();
        assertThat(snap.shellAllowedCommands()).isEmpty();
        assertThat(snap.defaultAllowCommands()).isEmpty();
        assertThat(snap.hardBlockPatterns()).isEmpty();
        assertThat(snap.softBlockPatterns()).isEmpty();
    }

    @Test
    void refresh_shouldLoadAllRulesFromDb() {
        SecurityRuleEntity e1 = new SecurityRuleEntity();
        e1.setRuleType(RULE_TYPE_SHELL_ALLOWED); e1.setRuleValue("ls");
        SecurityRuleEntity e2 = new SecurityRuleEntity();
        e2.setRuleType(RULE_TYPE_HARD_BLOCK); e2.setRuleValue("rm\\s+-rf");
        when(dao.queryAll()).thenReturn(List.of(e1, e2));
        service.refresh();
        SecurityConfigService.ConfigSnapshot snap = service.getSnapshot();
        assertThat(snap.shellAllowedCommands()).containsExactly("ls");
        assertThat(snap.hardBlockPatterns()).hasSize(1);
        assertThat(snap.defaultAllowCommands()).isEmpty();
    }

    @Test
    void refresh_shouldReloadSnapshotFromDb() {
        SecurityRuleEntity e1 = new SecurityRuleEntity();
        e1.setRuleType(RULE_TYPE_SOFT_BLOCK); e1.setRuleValue("kill\\s+-9");
        when(dao.queryAll()).thenReturn(List.of(e1));
        service.refresh();
        SecurityConfigService.ConfigSnapshot snap = service.getSnapshot();
        assertThat(snap.softBlockPatterns()).hasSize(1);
    }

    @Test
    void concurrentReads_shouldNotSeeCorruptedStateDuringRefresh() throws Exception {
        service.refresh();
        int readers = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(readers + 1);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(readers + 1);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < readers; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    for (int j = 0; j < 1000; j++) {
                        SecurityConfigService.ConfigSnapshot snap = service.getSnapshot();
                        assertThat(snap.shellAllowedCommands()).isNotNull();
                        assertThat(snap.hardBlockPatterns()).isNotNull();
                    }
                } catch (Exception e) { errors.incrementAndGet(); }
            });
        }
        executor.submit(() -> {
            try {
                barrier.await();
                for (int j = 0; j < 100; j++) {
                    when(dao.queryAll()).thenReturn(List.of());
                    service.refresh();
                }
            } catch (Exception e) { errors.incrementAndGet(); }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isEqualTo(0);
    }
}
