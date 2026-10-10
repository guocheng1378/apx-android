# AllPeriph Linux self-hosted runner —— 基于 Ubuntu 24.04
# 负责 Android（Gradle + Kotlin + NDK 27）+ 前端 Vite（React + Tailwind）构建。
#
# 与 Windows runner 同挂到 GitHub 同一个 repo（两个 runner 进程，runs-on label 路由）：
#   android / protocol / 前端 build → [self-hosted, linux, x86_64, apxpc-linux]
#   PC 原生 MSVC 编译                → [self-hosted, windows, x64, apxpc-win]
#   Release（只下载 artifact + 打 zip）→ ubuntu-latest（GitHub 官方 runner 足够）
#
# 镜像结构：
#   Ubuntu 24.04 → Node 20 (Debian repo) → JDK 17 Temurin → Android SDK cmdline-tools
#   → NDK 27.2.12479018 → CMake 3.22.1 → GitHub Actions Runner 2.327（固定版本）
#
# 构建：
#   docker build -t apxpc-linux-runner .
# 运行（见同目录 docker-compose.yml）：
#   docker compose up -d
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    LANG=zh_CN.UTF-8 \
    LC_ALL=zh_CN.UTF-8 \
    JAVA_HOME=/opt/java/openjdk \
    ANDROID_HOME=/opt/android-sdk \
    NDK_VERSION=27.2.12479018 \
    ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.2.12479018 \
    CMAKE_VERSION=3.22.1 \
    RUNNER_VERSION=2.327

# --- 1. 基础系统包 ---
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash ca-certificates curl git jq libicu-dev net-tools openssh-client \
        python3 python3-pip rsync sudo tar unzip zip wget xz-utils \
        build-essential autoconf libtool pkg-config \
        p7zip-full \
    && rm -rf /var/lib/apt/lists/* \
    && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && dpkg-reconfigure -f noninteractive tzdata

# --- 2. Node.js 20 LTS（官方 apt repo 24.04 自带 18，升级到 20）---
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && node --version && npm --version

# --- 3. JDK 17 (Eclipse Temurin，和原 GitHub Actions 一致) ---
ARG JAVA_VERSION=17.0.13_11
ARG JAVA_SHA=4c136f99c224b5c0900cf9e81f719be2c1527f5df16e3a4ae4cf78c99878b0b1
RUN mkdir -p /opt/java/openjdk \
    && curl -fSL -o /tmp/jdk.tar.gz \
       "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-${JAVA_VERSION}/OpenJDK17U-jdk_x64_linux_hotspot_${JAVA_VERSION}.tar.gz" \
    && echo "${JAVA_SHA} /tmp/jdk.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/jdk.tar.gz -C /opt/java/openjdk --strip-components=1 \
    && rm /tmp/jdk.tar.gz \
    && update-alternatives --install /usr/bin/java java /opt/java/openjdk/bin/java 100 \
    && update-alternatives --install /usr/bin/javac javac /opt/java/openjdk/bin/javac 100 \
    && java -version

# --- 4. Android SDK + NDK ---
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fSL -o /tmp/cmdline-tools.zip \
       "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" \
       "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip \
    && yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses || true \
    && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
        "platform-tools" \
        "build-tools;35.0.0" \
        "platforms;android-35" \
        "ndk;${NDK_VERSION}" \
        "cmake;${CMAKE_VERSION}" \
    && rm -rf /tmp/* \
    && ls "${ANDROID_HOME}" && ls "${ANDROID_HOME}/ndk/"

# --- 5. Gradle Wrapper 预装（CI 里 ./gradlew 首次执行要下载 Gradle，我们提前下好缓存）---
RUN mkdir -p /opt/gradle \
    && curl -fSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm /tmp/gradle.zip \
    && ln -s /opt/gradle/gradle-8.9/bin/gradle /usr/local/bin/gradle \
    && gradle --version

# --- 6. GitHub Actions Self-Hosted Runner ---
RUN mkdir -p /opt/actions-runner && cd /opt/actions-runner \
    && curl -fSL -o actions-runner.tar.gz \
       "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz" \
    && tar xzf actions-runner.tar.gz \
    && rm actions-runner.tar.gz \
    && ./bin/installdependencies.sh \
    && ls /opt/actions-runner

# runner 用户（非 root 跑，避免某些 Gradle task 以 root 写文件导致权限问题）
RUN groupadd -g 1000 runner \
    && useradd -m -u 1000 -g 1000 -d /home/runner -s /bin/bash runner \
    && chown -R runner:runner /opt/actions-runner /home/runner

# Gradle / npm 缓存目录预创建（持久化到 docker volume）
RUN mkdir -p /home/runner/.gradle /home/runner/.npm-cache \
    && chown -R runner:runner /home/runner

WORKDIR /opt/actions-runner
USER runner

# --- 启动 entrypoint：runner 注册 + 前台 run ---
# 用法（docker run 或 docker compose）：
#   -e REPO_URL=https://github.com/<owner>/<repo>
#   -e RUNNER_TOKEN=<从 GitHub 页面生成的 token>
#   -e RUNNER_LABELS=apxpc-linux,x64,linux
# entrypoint.sh 会 config.cmd + run.sh --once（每次容器启动重新注册一次，token 过期提示）
COPY entrypoint.sh /opt/actions-runner/entrypoint.sh
USER root
RUN chmod +x /opt/actions-runner/entrypoint.sh

CMD ["/opt/actions-runner/entrypoint.sh"]
