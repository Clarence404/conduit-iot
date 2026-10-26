package dev.conduit.server.tenant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 架构测试：租户拦截器豁免只能存在于唯一受控的 tenant.system 包。 */
class InterceptorIgnoreArchitectureTest {

    @Test
    void interceptorIgnoreOnlyExistsInSystemPackage() throws IOException {
        Path module = Path.of(System.getProperty("user.dir"));
        Path source = Files.isDirectory(module.resolve("src/main/java"))
                ? module.resolve("src/main/java") : module.resolve("conduit-server/src/main/java");
        try (var files = Files.walk(source)) {
            List<String> violations = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("@InterceptorIgnore"))
                    .filter(p -> !read(p).contains("package dev.conduit.server.tenant.system;"))
                    .map(source::relativize)
                    .map(Path::toString)
                    .toList();
            assertThat(violations)
                    .withFailMessage("跨租户查询必须放入 tenant.system 包，并经 TenantContext.callAsSystem 调用: %s",
                            violations)
                    .isEmpty();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取源码: " + path, e);
        }
    }
}
