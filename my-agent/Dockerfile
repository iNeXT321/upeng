# ===== 后端多阶段构建 =====
# 构建：docker build -t ai-agent-backend .
# 运行：docker run -e DEEPSEEK_API_KEY=sk-xxx -e AGENT_JWT_SECRET=<32字节以上> -p 8123:8123 ai-agent-backend

# ---- Stage 1: 构建（依赖层缓存：先拷 pom 下载依赖，再拷源码，源码改动不重拉依赖） ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Maven 走阿里云镜像（构建提速；spring 里程碑仓库走官方）
RUN mkdir -p /root/.m2 && printf '<?xml version="1.0" encoding="UTF-8"?>\n<settings><mirrors><mirror><id>aliyunmaven</id><mirrorOf>*,!spring-milestones,!spring-snapshots,!central-portal-snapshots</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q dependency:go-offline -DskipTests || true
COPY src ./src
RUN mvn -q package -DskipTests

# ---- Stage 2: 运行时（精简 JRE，非 root 用户） ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 安全基线：不用 root 跑应用；预建日志/数据目录并授权
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/logs /app/data \
    && chown -R app:app /app/logs /app/data
USER app

COPY --from=build /app/target/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC"
EXPOSE 8123

# liveness probe 直接打无鉴权的健康端点
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
  CMD wget -qO- http://localhost:8123/api/ai/health > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
