# GagneFlow 应用容器化（2026-08-18 新增）
# 解决 H-1 隐患: Docker 容器无中文字体导致 PDF 生成必然失败
#   - 安装 fonts-wqy-zenhei（路径 /usr/share/fonts/truetype/wqy/wqy-zenhei.ttc，
#     恰好命中 PdfGenerator.addChineseFonts 硬编码路径，零代码改动）
#
# 单阶段运行镜像（推荐）: 构建前先本地打包 `mvn package -DskipTests`
#   docker build -t gagneflow:latest .
# 运行: docker run -p 8080:8080 --env-file .env --network gagneflow_default \
#         --name gagneflow-app gagneflow:latest
# 注意: 依赖 docker-compose 里的 mysql/redis/milvus（同 network 时用容器名而非 localhost）
#
# 如需 CI 内自包含多阶段构建(maven 构建 + 运行), 参考:
#   FROM docker.1ms.run/library/maven:3.9-eclipse-temurin-17 AS build
#   ... mvn package ...
#   FROM docker.1ms.run/library/eclipse-temurin:17-jre-jammy
# 注意: maven 基础镜像大(400MB+), 1ms.run 对该镜像大层无缓存时回源慢, 本地打包更快

# 运行阶段（docker.1ms.run 2026-08 实测可用, JRE 镜像已本机缓存）
FROM docker.1ms.run/library/eclipse-temurin:17-jre-jammy
WORKDIR /app

# 中文字体: wqy-zenhei 命中硬编码路径 + noto-cjk 兜底
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        fonts-wqy-zenhei fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/* \
    && fc-cache -f >/dev/null 2>&1 || true

# 应用 jar（本地打包产物） + 运行时必需的外部资源
# 构建前: mvn package -DskipTests && 准备 dist/（见 Makefile docker-dist 目标）
# 注意: 镜像内无法再拉取外部资源, 必须随镜像一起 COPY
COPY dist/app.jar /app/app.jar
COPY dist/agent-config ./agent-config
COPY dist/lesson-plan-docs/k12_curriculum.json ./lesson-plan-docs/k12_curriculum.json
COPY dist/lesson-plan-docs/subject-formats.json ./lesson-plan-docs/subject-formats.json

# 可选: 显式指定字体路径（与硬编码路径一致, 双保险）
ENV GAGNEFLOW_PDF_FONT_PATH=/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc

EXPOSE 8080
# JWT_SECRET / DB / Redis / DashScope 凭据必须通过环境变量注入
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
