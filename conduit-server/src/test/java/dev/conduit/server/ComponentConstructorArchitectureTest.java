package dev.conduit.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构测试：每个 Spring 组件要么只有一个构造器，要么必须用 {@code @Autowired} 指明用哪个。
 *
 * <p>为什么需要这条：本仓库的集成测试依赖 Testcontainers，没有 Docker 的机器上跑不了，
 * 而 {@code mvn test} 本来也不跑 {@code *IT}，于是「单元测试全绿但 Spring 上下文根本起不来」
 * 成了一个盲区。2026-10-29 就因此把一个有两个构造器却没标 {@code @Autowired} 的
 * {@code @Component} 合进了 main，部署后服务崩溃重启循环
 * （{@code No default constructor found}）——Spring 在多个构造器之间无法选择时会去找默认构造器。
 *
 * <p>本测试不启动 Spring 上下文，只做反射检查，所以在任何机器上都能跑，补上的正是这个盲区。
 */
class ComponentConstructorArchitectureTest {

    @Test
    void 每个组件的构造器必须唯一或显式标注Autowired() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        List<String> violations = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("dev.conduit.server")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (type.getAnnotation(Component.class) == null || type.isInterface()) {
                continue;
            }
            Constructor<?>[] ctors = type.getDeclaredConstructors();
            if (ctors.length <= 1) {
                continue;
            }
            boolean marked = Arrays.stream(ctors).anyMatch(c -> c.isAnnotationPresent(Autowired.class));
            if (!marked) {
                violations.add(type.getName() + "（" + ctors.length + " 个构造器）");
            }
        }

        assertThat(violations)
                .withFailMessage("这些 @Component 有多个构造器却没有用 @Autowired 指明注入哪一个，"
                        + "Spring 启动时会报 No default constructor found：%s", violations)
                .isEmpty();
    }
}
