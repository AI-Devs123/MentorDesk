# NexusMind 切片 5：多阶段构建任意 Spring Boot 模块为可运行镜像
#
# 由 deploy/docker-compose.app.yml 驱动，传入 MODULE build arg 指定模块：
#   docker compose -f deploy/docker-compose.app.yml build
#
# 构建期在容器内 mvn package 指定模块及其上游（-am），运行期仅带 JRE + curl（探针用）。
# 运行参数（端口 / nacos 寻址 / redis 寻址 / mock 开关）由 compose 经 JAVA_OPTS 注入。

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 全量拷贝仓库（构建上下文为仓库根，便于 -am 构建上游模块）
COPY . .
ARG MODULE
RUN mvn -B -q -pl ${MODULE} -am package -DskipTests \
    && jar=$(ls /app/${MODULE}/target/${MODULE}-*.jar | grep -v plain | head -1) \
    && cp "$jar" /app/app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
# 探针（/actuator/health/liveness）需要 curl；JRE 镜像为 Debian，apt 可用
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/app.jar /app/app.jar
# JAVA_OPTS 由 compose 注入（端口、nacos 寻址、redis 寻址、mock 开关等）
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]
