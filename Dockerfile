# ============================================================
# MES 后端多阶段构建：前端打包 -> 后端打 jar -> 精简运行时
# 构建上下文必须是仓库根目录（含 mes-frontend / mes-backend）
# ============================================================

# ---------- Stage 1: 构建前端静态资源 ----------
FROM node:20-alpine AS frontend
WORKDIR /build/frontend
COPY mes-frontend/package*.json ./
RUN npm ci
COPY mes-frontend/ ./
RUN npm run build

# ---------- Stage 2: 构建后端可执行 jar ----------
FROM maven:3.9-eclipse-temurin-25 AS backend
WORKDIR /build/backend
COPY mes-backend/pom.xml ./
COPY mes-backend/src ./src
# 把前端产物塞进后端静态目录，最终 jar 自带前端
COPY --from=frontend /build/frontend/dist ./src/main/resources/static
RUN mvn package -DskipTests -B

# ---------- Stage 3: 运行时（仅 JRE + jar，镜像最小） ----------
FROM eclipse-temurin:25-jre
# 健康检查需要 wget；jre 基础镜像不带，装一个
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=backend /build/backend/target/mes-backend-1.0.0.jar app.jar

# 日志目录：logback 通过 LOG_PATH 环境变量读取，默认值 logs
ENV LOG_PATH=/app/logs
RUN mkdir -p /app/logs

EXPOSE 8080

# 健康检查：Spring Boot Actuator /actuator/health
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
