# Java 应用镜像（conduit-server、conduit-transport、conduit-simulator 共用），构建上下文为仓库根目录。
# 构建参数：
#   BUILD_FROM  产物来源：prebuilt（默认，复制 deploy/standalone/artifacts/ 中打好的 jar）或 source（在镜像内用 Maven 从源码编译，需联网）
#   MODULE      Maven 模块目录，例如 conduit-server（source 用）
#   JAR_NAME    jar 文件名（不含 .jar），例如 conduit-simulator-exec
# FROM ${BUILD_FROM} AS app 选中其中一个阶段，BuildKit 只构建被最终阶段用到的阶段：
# prebuilt 时不需要源码与 Maven 镜像，source 时不需要 artifacts/。
ARG BUILD_FROM=prebuilt

# 预编译产物：由开发机上的 deploy/package.sh 放入 deploy/standalone/artifacts/
FROM scratch AS prebuilt
ARG JAR_NAME
COPY deploy/standalone/artifacts/${JAR_NAME}.jar /out/app.jar

# 从源码编译：只复制 Maven 需要的父 pom、各 Java 模块与模拟器打包的演示物模型
FROM maven:3.9.9-eclipse-temurin-21 AS source
ARG MODULE
ARG JAR_NAME
WORKDIR /src
COPY pom.xml ./
COPY conduit-common conduit-common
COPY conduit-transport conduit-transport
COPY conduit-server conduit-server
COPY conduit-simulator conduit-simulator
COPY docs/examples docs/examples
# 只构建所需模块及其依赖；不激活 e2e 等 profile，测试由开发机负责
RUN mvn -B -q -DskipTests package -pl ${MODULE} -am \
    && mkdir -p /out && cp ${MODULE}/target/${JAR_NAME}.jar /out/app.jar

FROM ${BUILD_FROM} AS app

FROM eclipse-temurin:21.0.6_7-jre
WORKDIR /app
COPY --from=app /out/app.jar app.jar
# ExitOnOutOfMemoryError：OOM 后进程处于不可预测状态（如 transport 合批线程死亡后不再 ACK 而健康仍为 UP），
# 直接退出，由 compose 的 restart: unless-stopped（simulator 为 on-failure）重新拉起
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
