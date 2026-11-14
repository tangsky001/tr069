# syntax=docker/dockerfile:1
#
# PilotACS 镜像构建（三段式）
#   1/3 node：编译 web/，产物落到 src/main/resources/static（vue.config.js 的 outputDir）
#   2/3 maven：打成 target/pilotacs-server.jar
#   3/3 jre：只带 jar 与 configuration/ 跑起来
#
# 构建上下文是仓库根目录：docker build -t pilotacs-server:latest .

# ==================== 1/3 构建前端 ====================
FROM node:20-alpine AS web

WORKDIR /build/web

# 依赖单独一层，package.json 没变就复用缓存
COPY web/package.json web/package-lock.json* ./
RUN npm install --legacy-peer-deps --no-audit --no-fund

COPY web/ ./
# 旧版 webpack 在 OpenSSL 3 下需要 legacy provider
ENV NODE_OPTIONS=--openssl-legacy-provider
RUN npm run build:prod

# ==================== 2/3 构建后端 ====================
FROM maven:3.9-eclipse-temurin-21 AS backend

WORKDIR /build

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
COPY sql ./sql
COPY configuration ./configuration
# 前端产物（web 阶段的输出目录就是 /build/src/main/resources/static）
COPY --from=web /build/src/main/resources/static ./src/main/resources/static

RUN mvn -B -ntp -DskipTests package

# ==================== 3/3 运行 ====================
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache tzdata wget
ENV TZ=Asia/Shanghai

WORKDIR /app
COPY --from=backend /build/target/pilotacs-server.jar /app/pilotacs-server.jar
COPY --from=backend /build/configuration /app/configuration

# 日志目录：logback 默认写进程工作目录下的 log/，挂出去方便翻
VOLUME ["/app/log", "/app/config"]

EXPOSE 8888 8899 9090 8082 6060 8012 8080 8172 5481

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/pilotacs-server.jar"]
