# Veyra 轻量后端镜像
FROM node:22-alpine

# 国内构建加速：alpine 官方源（dl-cdn）在境内 VPS 上经常卡死超时，换阿里云镜像。
# sed 没匹配到也不会报错，境外构建无副作用。
RUN sed -i 's#dl-cdn.alpinelinux.org#mirrors.aliyun.com#g' /etc/apk/repositories || true

# 下载/合并依赖：ffmpeg（concat 合并 + ffprobe 编码探测），alpine 官方包含两件套；
# curl：proxy-fetch.js 抓取底层（黄果站点对 Node TLS 指纹风控，curl 正常）
RUN apk add --no-cache ffmpeg curl

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund --prefer-offline --registry=https://registry.npmmirror.com

# 业务代码整目录复制，靠 .dockerignore 排除 node_modules/test/data。
# 以前是逐个文件列举，0.28.9 新增 server/sources/ 时漏写了一行，
# 镜像里 index.js require('./sources') 直接 MODULE_NOT_FOUND 起不来 —— 不再列举。
# hongguo-sign.js：红果签名链 JS 内嵌实现（hongguo-app.js require 依赖，缺失会导致取流失败）
COPY . ./

# 本地取流服务：hongguo-app.js 的旧签名链（Gorgon/Argus/Ladon）已经过不了
# 2026-11-23 之后的校验，取流改由这个 Go 二进制负责（完整客户端签名 + CENC 密钥派生）。
# 二进制随源码一起 COPY 进来（fetch/），这里只按构建架构建个软链，不重复占空间。
# 2026-11-24 起，站方只接受 App 自带 so 产出的播放签名，内置移植版全被静默丢弃，
# 必须通过 HG_SIGNER 指向 openhongguo-signer（见 docker-compose.yml 的 hongguo-signer）。
ARG TARGETARCH=amd64
RUN ln -sf /app/fetch/hongguo-fetch-linux-${TARGETARCH} /usr/local/bin/hongguo-fetch \
    && chmod +x /app/fetch/hongguo-fetch-linux-${TARGETARCH}

ENV PORT=8080
ENV HG_PROFILE=/data/hongguo-profile.json
EXPOSE 8080
VOLUME /data

# 健康检查必须跟着 PORT 走：compose 里把 PORT 改成 8888，写死 8080 会一直 unhealthy
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/api/health" >/dev/null || exit 1

# 先起取流服务（失败不阻塞主进程），再交给 node
CMD ["sh", "-c", "/usr/local/bin/hongguo-fetch & exec node index.js"]
